package app.locationtimeline

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Foreground service that records the phone's location, adapting how often it asks for a fix
 * to how much the phone is moving (see [TierPolicy]).
 */
class TrackingService : Service() {

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.locationtimeline.STOP"
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val MAX_ACCURACY_M = 200f

        @Volatile
        var instance: TrackingService? = null
            private set

        fun hasLocationPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasBackgroundPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context): Boolean {
            if (!hasLocationPermission(context)) return false
            return try {
                context.startForegroundService(Intent(context, TrackingService::class.java))
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not start tracking", e)
                Prefs(context).recordError("Could not start tracking: ${e.message}")
                false
            }
        }

        fun stop(context: Context) {
            instance?.stopTracking()
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var fused: FusedLocationProviderClient
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    var tier: Tier = Tier.WALK
        private set
    private var lastMovementAt = 0L
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var activityPendingIntent: PendingIntent? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) onFix(loc)
        }
    }

    private val periodicCheck = object : Runnable {
        override fun run() {
            checkStill(System.currentTimeMillis())
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasLocationPermission(this)) {
            prefs.recordError("Location permission missing, tracking stopped")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            prefs.recordError("Could not start tracking in the background: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            instance = this
            tier = Tier.WALK
            lastMovementAt = System.currentTimeMillis()
            prefs.lastRecorded()?.let { (lat, lon, _) -> lastLat = lat; lastLon = lon }
            prefs.tierKey = tier.key
            requestLocationUpdates()
            registerActivityTransitions()
            handler.postDelayed(periodicCheck, CHECK_INTERVAL_MS)
            updateNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    fun stopTracking() {
        if (!started) return
        started = false
        handler.removeCallbacks(periodicCheck)
        fused.removeLocationUpdates(locationCallback)
        activityPendingIntent?.let { pi ->
            try {
                ActivityRecognition.getClient(this).removeActivityTransitionUpdates(pi)
            } catch (e: Exception) {
                Log.w(TAG, "removeActivityTransitionUpdates failed", e)
            }
        }
        activityPendingIntent = null
        if (instance === this) instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ---- Location handling ----

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        if (!hasLocationPermission(this)) return
        val priority = if (tier.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        // No setMinUpdateDistanceMeters: we want callbacks even when not moving so still-detection keeps running.
        val request = LocationRequest.Builder(priority, tier.intervalMs)
            .setMinUpdateIntervalMillis(tier.intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.removeLocationUpdates(locationCallback)
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            prefs.recordError("Location permission missing: ${e.message}")
        }
    }

    private fun onFix(loc: Location) {
        if (!started) return
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE
        if (accuracy > MAX_ACCURACY_M) return
        val now = System.currentTimeMillis()
        prefs.lastFixAt = now

        val speedTier = if (loc.hasSpeed()) TierPolicy.tierForSpeed(loc.speed, accuracy) else null

        if (lastLat.isNaN()) {
            // Very first point ever: just record where we are.
            record(loc, accuracy)
        } else {
            val dist = TierPolicy.distanceM(lastLat, lastLon, loc.latitude, loc.longitude)
            val moved = TierPolicy.isMove(tier, dist, accuracy)
            // Fast but not far (e.g. stop-start traffic) still counts as moving.
            val fast = speedTier != null && speedTier.ordinal >= Tier.BIKE.ordinal
            if (moved || fast) {
                lastMovementAt = now
                setTier(TierPolicy.afterMove(tier, speedTier))
            }
            if (moved) record(loc, accuracy)
        }
        checkStill(now)
    }

    private fun record(loc: Location, accuracy: Float) {
        val time = if (loc.time > 0) loc.time else System.currentTimeMillis()
        LocalDb.get(this).insert(
            recordedAt = time,
            lat = loc.latitude,
            lon = loc.longitude,
            accuracy = if (loc.hasAccuracy()) accuracy else null,
            speed = if (loc.hasSpeed()) loc.speed else null,
            activity = tier.key,
        )
        lastLat = loc.latitude
        lastLon = loc.longitude
        prefs.saveLastRecorded(loc.latitude, loc.longitude, time)
    }

    private fun checkStill(now: Long) {
        if (started && TierPolicy.shouldGoStill(tier, lastMovementAt, now)) setTier(Tier.STILL)
    }

    /** Called by [ActivityTransitionReceiver] with a DetectedActivity type the phone just entered. */
    fun onActivity(type: Int) {
        if (!started) return
        val t = TierPolicy.tierForActivity(type) ?: return
        if (t != Tier.STILL) lastMovementAt = System.currentTimeMillis()
        setTier(t)
    }

    private fun setTier(newTier: Tier) {
        if (newTier == tier) return
        Log.i(TAG, "Mode ${tier.label} -> ${newTier.label}")
        tier = newTier
        prefs.tierKey = newTier.key
        requestLocationUpdates()
        updateNotification()
    }

    /** Re-requests location and activity updates after the user grants more permissions. */
    fun onPermissionsChanged() {
        if (!started) return
        requestLocationUpdates()
        if (activityPendingIntent == null) registerActivityTransitions()
    }

    // ---- Activity recognition ----

    @SuppressLint("MissingPermission")
    private fun registerActivityTransitions() {
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        val types = listOf(
            DetectedActivity.STILL,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE,
        )
        val transitions = types.map {
            ActivityTransition.Builder()
                .setActivityType(it)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        }
        val intent = Intent(this, ActivityTransitionReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        activityPendingIntent = pi
        try {
            ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pi)
                .addOnFailureListener { e -> Log.w(TAG, "Activity transitions unavailable", e) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Activity recognition permission missing", e)
        }
    }

    // ---- Notification ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Location tracking", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while your location timeline is being recorded"
            setShowBadge(false)
        }
        nm?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (tier == Tier.STILL) "Still: checking every 10 min" else "Mode: ${tier.label}"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Recording your timeline")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        if (!started) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }
}
