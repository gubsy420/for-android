package chat.stoat.services

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Fork addition (background-socket push): tracks whether any activity is currently
 * in the started/visible state.
 *
 * [ForegroundSocketService] uses this to suppress notifications while the user is
 * actively looking at the app — the in-app UI already shows incoming messages — and
 * only raise them when the app is backgrounded. Registered from
 * [chat.stoat.StoatApplication.onCreate].
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var startedActivities = 0

    val isForeground: Boolean
        get() = startedActivities > 0

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedActivities > 0) startedActivities--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
