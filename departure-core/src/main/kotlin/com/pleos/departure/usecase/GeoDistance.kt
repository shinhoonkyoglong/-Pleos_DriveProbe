package com.pleos.departure.usecase

import com.pleos.departure.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 두 좌표 사이 직선거리(대권 거리, Haversine). 반경 확인은 이 순수 계산만 쓴다
 * (FusedLocation ProximityAlert 사용 안 함 — 사용자 결정 11/09, 리뷰 task-03 #5-4).
 *
 * 지구를 구로 본다. 국내 수십 km 거리에서 타원체 대비 오차는 최대 약 0.5%라 허용 반경 판정에 충분하다(설계 판단).
 */
object GeoDistance {

    /** 지구 평균 반지름(m). IUGG 평균 반지름 R1. 동작 수치가 아니라 물리 상수라 Config 에 두지 않는다. */
    const val EARTH_MEAN_RADIUS_METERS: Double = 6_371_008.8

    /** [a]와 [b] 사이 직선거리(m). */
    fun meters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        // 부동소수 오차로 h 가 1을 살짝 넘으면 asin 이 NaN 이 되므로 1로 자른다(대척점 근처).
        return 2 * EARTH_MEAN_RADIUS_METERS * asin(sqrt(min(1.0, h)))
    }

    /**
     * [point]가 [center]에서 [radiusMeters] 안인가. **경계(거리 == 반경)는 안**으로 본다(설계 판단: 반경 "이내"라는 일상 표현).
     */
    fun isWithinRadius(center: GeoPoint, point: GeoPoint, radiusMeters: Double): Boolean =
        isWithin(meters(center, point), radiusMeters)

    /** 경계 규칙의 **유일한 정의**(리뷰 task-05 MINOR-1): 거리 ≤ 반경이면 안. 이미 잰 거리로 판정할 때 쓴다. */
    fun isWithin(distanceMeters: Double, radiusMeters: Double): Boolean {
        require(radiusMeters > 0.0) { "radiusMeters must be > 0: $radiusMeters" }
        return distanceMeters <= radiusMeters
    }
}
