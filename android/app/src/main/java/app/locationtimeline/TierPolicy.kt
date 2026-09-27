package app.locationtimeline

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How aggressively we track. Ordered from least to most frequent, so ordinals can be compared.
 * STILL is only a "wake-up watch": infrequent, low-power fixes that notice when we start moving.
 */
enum class Tier(
    val key: String,
    val label: String,
    val intervalMs: Long,
    val minDistanceM: Double,
    val highAccuracy: Boolean,
    val stillAfterMs: Long,
) {
    STILL("still", "Still", 10 * 60_000L, 150.0, false, 0L),
    WALK("walk", "Walking", 30_000L, 20.0, true, 3 * 60_000L),
    BIKE("bike", "Cycling", 15_000L, 30.0, true, 3 * 60_000L),
    VEHICLE("vehicle", "Driving", 10_000L, 50.0, true, 5 * 60_000L);

    companion object {
        fun fromKey(key: String?): Tier? = entries.firstOrNull { it.key == key }
    }
}

/** Pure decision logic for adaptive tracking. No Android imports so it can be unit-tested on the JVM. */
object TierPolicy {
    // Values of com.google.android.gms.location.DetectedActivity.
    const val IN_VEHICLE = 0
    const val ON_BICYCLE = 1
    const val ON_FOOT = 2
    const val STILL = 3
    const val UNKNOWN = 4
    const val TILTING = 5
    const val WALKING = 7
    const val RUNNING = 8

    /** Speeds from fixes worse than this are not trusted. */
    const val MAX_SPEED_ACCURACY_M = 30f

    fun tierForActivity(type: Int): Tier? = when (type) {
        IN_VEHICLE -> Tier.VEHICLE
        ON_BICYCLE -> Tier.BIKE
        ON_FOOT, WALKING, RUNNING -> Tier.WALK
        STILL -> Tier.STILL
        else -> null
    }

    fun tierForSpeed(mps: Float, accuracy: Float): Tier? {
        if (accuracy > MAX_SPEED_ACCURACY_M) return null
        return when {
            mps >= 7f -> Tier.VEHICLE
            mps >= 3f -> Tier.BIKE
            mps >= 1f -> Tier.WALK
            else -> null
        }
    }

    /** Is this fix far enough from the last recorded point to count as real movement? */
    fun isMove(tier: Tier, distFromLastRecordedM: Double, accuracy: Float): Boolean {
        if (distFromLastRecordedM < tier.minDistanceM) return false
        if (tier == Tier.STILL && distFromLastRecordedM < 2.0 * accuracy) return false
        return true
    }

    /** Tier to use after movement was detected. Leaving STILL goes at least to WALK; speed can bump it up. */
    fun afterMove(current: Tier, speedTier: Tier?): Tier {
        var t = if (current == Tier.STILL) Tier.WALK else current
        if (speedTier != null && speedTier.ordinal > t.ordinal) t = speedTier
        return t
    }

    fun shouldGoStill(tier: Tier, lastMovementAt: Long, now: Long): Boolean =
        tier != Tier.STILL && now - lastMovementAt >= tier.stillAfterMs

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_008.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(min(1.0, a)))
    }
}
