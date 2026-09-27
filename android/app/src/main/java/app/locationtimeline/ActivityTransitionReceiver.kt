package app.locationtimeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult

/** Receives activity-recognition transitions and forwards the newest ENTER event to the service. */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val latest = result.transitionEvents
            .filter { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER }
            .maxByOrNull { it.elapsedRealTimeNanos }
            ?: return
        TrackingService.instance?.onActivity(latest.activityType)
    }
}
