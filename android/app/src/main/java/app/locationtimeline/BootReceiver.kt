package app.locationtimeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts tracking after a reboot or an app update, if it was switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs(context).trackingEnabled) return
        if (!TrackingService.hasLocationPermission(context) || !TrackingService.hasBackgroundPermission(context)) return
        TrackingService.start(context)
        UploadWorker.schedulePeriodic(context)
    }
}
