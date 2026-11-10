package ai.pleos.playground.sample.vehicle.condition.core

import kotlin.math.abs
import kotlin.math.max

/** 주행 1회의 요약(유효 창 특징들의 중앙값). 기준선에 1개씩 쌓인다. */
data class TripSummary(
    val endTimeMs: Long,
    val reversalsSmallPerMin: Float,
    val reversalsLargePerMin: Float,
    val stdDeg: Float,
)

/** 중앙값 + 흩어짐(scale = 1.4826 × MAD, 최소값 보장). z = (x - median) / scale */
data class RobustStat(val median: Float, val scale: Float) {
    fun z(x: Float): Float = (x - median) / scale
}

data class SteeringBaseline(
    val small: RobustStat,
    val large: RobustStat,
    val std: RobustStat,
    val tripCount: Int,
    val isCalibrating: Boolean,
)

data class BaselineParams(
    val maxTrips: Int = 20,
    /** 이 횟수 이상 쌓여야 개인 기준선 사용 */
    val minTripsForPersonal: Int = 5,
    val minScaleSmall: Float = 3f,
    val minScaleLarge: Float = 0.7f,
    val minScaleStd: Float = 0.5f,
    /**
     * 기록이 쌓이기 전에 쓰는 기본 기준선.
     * 합성 정상 주행(DemoTripGenerator.normalTrip, 시드 1~8)의 측정값에 맞춘 값이다:
     * 미세 반전 중앙값 24~28회/분, 큰 반전 0회/분(창에 따라 최대 3회), 표준편차 약 1.0°.
     * 실제 운전자 데이터로 검증된 값이 아니므로, 실차 데이터가 생기면 다시 맞춘다.
     */
    val defaultSmall: RobustStat = RobustStat(median = 25f, scale = 4f),
    val defaultLarge: RobustStat = RobustStat(median = 1f, scale = 1f),
    val defaultStd: RobustStat = RobustStat(median = 1f, scale = 0.5f),
)

/** 기준선 저장소. 안드로이드 쪽에서 파일/DB로 구현한다(core는 저장 방법을 모른다). */
interface BaselineStore {
    fun load(): List<TripSummary>
    fun save(trips: List<TripSummary>)
}

class InMemoryBaselineStore(initial: List<TripSummary> = emptyList()) : BaselineStore {
    private var trips: List<TripSummary> = initial
    override fun load(): List<TripSummary> = trips
    override fun save(trips: List<TripSummary>) { this.trips = trips }
}

object RobustStats {
    fun median(xs: List<Float>): Float {
        require(xs.isNotEmpty()) { "empty" }
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
    }

    fun mad(xs: List<Float>, median: Float = median(xs)): Float = median(xs.map { abs(it - median) })

    fun of(xs: List<Float>, minScale: Float): RobustStat {
        val med = median(xs)
        return RobustStat(med, max(MAD_TO_SD * mad(xs, med), minScale))
    }

    /** 정규분포에서 MAD를 표준편차로 바꾸는 계수 */
    const val MAD_TO_SD = 1.4826f
}

/**
 * 개인 기준선 관리. 최근 maxTrips회의 주행 요약으로 중앙값/MAD를 계산한다.
 * 피로 경고가 뜬 주행은 "평소"가 아니므로 넣지 않는다.
 */
class BaselineTracker(
    private val store: BaselineStore,
    private val params: BaselineParams = BaselineParams(),
) {
    private var trips: List<TripSummary> = store.load().takeLast(params.maxTrips)

    val tripCount: Int get() = trips.size

    fun current(): SteeringBaseline {
        if (trips.size < params.minTripsForPersonal) {
            return SteeringBaseline(params.defaultSmall, params.defaultLarge, params.defaultStd, trips.size, isCalibrating = true)
        }
        return SteeringBaseline(
            small = RobustStats.of(trips.map { it.reversalsSmallPerMin }, params.minScaleSmall),
            large = RobustStats.of(trips.map { it.reversalsLargePerMin }, params.minScaleLarge),
            std = RobustStats.of(trips.map { it.stdDeg }, params.minScaleStd),
            tripCount = trips.size,
            isCalibrating = false,
        )
    }

    /** @return 기준선에 반영했으면 true */
    fun record(summary: TripSummary, hadFatigueWarning: Boolean): Boolean {
        if (hadFatigueWarning) return false
        trips = (trips + summary).takeLast(params.maxTrips)
        store.save(trips)
        return true
    }
}
