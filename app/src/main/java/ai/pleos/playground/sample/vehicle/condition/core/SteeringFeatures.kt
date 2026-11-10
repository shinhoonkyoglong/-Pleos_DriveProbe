package ai.pleos.playground.sample.vehicle.condition.core

import kotlin.math.abs
import kotlin.math.sqrt

/** 60초 창 하나에서 뽑은 조향 특징 */
data class SteeringFeatures(
    /** 분당 반전 횟수(gap = 미세 기준, 기본 1°). 졸리면 작은 수정이 줄어드는 경향 */
    val reversalsSmallPerMin: Float,
    /** 분당 반전 횟수(gap = 큰 기준, 기본 5°). 졸리면 큰 수정이 늘어나는 경향 */
    val reversalsLargePerMin: Float,
    /** 무조향 후 급조향 횟수(이 창 안) */
    val lullJerkCount: Int,
    val stdDeg: Float,
    val meanAbsDeg: Float,
    /** 특징 계산에 쓴 유효 시간(초) */
    val validSeconds: Float,
)

data class FeatureParams(
    val smallGapDeg: Float = 1f,
    val largeGapDeg: Float = 5f,
    /** "거의 안 움직임"으로 볼 최소 시간 */
    val lullMs: Long = 3_000L,
    /** 그 시간 동안 조향 폭(최대-최소)이 이 값 이하면 무조향 */
    val lullRangeDeg: Float = 1f,
    /** 무조향 직후 이 시간 안에 */
    val jerkWithinMs: Long = 1_500L,
    /** 이만큼 이상 변하면 급조향 */
    val jerkDeltaDeg: Float = 8f,
)

object SteeringFeatureExtractor {
    private const val EPS = 1e-4f

    /**
     * 히스테리시스 방식 반전 횟수.
     * 직전 꼭짓점(ext)에서 반대 방향으로 gap 이상 움직이면 반전 1회. 처음 방향이 정해지는 것은 반전이 아니다.
     */
    fun countReversals(xs: FloatArray, gapDeg: Float): Int {
        if (xs.size < 2) return 0
        val gap = gapDeg - EPS
        var dir = 0
        var hi = xs[0]
        var lo = xs[0]
        var ext = xs[0]
        var count = 0
        for (i in 1 until xs.size) {
            val x = xs[i]
            when (dir) {
                0 -> {
                    if (x > hi) hi = x
                    if (x < lo) lo = x
                    if (x - lo >= gap) { dir = 1; ext = x } else if (hi - x >= gap) { dir = -1; ext = x }
                }
                1 -> if (x > ext) ext = x else if (ext - x >= gap) { count++; dir = -1; ext = x }
                else -> if (x < ext) ext = x else if (x - ext >= gap) { count++; dir = 1; ext = x }
            }
        }
        return count
    }

    /** 무조향(lullMs 동안 폭 ≤ lullRangeDeg) 직후 jerkWithinMs 안에 jerkDeltaDeg 이상 변화한 횟수 */
    fun countLullJerks(xs: FloatArray, periodMs: Long, p: FeatureParams = FeatureParams()): Int {
        val lullN = (p.lullMs / periodMs).toInt()
        val jerkN = (p.jerkWithinMs / periodMs).toInt()
        val delta = p.jerkDeltaDeg - EPS
        var count = 0
        var i = lullN
        while (i < xs.size) {
            if (range(xs, i - lullN, i) <= p.lullRangeDeg + EPS) {
                val base = xs[i]
                var hit = -1
                val end = minOf(i + jerkN, xs.size - 1)
                for (j in i + 1..end) {
                    if (abs(xs[j] - base) >= delta) { hit = j; break }
                }
                if (hit >= 0) {
                    count++
                    i = hit + lullN // 같은 급조향을 두 번 세지 않도록, 새 무조향 구간이 필요
                    continue
                }
            }
            i++
        }
        return count
    }

    /** 연속된 유효 구간들(segments)에서 특징을 계산한다. 구간끼리는 이어 붙이지 않는다(가짜 반전 방지). */
    fun extract(segments: List<FloatArray>, periodMs: Long, p: FeatureParams = FeatureParams()): SteeringFeatures {
        var n = 0
        var sum = 0.0
        var sumAbs = 0.0
        var small = 0
        var large = 0
        var jerks = 0
        for (seg in segments) {
            small += countReversals(seg, p.smallGapDeg)
            large += countReversals(seg, p.largeGapDeg)
            jerks += countLullJerks(seg, periodMs, p)
            for (x in seg) { sum += x; sumAbs += abs(x) }
            n += seg.size
        }
        if (n == 0) return SteeringFeatures(0f, 0f, 0, 0f, 0f, 0f)
        val mean = sum / n
        var sq = 0.0
        for (seg in segments) for (x in seg) sq += (x - mean) * (x - mean)
        val seconds = n * periodMs / 1000f
        val minutes = seconds / 60f
        return SteeringFeatures(
            reversalsSmallPerMin = small / minutes,
            reversalsLargePerMin = large / minutes,
            lullJerkCount = jerks,
            stdDeg = sqrt(sq / n).toFloat(),
            meanAbsDeg = (sumAbs / n).toFloat(),
            validSeconds = seconds,
        )
    }

    private fun range(xs: FloatArray, from: Int, toInclusive: Int): Float {
        var lo = xs[from]
        var hi = xs[from]
        for (k in from + 1..toInclusive) {
            val x = xs[k]
            if (x < lo) lo = x
            if (x > hi) hi = x
        }
        return hi - lo
    }
}
