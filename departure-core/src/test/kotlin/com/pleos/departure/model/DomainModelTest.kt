package com.pleos.departure.model

import com.pleos.departure.DepartureConfig
import com.pleos.departure.WarningThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class DomainModelTest {

    private fun assertRejected(block: () -> Unit) {
        assertTrue("IllegalArgumentException 이 나야 한다", runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }

    // --- GeoPoint ---

    @Test
    fun geoPointAcceptsBoundaryValues() {
        GeoPoint(latitude = 90.0, longitude = 180.0)
        GeoPoint(latitude = -90.0, longitude = -180.0)
    }

    @Test
    fun geoPointRejectsOutOfRange() {
        assertRejected { GeoPoint(latitude = 90.0001, longitude = 0.0) }
        assertRejected { GeoPoint(latitude = 0.0, longitude = -180.0001) }
    }

    // --- DriverCondition ---

    @Test
    fun driverConditionUsesDefaultRadiusWhenNotSet() {
        val condition = DriverCondition(radiusCenter = GeoPoint(latitude = 37.5, longitude = 127.0))

        assertEquals(10_000.0, condition.effectiveRadiusMeters(DepartureConfig().defaultAllowedRadiusMeters), 0.0)
        assertEquals(3_000.0, condition.copy(allowedRadiusMeters = 3_000.0).effectiveRadiusMeters(10_000.0), 0.0)
    }

    @Test
    fun driverConditionRejectsNonPositiveRadius() {
        assertRejected { DriverCondition(allowedRadiusMeters = 0.0) }
    }

    @Test
    fun noNightDrivingReflectsTimeConstraint() {
        assertFalse(DriverCondition().noNightDriving)
        assertTrue(DriverCondition(timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING)).noNightDriving)
    }

    @Test
    fun routePreferenceIsSetBased() {
        val p = RoutePreference.of(RouteConstraint.AVOID_HIGHWAY, RouteConstraint.PREFER_TOLL_FREE, RouteConstraint.AVOID_HIGHWAY)

        assertEquals(2, p.constraints.size)
        assertTrue(RoutePreference.NONE.isEmpty)
    }

    // --- RouteMapping ---

    @Test
    fun routeMappingOverallIsWeakestGuarantee() {
        val pref = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE, RouteConstraint.AVOID_HIGHWAY)
        val approx = RouteMapping(
            pref, RouteOption.FREE,
            mapOf(RouteConstraint.PREFER_TOLL_FREE to Guarantee.EXACT, RouteConstraint.AVOID_HIGHWAY to Guarantee.APPROXIMATE),
        )
        val unsupported = approx.copy(perConstraint = approx.perConstraint + (RouteConstraint.AVOID_HIGHWAY to Guarantee.UNSUPPORTED))
        val empty = RouteMapping(RoutePreference.NONE, RouteOption.FREE, emptyMap())

        assertEquals(Guarantee.APPROXIMATE, approx.overall)
        assertEquals(Guarantee.UNSUPPORTED, unsupported.overall)
        assertEquals(Guarantee.EXACT, empty.overall)
    }

    // --- TripEstimate ---

    private val basis: Instant = Instant.parse("2026-11-09T07:00:00Z")

    @Test
    fun tripEstimateFreshnessBoundaryIsInclusive() {
        val estimate = TripEstimate(Duration.ofMinutes(35), 9_800, EstimateSource.SDK_MEASURED, basis)
        val limit = Duration.ofMinutes(15)

        assertTrue(estimate.isFresh(basis.plus(limit), limit))
        assertFalse(estimate.isFresh(basis.plus(limit).plusSeconds(1), limit))
        assertTrue(estimate.isFresh(basis, limit))
    }

    @Test
    fun tripEstimateRejectsNegativeValues() {
        assertRejected { TripEstimate(Duration.ofMinutes(-1), null, EstimateSource.DEFAULT, basis) }
        assertRejected { TripEstimate(Duration.ZERO, -1, EstimateSource.DEFAULT, basis) }
    }

    // --- RouteState ---

    @Test
    fun routeStateGuidingAllowsUnknownTollButRejectsNegative() {
        assertNull(RouteState.Guiding(RouteOption.FREE, tollFare = null).tollFare)
        assertEquals(0, RouteState.Guiding(RouteOption.FREE, tollFare = 0).tollFare)
        assertRejected { RouteState.Guiding(RouteOption.FREE, tollFare = -1) }
    }

    // --- DataResult ---

    @Test
    fun dataResultGetOrNull() {
        assertEquals(3, DataResult.Available(3).getOrNull())
        assertNull(DataResult.Unavailable(UnavailableReason.TIMEOUT).getOrNull())
    }

    // --- DepartureConfig ---

    @Test
    fun configDefaultsMatchDesign() {
        val c = DepartureConfig()

        assertEquals(ZoneId.of("Asia/Seoul"), c.timeZone)
        assertEquals(Duration.ofSeconds(2), c.loadingIndicatorThreshold)
        assertEquals(RouteOption.RECOMMENDED, c.defaultRouteOption)
        // #5b: requestRoute 를 쓰지 않음. 경로 상태 조회는 get*Info 실측 20~35ms → 5초
        assertEquals(Duration.ofSeconds(5), c.routeStateCallbackTimeout)
        assertEquals(Duration.ofSeconds(5), c.destinationCallbackTimeout)
    }

    @Test
    fun configRejectsInvalidValues() {
        assertRejected { DepartureConfig(tollFreeDurationFactor = 0.9) }
        assertRejected { DepartureConfig(tripCallbackTimeout = Duration.ZERO) }
        assertRejected { DepartureConfig(destinationCallbackTimeout = Duration.ZERO) }
        assertRejected { WarningThresholds(cautionBefore = Duration.ofMinutes(10), urgentBefore = Duration.ofMinutes(10)) }
    }
}
