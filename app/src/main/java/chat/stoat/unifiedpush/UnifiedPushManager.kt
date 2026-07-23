package chat.stoat.unifiedpush

import android.content.Context
import chat.stoat.api.routes.misc.getRootRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Fork addition (UnifiedPush support): registration entry point.
 *
 * If a UnifiedPush distributor app (e.g. ntfy) is installed, register with it —
 * the resulting endpoint is sent to the chat server by
 * [StoatUnifiedPushService.onNewEndpoint], replacing any FCM subscription for
 * this session. Without a distributor this is a no-op and FCM continues to be
 * used as before.
 */
object UnifiedPushManager {
    fun registerIfAvailable(context: Context) {
        val distributors = UnifiedPush.getDistributors(context)
        if (distributors.isEmpty()) {
            logcat { "No UnifiedPush distributor installed; staying on FCM" }
            return
        }

        // Multiple distributors are rare; default to the first. Users can switch
        // by uninstalling/installing distributor apps.
        UnifiedPush.saveDistributor(context, distributors.first())

        CoroutineScope(Dispatchers.IO).launch {
            // The server's VAPID public key lets the distributor validate senders
            // where supported; registration proceeds without it if unavailable.
            val vapid = try {
                getRootRoute().vapid.ifEmpty { null }
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Could not fetch VAPID key for UnifiedPush: $e" }
                null
            }

            try {
                UnifiedPush.register(context, vapid = vapid)
                logcat { "UnifiedPush registration requested via ${distributors.first()}" }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "UnifiedPush registration failed: $e" }
            }
        }
    }
}
