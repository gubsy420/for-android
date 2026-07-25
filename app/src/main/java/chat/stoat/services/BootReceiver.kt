package chat.stoat.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fork addition (background-socket push): restarts [ForegroundSocketService] after a
 * device reboot when the user has opted into background-socket notifications, so push
 * survives a restart without the user having to open the app. BOOT_COMPLETED is an
 * allowed exemption for starting a foreground service from the background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ForegroundSocketService.startIfEnabled(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
