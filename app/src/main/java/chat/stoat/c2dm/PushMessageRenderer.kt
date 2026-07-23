package chat.stoat.c2dm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import chat.stoat.BuildConfig
import chat.stoat.R
import chat.stoat.activities.MainActivity
import chat.stoat.api.internals.ULID
import chat.stoat.api.routes.channel.fetchSingleChannel
import chat.stoat.c2dm.ChannelRegistrator.Companion.CHANNEL_ID_GROUP_CONVERSATIONS_MESSAGES
import chat.stoat.core.model.data.STOAT_FILES
import chat.stoat.persistence.Database
import chat.stoat.persistence.KVStorage
import chat.stoat.persistence.SqlStorage
import com.bumptech.glide.Glide
import kotlinx.coroutines.runBlocking

/**
 * Fork addition (UnifiedPush support): the message-notification rendering logic,
 * extracted verbatim from [HandlerService.onMessageReceived] so it can be shared
 * between the FCM path ([HandlerService]) and the UnifiedPush path
 * (`chat.stoat.unifiedpush.StoatUnifiedPushService`).
 */
object PushMessageRenderer {
    fun render(
        context: Context,
        authorId: String,
        authorName: String,
        avatarUrl: String,
        body: String,
        channelId: String,
        messageId: String
    ) {
        val messageTimestamp = ULID.asTimestamp(messageId)

        val db = Database(SqlStorage.driver)

        fun serverPrefix(serverId: String?): String? {
            if (serverId == null) return null
            return db.serverQueries.findById(serverId).executeAsOneOrNull()?.name
        }

        fun formatChannelName(type: String, name: String?, serverId: String?): String {
            val base = when (type) {
                "DirectMessage" -> return authorName
                "TextChannel" -> "#${name}"
                else -> name ?: return authorName
            }
            val prefix = serverPrefix(serverId) ?: return base
            return "$prefix · $base"
        }

        val channelName = db.channelQueries.findById(channelId).executeAsOneOrNull()?.let {
            formatChannelName(it.channelType, it.name, it.server)
        } ?: runBlocking {
            runCatching { fetchSingleChannel(channelId) }.getOrNull()?.let {
                formatChannelName(
                    it.channelType?.value ?: "",
                    it.name,
                    it.server
                )
            } ?: authorName
        }

        fun loadBitmap(url: String) = Glide.with(context)
            .asBitmap()
            .load(url)
            .circleCrop()
            .submit()
            .get()

        val kv = KVStorage(context)
        val selfId = runBlocking { kv.get("selfId") }.orEmpty()
        val selfName = runBlocking { kv.get("selfName") }.orEmpty()
        val selfAvatarUrl = runBlocking { kv.get("selfAvatarUrl") }

        val selfBitmap: Bitmap = if (!selfAvatarUrl.isNullOrEmpty()) {
            runCatching { loadBitmap(selfAvatarUrl) }.getOrNull()
                ?: generateLetterBitmap(selfName.ifEmpty { "?" })
        } else {
            generateLetterBitmap(selfName.ifEmpty { "?" })
        }

        val self = Person.Builder()
            .setBot(false)
            .setKey(selfId.ifEmpty { "self" })
            .setIcon(IconCompat.createWithBitmap(selfBitmap))
            .setName(selfName.ifEmpty { "Me" })
            .build()

        val dbChannel = db.channelQueries.findById(channelId).executeAsOneOrNull()

        val authorBitmap =
            runCatching { loadBitmap(avatarUrl) }.getOrElse { generateLetterBitmap(authorName) }
        val conversationBitmap: Bitmap = when (dbChannel?.channelType) {
            "TextChannel", "VoiceChannel" -> {
                val server =
                    dbChannel.server?.let { db.serverQueries.findById(it).executeAsOneOrNull() }
                val iconUrl = server?.iconId?.let { "$STOAT_FILES/icons/$it" }
                iconUrl?.let { runCatching { loadBitmap(it) }.getOrNull() }
                    ?: generateLetterBitmap(server?.name ?: channelName)
            }

            "Group" -> {
                val iconUrl = dbChannel.iconId?.let { "$STOAT_FILES/icons/$it" }
                iconUrl?.let { runCatching { loadBitmap(it) }.getOrNull() }
                    ?: generateLetterBitmap(dbChannel.name ?: channelName)
            }

            else -> authorBitmap
        }

        val author = Person.Builder()
            .setBot(false)
            .setKey(authorId)
            .setIcon(IconCompat.createWithBitmap(authorBitmap))
            .setName(authorName)
            .build()

        val shortcutId = "${BuildConfig.APPLICATION_ID}.channel.$channelId"

        val conversationIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("channelId", channelId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(channelName)
            .setLongLabel(channelName)
            .setIcon(IconCompat.createWithBitmap(conversationBitmap))
            .setIntent(conversationIntent)
            .setLongLived(true)
            .setPerson(author)
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

        val remoteInput = RemoteInput.Builder("content").run {
            setLabel(context.getString(R.string.message_context_sheet_actions_reply))
            build()
        }

        val replyIntent = Intent(context, ReplyReceiver::class.java).apply {
            putExtra("channelId", channelId)
        }

        val replyAction: NotificationCompat.Action =
            NotificationCompat.Action.Builder(
                R.drawable.ic_reply_24dp,
                context.getString(R.string.message_context_sheet_actions_reply),
                PendingIntent.getBroadcast(
                    context,
                    channelId.hashCode(),
                    replyIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
                .addRemoteInput(remoteInput)
                .build()

        val markAsReadIntent = Intent(context, MarkAsReadReceiver::class.java).apply {
            putExtra("channelId", channelId)
            putExtra("messageId", messageId)
        }

        val markAsReadAction: NotificationCompat.Action =
            NotificationCompat.Action.Builder(
                R.drawable.ic_mark_chat_read_24dp,
                context.getString(R.string.channel_context_sheet_actions_mark_read),
                PendingIntent.getBroadcast(
                    context,
                    channelId.hashCode() xor 1,
                    markAsReadIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
                .build()

        val contentIntent = PendingIntent.getActivity(
            context,
            channelId.hashCode(),
            conversationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val existingStyle = NotificationManagerCompat.from(context)
            .activeNotifications
            .firstOrNull { it.tag == channelId && it.id == NotificationID.NEW_MESSAGE }
            ?.notification
            ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }

        val messagingStyle = (existingStyle ?: NotificationCompat.MessagingStyle(self))
            .setGroupConversation(dbChannel?.channelType != "DirectMessage")
            .setConversationTitle(channelName)
            .addMessage(body, messageTimestamp, author)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_GROUP_CONVERSATIONS_MESSAGES)
            .setSmallIcon(R.drawable.ic_stoat_24dp)
            .setContentTitle(authorName)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setStyle(messagingStyle)
            .addAction(replyAction)
            .addAction(markAsReadAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        // Android 11 bubbles
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setShortcutId(shortcutId)
            builder.setLocusId(LocusIdCompat(shortcutId))

            val bubbleIntent = PendingIntent.getActivity(
                context,
                channelId.hashCode(),
                conversationIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val bubbleMetadata = NotificationCompat.BubbleMetadata.Builder(
                bubbleIntent,
                IconCompat.createWithBitmap(conversationBitmap)
            )
                .setDesiredHeight(600)
                .setAutoExpandBubble(false)
                .setSuppressNotification(false)
                .build()

            builder.setBubbleMetadata(bubbleMetadata)
        }

        NotificationManagerCompat.from(context).apply {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            notify(channelId, NotificationID.NEW_MESSAGE, builder.build())
        }
    }

    /**
     * Plain notification for non-message pushes (friend requests, calls, generic
     * alerts) delivered over UnifiedPush, which arrive as `{"body": ...}` or
     * `{"title": ..., "body": ...}` payloads.
     */
    fun renderSimple(context: Context, title: String?, body: String) {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_GROUP_CONVERSATIONS_MESSAGES)
            .setSmallIcon(R.drawable.ic_stoat_24dp)
            .setContentTitle(title ?: context.getString(R.string.app_name))
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        NotificationManagerCompat.from(context).apply {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            notify("generic", NotificationID.NEW_MESSAGE, builder.build())
        }
    }
}
