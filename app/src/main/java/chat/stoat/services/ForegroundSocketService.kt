package chat.stoat.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import chat.stoat.R
import chat.stoat.activities.MainActivity
import chat.stoat.api.StoatAPI
import chat.stoat.api.internals.ResourceLocations
import chat.stoat.api.realtime.DisconnectionState
import chat.stoat.api.realtime.RealtimeSocket
import chat.stoat.api.routes.user.fetchUser
import chat.stoat.api.settings.NotificationSettingsProvider
import chat.stoat.api.settings.SyncedSettings
import chat.stoat.c2dm.ChannelRegistrator
import chat.stoat.c2dm.PushMessageRenderer
import chat.stoat.core.model.data.STOAT_FILES
import chat.stoat.core.model.schemas.ChannelType
import chat.stoat.core.model.schemas.Message
import chat.stoat.core.model.schemas.User
import chat.stoat.persistence.KVStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

/**
 * Fork addition (background-socket push): a Google-free, distributor-free push
 * transport.
 *
 * Instead of relying on FCM or a UnifiedPush distributor, this foreground service
 * keeps the app's existing realtime websocket ([RealtimeSocket]) connected while the
 * app is backgrounded and renders notifications directly from the message frames it
 * already receives. Because the socket keeps us permanently "online", the server's
 * server-side push (pushd) is redundant — and notification decisions (including role
 * and mass mentions) are evaluated on-device, sidestepping the backend entirely.
 *
 * Trade-offs, by design: a persistent (minimal, low-priority) notification is
 * mandatory for any Android foreground service, and holding a socket open costs more
 * battery than a shared distributor. This is opt-in via the notification settings
 * (KV flag [KEY_ENABLED]).
 *
 * Uses the `specialUse` foreground-service type: the honest fit for "maintain a
 * connection to a self-hosted chat server, deliberately not using FCM", and unlike
 * `dataSync` it is not subject to the per-day runtime cap on Android 15+.
 */
