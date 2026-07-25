package chat.stoat.unifiedpush

import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatJson
import chat.stoat.api.routes.push.subscribePush
import chat.stoat.c2dm.PushMessageRenderer
import chat.stoat.core.model.schemas.Message
import chat.stoat.core.model.data.STOAT_FILES
import chat.stoat.persistence.KVStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import logcat.LogPriority
import logcat.logcat
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Fork addition (UnifiedPush support): receives push messages from a UnifiedPush
 * distributor (e.g. ntfy) as an alternative to FCM, so notifications work without
 * Google services.
 *
 * The Stoat backend's vapid consumer sends the same payloads it sends to
 * browsers: for chat messages, a JSON `PushNotification` object; for friend
 * requests / calls, a small `{"body": ...}` object. Requires the instance's
 * pushd to encrypt with aes128gcm (see FORK_NOTES.md).
 *
 * IMPORTANT: the UnifiedPush connector invokes [onNewEndpoint] and [onMessage] on
 * the **main thread** (a broadcast receiver forwards to a bound service), unlike
 * FCM's `FirebaseMessagingService` which uses a background thread. All blocking
 * work — network subscribe, Glide image fetches, REST/DB lookups in the renderer —
 * must therefore be dispatched off the main thread here, or the app ANRs (which
 * manifests as repeated "app isn't responding" dialogs, especially when a
 * distributor redelivers a backlog of cached messages).
 */
@Serializable
private data class WebPushPayload(
    val author: String? = null,
    val icon: String? = null,
    val image: String? = null,
    val body: String? = null,
    val title: String? = null,
    val tag: String? = null,
    val message: Message? = null
)

class StoatUnifiedPushService : PushService() {
    // Work outlives the service's brief (~5s) bind window; the distributor keeps
    // the process alive via its foreground-raise. Use the application context so
    // rendering survives service teardown.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun ensureSession() {
        if (StoatAPI.sessionToken.isEmpty()) {
            KVStorage(applicationContext).get("sessionToken")?.let {
                StoatAPI.setSessionHeader(it)
            }
        }
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet ?: run {
            logcat(LogPriority.ERROR) { "UnifiedPush endpoint has no key set, cannot subscribe" }
            return
        }

        scope.launch {
            ensureSession()
            try {
                subscribePush(
                    endpoint = endpoint.url,
                    auth = keys.auth,
                    p256diffieHellman = keys.pubKey
                )
                logcat { "Subscribed UnifiedPush endpoint with server" }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Failed to subscribe UnifiedPush endpoint: $e" }
            }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) {
            logcat(LogPriority.ERROR) {
                "UnifiedPush message could not be decrypted. The server likely encrypts " +
                        "with legacy aesgcm instead of aes128gcm; see FORK_NOTES.md."
            }
            return
        }

        // Decode is cheap, but rendering does blocking I/O — run the whole thing
        // off the main thread.
        scope.launch {
            val payload = try {
                StoatJson.decodeFromString(
                    WebPushPayload.serializer(),
                    message.content.decodeToString()
                )
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Unparseable UnifiedPush payload: $e" }
                return@launch
            }

            val ctx = applicationContext
            val msg = payload.message
            if (msg != null) {
                PushMessageRenderer.render(
                    context = ctx,
                    authorId = msg.author ?: "",
                    authorName = payload.author ?: "Unknown",
                    avatarUrl = payload.icon
                        ?: msg.author?.let { "$STOAT_FILES/avatars/$it" }.orEmpty(),
                    body = payload.body ?: "",
                    channelId = msg.channel ?: payload.tag ?: return@launch,
                    messageId = msg.id ?: return@launch
                )
            } else {
                PushMessageRenderer.renderSimple(
                    context = ctx,
                    title = payload.title ?: payload.author,
                    body = payload.body ?: return@launch
                )
            }
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        logcat(LogPriority.ERROR) { "UnifiedPush registration failed: $reason" }
    }

    override fun onUnregistered(instance: String) {
        logcat(LogPriority.WARN) { "UnifiedPush distributor unregistered us" }
    }
}
