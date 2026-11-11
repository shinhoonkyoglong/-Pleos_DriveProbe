package com.pleos.departure.usecase

import com.pleos.departure.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoDistanceTest {

    private val home = GeoPoint(latitude = 37.5563, longitude = 126.9220) // Fake HOME(홍대입구)

    /** home 에서 정북으로 [meters] 떨어진 점. 같은 경선 위라 Haversine 거리 = R × Δφ 로 정확히 계산된다. */
    private fun northOf(origin: GeoPoint, meters: Double) = GeoPoint(
        latitude = origin.latitude + Math.toDegrees(meters / GeoDistance.EARTH_MEAN_RADIUS_METERS),
        longitude = origin.longitude,
    )

    @Test
    fun samePointIsZero() {
        assertEquals(0.0, GeoDistance.meters(home, home), 0.0)
    }

    @Test
    fun oneDegreeOfLatitudeMatchesSphereFormula() {
        val d = GeoDistance.meters(GeoPoint(latitude = 0.0, longitude = 0.0), GeoPoint(latitude = 1.0, longitude = 0.0))
        assertEquals(Math.PI * GeoDistance.EARTH_MEAN_RADIUS_METERS / 180.0, d, 1e-6) // 약 111,195m
    }

    @Test
    fun isSymmetric() {
        val gangnam = GeoPoint(latitude = 37.49813055, longitude = 127.02820277)
        assertEquals(GeoDistance.meters(home, gangnam), GeoDistance.meters(gangnam, home), 1e-9)
    }

    /** 리뷰 task-01c 독립 계산(Python Haversine)과 10m 안에서 맞는다. */
    @Test
    fun matchesReviewerIndependentValues() {
        val naviDefault = GeoPoint(latitude = 37.4127212, longitude = 127.094681) // 에뮬레이터 내비 기본 위치
        val gangnam = GeoPoint(latitude = 37.49813055, longitude = 127.02820277) // run4 목적지 강남역 2호선
        val origin = GeoPoint(latitude = 0.0, longitude = 0.0)

        assertEquals(22_070.0, GeoDistance.meters(naviDefault, home), 10.0)
        assertEquals(11_380.0, GeoDistance.meters(home, gangnam), 10.0)
        assertEquals(13_170_000.0, GeoDistance.meters(home, origin), 10_000.0) // "약 13,170km"
    }

    @Test
    fun antipodeDoesNotProduceNaN() {
        val d = GeoDistance.meters(GeoPoint(latitude = 0.0, longitude = 0.0), GeoPoint(latitude = 0.0, longitude = 180.0))
        assertEquals(Math.PI * GeoDistance.EARTH_MEAN_RADIUS_METERS, d, 1e-3)
    }

    // --- 반경 경계값(리뷰 task-03 #5-4) ---

    @Test
    fun exactlyRadiusIsWithin() {
        val point = northOf(home, 10_000.0)
        val d = GeoDistance.meters(home, point)
        assertEquals(10_000.0, d, 1e-6)
        assertTrue(GeoDistance.isWithinRadius(home, point, d)) // 정확히 반경 = 안
    }

    @Test
    fun fractionOfMeterAroundBoundary() {
        assertTrue(GeoDistance.isWithinRadius(home, northOf(home, 9_999.5), 10_000.0))
        assertFalse(GeoDistance.isWithinRadius(home, northOf(home, 10_000.5), 10_000.0))

        val point = northOf(home, 10_000.0)
        val d = GeoDistance.meters(home, point)
        assertTrue(GeoDistance.isWithinRadius(home, point, d + 0.001))
        assertFalse(GeoDistance.isWithinRadius(home, point, d - 0.001))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveRadiusIsRejected() {
        GeoDistance.isWithinRadius(home, home, 0.0)
    }

    /** 리뷰 task-05 MINOR-1: 경계 규칙은 isWithin 한 곳. isWithinRadius 도 같은 규칙을 쓴다. */
    @Test
    fun isWithin_isTheSingleBoundaryRule() {
        assertTrue(GeoDistance.isWithin(10_000.0, 10_000.0))
        assertFalse(GeoDistance.isWithin(10_000.001, 10_000.0))
    }
}