class ForegroundSocketService : Service() {
    companion object {
        /** KV flag: whether the user has opted into background-socket push. */
        const val KEY_ENABLED = "foregroundSocketEnabled"

        private const val NOTIFICATION_ID = 68
        private const val ACTION_START = "chat.stoat.services.ForegroundSocketService.START"
        private const val ACTION_STOP = "chat.stoat.services.ForegroundSocketService.STOP"

        private const val RECONNECT_POLL_MS = 5_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ForegroundSocketService::class.java).setAction(ACTION_START)
                )
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the
                // background without an exemption; nothing actionable here.
                logcat(LogPriority.WARN) { "Could not start socket service\n" + e.asLog() }
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, ForegroundSocketService::class.java).setAction(ACTION_STOP)
                )
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Could not stop socket service\n" + e.asLog() }
            }
        }

        /** Start the service only if the user opted in and a session exists. */
        suspend fun startIfEnabled(context: Context) {
            val kv = KVStorage(context)
            if (kv.getBoolean(KEY_ENABLED) != true) return
            if (kv.get("sessionToken").isNullOrEmpty()) return
            start(context)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var supervising = false
    private val roleMentionRegex = Regex("<%([0-9A-Za-z]{26})>")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        // Must promote to foreground within ~5s of startForegroundService().
        ChannelRegistrator(this).register()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )

        if (!supervising) {
            supervising = true
            beginSupervision()
        }

        // STICKY so Android restarts us (and re-runs onStartCommand) if the process
        // is killed for memory; the reconnect supervisor re-establishes the socket.
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun beginSupervision() {
        // Keep the realtime socket connected, logging in first if this is a cold
        // process start (e.g. started from boot without the UI ever running).
        scope.launch {
            val token = KVStorage(applicationContext).get("sessionToken")
            if (token.isNullOrEmpty()) {
                logcat(LogPriority.WARN) { "Socket service started without a session; stopping" }
                stopForegroundAndSelf()
                return@launch
            }

            if (!StoatAPI.isLoggedIn()) {
                try {
                    StoatAPI.loginAs(token)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Socket service login failed\n" + e.asLog() }
                }
            }

            // The in-app reconnection logic only runs while the UI is composed, so
            // drive reconnection here for the backgrounded case.
            while (isActive) {
                if (RealtimeSocket.disconnectionState == DisconnectionState.Disconnected) {
                    logcat { "Socket dropped; reconnecting from service" }
                    RealtimeSocket.updateDisconnectionState(DisconnectionState.Reconnecting)
                    StoatAPI.connectWS()
                }
                delay(RECONNECT_POLL_MS)
            }
        }

        // Render notifications from live message frames.
        scope.launch {
            StoatAPI.wsFrameChannel
                .filterIsInstance<Message>()
                .collect { message ->
                    try {
                        maybeNotify(message)
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR) {
                            "Failed to render socket notification\n" + e.asLog()
                        }
                    }
                }
        }
    }

    private suspend fun maybeNotify(message: Message) {
        // Only surface notifications while backgrounded; the in-app UI handles
        // messages (and the currently-open channel) when the user is looking.
        if (AppVisibility.isForeground) return

        val selfId = StoatAPI.selfId
            ?: KVStorage(applicationContext).get("selfId")?.ifEmpty { null }
            ?: return
        val authorId = message.author ?: return
        if (authorId == selfId) return
        if (message.system != null) return
        val messageId = message.id ?: return
        val channelId = message.channel ?: return

        val channel = StoatAPI.channelCache[channelId] ?: return
        val serverId = channel.server

        if (!shouldNotify(message, channel.channelType, channelId, serverId, selfId)) return

        val (authorName, avatarUrl) = resolveAuthor(message, authorId, serverId)

        PushMessageRenderer.render(
            context = applicationContext,
            authorId = authorId,
            authorName = authorName,
            avatarUrl = avatarUrl,
            body = resolveBody(message),
            channelId = channelId,
            messageId = messageId
        )
    }

    private fun shouldNotify(
        message: Message,
        channelType: ChannelType?,
        channelId: String,
        serverId: String?,
        selfId: String
    ): Boolean {
        if (NotificationSettingsProvider.isChannelMuted(channelId, serverId)) return false

        val setting = SyncedSettings.notifications.channel[channelId]
            ?: serverId?.let { SyncedSettings.notifications.server[it] }
            ?: defaultSetting(channelType)

        return when (setting) {
            "all" -> true
            "mention" -> isMentioned(message, serverId, selfId)
            "muted", "none" -> false
            // Unknown/legacy value: fall back to the channel-type default.
            else -> defaultSetting(channelType) == "all" || isMentioned(message, serverId, selfId)
        }
    }

    private fun defaultSetting(channelType: ChannelType?): String =
        when (channelType) {
            ChannelType.DirectMessage, ChannelType.Group -> "all"
            else -> "mention"
        }

    private fun isMentioned(message: Message, serverId: String?, selfId: String): Boolean {
        if (message.mentions?.contains(selfId) == true) return true

        val content = message.content ?: return false
        // Mass mentions (evaluated client-side; the server does not expand these into
        // the message's `mentions` array).
        if (content.contains("@everyone") || content.contains("@online")) return true

        // Role mentions: `<%ROLEID>` for a role the current user holds in this server.
        if (serverId != null) {
            val selfRoles = StoatAPI.members.getMember(serverId, selfId)?.roles
            if (!selfRoles.isNullOrEmpty()) {
                val mentionedRoles =
                    roleMentionRegex.findAll(content).map { it.groupValues[1] }.toSet()
                if (mentionedRoles.any { it in selfRoles }) return true
            }
        }
        return false
    }

    private suspend fun resolveAuthor(
        message: Message,
        authorId: String,
        serverId: String?
    ): Pair<String, String> {
        message.masquerade?.name?.let { name ->
            return name to (message.masquerade?.avatar ?: "")
        }

        val member = serverId?.let { StoatAPI.members.getMember(it, authorId) }

        var user = StoatAPI.userCache[authorId]
        if (user == null) {
            user = runCatching { fetchUser(authorId) }.getOrNull()
            if (user != null) StoatAPI.userCache[authorId] = user
        }

        val name = member?.nickname
            ?: user?.let { User.resolveDefaultName(it) }
            ?: getString(R.string.unknown)

        val avatarUrl = member?.avatar?.let { "$STOAT_FILES/avatars/${it.id}" }
            ?: user?.let { ResourceLocations.userAvatarUrl(it) }
            ?: ""

        return name to avatarUrl
    }

    private fun resolveBody(message: Message): String {
        message.content?.takeIf { it.isNotBlank() }?.let { return it }
        if (!message.attachments.isNullOrEmpty()) {
            return getString(R.string.notification_message_attachment)
        }
        return getString(R.string.notification_message_fallback)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ChannelRegistrator.CHANNEL_ID_SERVICE_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification_monochrome)
            .setContentTitle(getString(R.string.notification_connection_title))
            .setContentText(getString(R.string.notification_connection_description))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
