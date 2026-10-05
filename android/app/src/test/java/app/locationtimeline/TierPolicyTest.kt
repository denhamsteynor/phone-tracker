package app.locationtimeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TierPolicyTest {

    @Test
    fun tierValues() {
        assertEquals(600_000L, Tier.STILL.intervalMs)
        assertEquals(150.0, Tier.STILL.minDistanceM, 0.0)
        assertFalse(Tier.STILL.highAccuracy)
        assertEquals(30_000L, Tier.WALK.intervalMs)
        assertEquals(20.0, Tier.WALK.minDistanceM, 0.0)
        assertEquals(15_000L, Tier.BIKE.intervalMs)
        assertEquals(30.0, Tier.BIKE.minDistanceM, 0.0)
        assertEquals(10_000L, Tier.VEHICLE.intervalMs)
        assertEquals(50.0, Tier.VEHICLE.minDistanceM, 0.0)
        assertTrue(Tier.WALK.highAccuracy && Tier.BIKE.highAccuracy && Tier.VEHICLE.highAccuracy)
        assertEquals(180_000L, Tier.WALK.stillAfterMs)
        assertEquals(180_000L, Tier.BIKE.stillAfterMs)
        assertEquals(300_000L, Tier.VEHICLE.stillAfterMs)
        assertEquals(Tier.BIKE, Tier.fromKey("bike"))
        assertNull(Tier.fromKey("nope"))
    }

    @Test
    fun activityMapping() {
        assertEquals(Tier.VEHICLE, TierPolicy.tierForActivity(TierPolicy.IN_VEHICLE))
        assertEquals(Tier.BIKE, TierPolicy.tierForActivity(TierPolicy.ON_BICYCLE))
        assertEquals(Tier.WALK, TierPolicy.tierForActivity(TierPolicy.ON_FOOT))
        assertEquals(Tier.WALK, TierPolicy.tierForActivity(TierPolicy.WALKING))
        assertEquals(Tier.WALK, TierPolicy.tierForActivity(TierPolicy.RUNNING))
        assertEquals(Tier.STILL, TierPolicy.tierForActivity(TierPolicy.STILL))
        assertNull(TierPolicy.tierForActivity(TierPolicy.UNKNOWN))
        assertNull(TierPolicy.tierForActivity(TierPolicy.TILTING))
    }

    @Test
    fun speedMapping() {
        assertEquals(Tier.VEHICLE, TierPolicy.tierForSpeed(7f, 10f))
        assertEquals(Tier.VEHICLE, TierPolicy.tierForSpeed(30f, 30f))
        assertEquals(Tier.BIKE, TierPolicy.tierForSpeed(3f, 10f))
        assertEquals(Tier.BIKE, TierPolicy.tierForSpeed(6.9f, 10f))
        assertEquals(Tier.WALK, TierPolicy.tierForSpeed(1f, 10f))
        assertNull(TierPolicy.tierForSpeed(0.5f, 5f))
        assertNull("inaccurate fix ignored", TierPolicy.tierForSpeed(20f, 31f))
    }

    @Test
    fun moveDetection() {
        assertFalse(TierPolicy.isMove(Tier.WALK, 19.0, 5f))
        assertTrue(TierPolicy.isMove(Tier.WALK, 20.0, 10f))
        assertFalse("indoor GPS wander is not movement", TierPolicy.isMove(Tier.WALK, 40.0, 30f))
        assertTrue(TierPolicy.isMove(Tier.WALK, 45.0, 30f))
        assertFalse(TierPolicy.isMove(Tier.VEHICLE, 60.0, 50f))
        assertFalse(TierPolicy.isMove(Tier.VEHICLE, 49.0, 5f))
        assertTrue(TierPolicy.isMove(Tier.VEHICLE, 50.0, 5f))
        assertFalse(TierPolicy.isMove(Tier.STILL, 149.0, 5f))
        assertTrue(TierPolicy.isMove(Tier.STILL, 150.0, 20f))
        assertFalse("STILL needs 2x accuracy", TierPolicy.isMove(Tier.STILL, 180.0, 100f))
        assertTrue(TierPolicy.isMove(Tier.STILL, 200.0, 100f))
    }

    @Test
    fun afterMoveTransitions() {
        assertEquals(Tier.WALK, TierPolicy.afterMove(Tier.STILL, null))
        assertEquals(Tier.WALK, TierPolicy.afterMove(Tier.STILL, Tier.WALK))
        assertEquals(Tier.VEHICLE, TierPolicy.afterMove(Tier.STILL, Tier.VEHICLE))
        assertEquals(Tier.BIKE, TierPolicy.afterMove(Tier.WALK, Tier.BIKE))
        assertEquals("never bumps down", Tier.VEHICLE, TierPolicy.afterMove(Tier.VEHICLE, Tier.WALK))
        assertEquals(Tier.BIKE, TierPolicy.afterMove(Tier.BIKE, null))
    }

    @Test
    fun stillTimeout() {
        val t0 = 1_000_000L
        assertFalse(TierPolicy.shouldGoStill(Tier.WALK, t0, t0 + 179_999))
        assertTrue(TierPolicy.shouldGoStill(Tier.WALK, t0, t0 + 180_000))
        assertFalse(TierPolicy.shouldGoStill(Tier.VEHICLE, t0, t0 + 299_999))
        assertTrue(TierPolicy.shouldGoStill(Tier.VEHICLE, t0, t0 + 300_000))
        assertFalse(TierPolicy.shouldGoStill(Tier.STILL, t0, t0 + 10_000_000))
    }

    @Test
    fun haversine() {
        assertEquals(0.0, TierPolicy.distanceM(51.5, -0.12, 51.5, -0.12), 1e-9)
        // One degree of latitude is ~111.2 km.
        assertEquals(111_195.0, TierPolicy.distanceM(0.0, 0.0, 1.0, 0.0), 50.0)
        // London -> Paris is ~343.5 km.
        assertEquals(343_500.0, TierPolicy.distanceM(51.5074, -0.1278, 48.8566, 2.3522), 1_500.0)
    }
}
