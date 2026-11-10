package ai.pleos.playground.sample.vehicle.condition.core

import kotlin.math.abs

/** 창이 무효인 이유(가장 많이 걸린 조건) */
enum class InvalidReason { SIGNAL_LOST, GEAR_NOT_DRIVE, SPEED_LOW, TURN_SIGNAL_ON, CURVE }

data class WindowParams(
    val periodMs: Long = Resampler.DEFAULT_PERIOD_MS,
    val windowMs: Long = 60_000L,
    val hopMs: Long = 10_000L,
    val minSpeedKph: Float = 60f,
    /** 방향지시등이 꺼진 뒤 이 시간이 지나야 "꺼짐"으로 인정(실측: 좌→우 전환 시 OFF가 28ms 끼어듦) */
    val turnOffHoldMs: Long = 1_000L,
    /** 창 안에서 유효 프레임 비율이 이 이상이어야 점수 계산 */
    val minValidFraction: Float = 0.8f,
    /** 평균 조향 절댓값이 이보다 크면 굽은 길로 보고 제외 */
    val maxMeanAbsSteeringDeg: Float = 15f,
    val features: FeatureParams = FeatureParams(),
) {
    val windowSamples: Int get() = (windowMs / periodMs).toInt()
    val hopSamples: Int get() = (hopMs / periodMs).toInt()
}

data class WindowResult(
    val endTimeMs: Long,
    val isValid: Boolean,
    val invalidReason: InvalidReason?,
    val validFraction: Float,
    val features: SteeringFeatures?,
)

/**
 * 프레임을 하나씩 받아 60초 창을 유지하고, 창이 찬 뒤 10초마다 [WindowResult]를 낸다.
 * 프레임마다 유효 조건(신호 정상, D, 60km/h 이상, 방향지시등 꺼진 지 1초 이상)을 판정해 둔다.
 */
class WindowAnalyzer(private val params: WindowParams = WindowParams()) {

    private class Entry(val frame: SignalFrame, val invalid: InvalidReason?)

    private val ring = ArrayDeque<Entry>()
    private var sinceLast = 0
    private var lastTurnOnMs: Long? = null

    /** 프레임 하나를 넣는다. 창 결과가 나올 차례면 결과를, 아니면 null을 돌려준다. */
    fun add(frame: SignalFrame): WindowResult? {
        val ts = frame.turnSignal
        if (ts != null && ts != TurnSignal.OFF) lastTurnOnMs = frame.timeMs

        ring.addLast(Entry(frame, check(frame)))
        while (ring.size > params.windowSamples) ring.removeFirst()
        sinceLast++

        if (ring.size < params.windowSamples || sinceLast < params.hopSamples) return null
        sinceLast = 0
        return analyze()
    }

    fun reset() {
        ring.clear()
        sinceLast = 0
        lastTurnOnMs = null
    }

    private fun check(f: SignalFrame): InvalidReason? {
        val speed = f.speedMps
        if (f.steeringDeg == null || f.steeringStale || speed == null || f.speedStale) return InvalidReason.SIGNAL_LOST
        if (f.gear != Gear.DRIVE) return InvalidReason.GEAR_NOT_DRIVE
        if (speed * 3.6f < params.minSpeedKph - SPEED_TOLERANCE_KPH) return InvalidReason.SPEED_LOW
        val ts = f.turnSignal
        if (ts != null && ts != TurnSignal.OFF) return InvalidReason.TURN_SIGNAL_ON
        val on = lastTurnOnMs
        if (on != null && f.timeMs - on < params.turnOffHoldMs) return InvalidReason.TURN_SIGNAL_ON
        return null
    }

    private fun analyze(): WindowResult {
        val endMs = ring.last().frame.timeMs
        val validCount = ring.count { it.invalid == null }
        val fraction = validCount.toFloat() / ring.size

        if (fraction < params.minValidFraction) {
            val reason = ring.mapNotNull { it.invalid }
                .groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key ?: InvalidReason.SIGNAL_LOST
            return WindowResult(endMs, false, reason, fraction, null)
        }

        // 연속된 유효 구간으로 나누기
        val segments = ArrayList<FloatArray>()
        val cur = ArrayList<Float>()
        for (e in ring) {
            if (e.invalid == null) {
                cur += e.frame.steeringDeg!!
            } else if (cur.isNotEmpty()) {
                segments += cur.toFloatArray(); cur.clear()
            }
        }
        if (cur.isNotEmpty()) segments += cur.toFloatArray()

        val meanAbs = segments.sumOf { seg -> seg.sumOf { abs(it).toDouble() } } / validCount
        if (meanAbs > params.maxMeanAbsSteeringDeg) {
            return WindowResult(endMs, false, InvalidReason.CURVE, fraction, null)
        }

        val features = SteeringFeatureExtractor.extract(segments, params.periodMs, params.features)
        return WindowResult(endMs, true, null, fraction, features)
    }

    private companion object {
        /** 콘솔 60km/h가 앱에 16.666668 m/s로 오는 등 부동소수 오차 허용 */
        const val SPEED_TOLERANCE_KPH = 0.05f
    }
}
