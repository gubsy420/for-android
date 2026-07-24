package chat.stoat.unifiedpush

import android.content.Context
import chat.stoat.api.routes.misc.getRootRoute
import chat.stoat.api.routes.push.unsubscribePush
import chat.stoat.persistence.KVStorage
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
    /** True when a UnifiedPush distributor app is installed on the device. */
    fun hasDistributor(context: Context): Boolean =
        UnifiedPush.getDistributors(context).isNotEmpty()

    /** True when we have selected/registered a distributor for this app. */
    fun isRegistered(context: Context): Boolean =
        UnifiedPush.getSavedDistributor(context) != null

    fun registerIfAvailable(context: Context) {
        val distributors = UnifiedPush.getDistributors(context)
        if (distributors.isEmpty()) {
            logcat { "No UnifiedPush distributor installed; staying on FCM" }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Fork: respect an explicit opt-out from the notification settings so a
            // disabled state is not silently re-enabled on the next login.
            val rejected =
                KVStorage(context).getBoolean("pushNotificationsRejected") ?: false
            if (rejected) {
                logcat { "Push disabled by user; skipping UnifiedPush registration" }
                return@launch
            }

            register(context)
        }
    }

    /**
     * Selects the first available distributor and registers with it. The resulting
     * endpoint is delivered to [StoatUnifiedPushService.onNewEndpoint], which
     * subscribes it with the server.
     */
    suspend fun register(context: Context) {
        val distributors = UnifiedPush.getDistributors(context)
        if (distributors.isEmpty()) return

        // Multiple distributors are rare; default to the first. Users can switch
        // by uninstalling/installing distributor apps.
        UnifiedPush.saveDistributor(context, distributors.first())

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

    /** Unregisters from the distributor and removes the server-side subscription. */
    suspend fun unregister(context: Context) {
        runCatching { unsubscribePush() }
        runCatching { UnifiedPush.unregister(context) }
        runCatching { UnifiedPush.removeDistributor(context) }
    }
}
