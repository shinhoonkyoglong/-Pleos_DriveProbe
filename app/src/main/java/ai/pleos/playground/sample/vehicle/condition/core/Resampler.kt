package ai.pleos.playground.sample.vehicle.condition.core

/**
 * 불규칙한 콜백을 고정 간격(기본 100ms = 10Hz) 격자로 바꾼다.
 *
 * - 격자 시각은 periodMs의 배수(0, 100, 200, ...)로 고정한다. 여러 신호를 같은 격자에 맞추기 위해서다.
 * - 각 격자 시각의 값은 "그 시각 이하에서 마지막으로 받은 값"이다(zero-order hold, 값 사이는 마지막 값 유지).
 *   실측(CLAUDE.md 5-1): 조향 콜백은 중간값이 버려지고 최신값만 오므로 선형 보간보다 이 방식이 실제와 맞다.
 * - 첫 입력 전에는 아무것도 내보내지 않는다.
 * - 마지막 입력 후 staleAfterMs가 지나면 isStale=true로 내보낸다(null이면 끊김 판정 안 함 — 기어·방향지시등처럼 변경 시에만 오는 신호).
 * - 시간이 거꾸로 가는 입력은 버린다.
 *
 * 사용법 (실시간): 콜백마다 [push], 주기적으로(예: 100ms 타이머) [advanceTo]를 부른다.
 * 둘 다 "이번에 새로 확정된 격자 값"만 돌려주므로 결과를 이어 붙이면 중복·누락 없는 10Hz 시계열이 된다.
 *
 * 스레드 안전하지 않다. 한 스레드(또는 하나의 코루틴)에서만 부른다.
 */
class Resampler<T>(
    val periodMs: Long = DEFAULT_PERIOD_MS,
    val staleAfterMs: Long? = null,
) {
    init {
        require(periodMs > 0) { "periodMs must be > 0" }
        require(staleAfterMs == null || staleAfterMs > 0) { "staleAfterMs must be > 0" }
    }

    private var last: TimedValue<T>? = null

    /** 다음에 내보낼 격자 시각. 첫 입력 전에는 null. */
    private var nextGridMs: Long? = null

    /** 버린 입력 수(시간 역행). 디버깅용 */
    var droppedCount: Int = 0
        private set

    /**
     * 새 입력을 넣는다. 이 입력 시각 "미만"의 격자 값들이 확정되어 반환된다(이전 값으로 채움).
     * 입력 시각과 같은 격자 시각은 새 값으로 다음 호출 때 확정된다.
     */
    fun push(sample: TimedValue<T>): List<ResampledValue<T>> {
        val prev = last
        if (prev != null && sample.timeMs < prev.timeMs) {
            droppedCount++
            return emptyList()
        }
        val out = if (prev == null) {
            nextGridMs = ceilToGrid(sample.timeMs)
            emptyList()
        } else {
            emitUntil(exclusiveEndMs = sample.timeMs)
        }
        last = sample
        return out
    }

    /** 현재 시각까지(포함) 격자 값을 확정해 반환한다. 입력이 한동안 없어도 시계열이 이어지게 한다. */
    fun advanceTo(nowMs: Long): List<ResampledValue<T>> {
        if (last == null) return emptyList()
        return emitUntil(exclusiveEndMs = nowMs + 1)
    }

    /** 마지막으로 받은 입력 (없으면 null) */
    fun lastSample(): TimedValue<T>? = last

    fun reset() {
        last = null
        nextGridMs = null
        droppedCount = 0
    }

    private fun emitUntil(exclusiveEndMs: Long): List<ResampledValue<T>> {
        val cur = last ?: return emptyList()
        var t = nextGridMs ?: return emptyList()
        if (t >= exclusiveEndMs) return emptyList()
        val out = ArrayList<ResampledValue<T>>(((exclusiveEndMs - t) / periodMs + 1).toInt())
        while (t < exclusiveEndMs) {
            val stale = staleAfterMs != null && (t - cur.timeMs) > staleAfterMs
            out += ResampledValue(t, cur.value, stale)
            t += periodMs
        }
        nextGridMs = t
        return out
    }

    private fun ceilToGrid(timeMs: Long): Long {
        val r = Math.floorMod(timeMs, periodMs)
        return if (r == 0L) timeMs else timeMs - r + periodMs
    }

    companion object {
        const val DEFAULT_PERIOD_MS = 100L

        /**
         * 한꺼번에 받은 입력 목록을 untilMs까지(포함) 재샘플링한다. 재생 데이터·테스트용.
         * 입력은 시간순이어야 하며, 역행 입력은 버려진다.
         */
        fun <T> resampleAll(
            samples: List<TimedValue<T>>,
            untilMs: Long,
            periodMs: Long = DEFAULT_PERIOD_MS,
            staleAfterMs: Long? = null,
        ): List<ResampledValue<T>> {
            val r = Resampler<T>(periodMs, staleAfterMs)
            val out = ArrayList<ResampledValue<T>>()
            for (s in samples) out += r.push(s)
            out += r.advanceTo(untilMs)
            return out
        }
    }
}

/** 신호별 끊김 판정 기준 (실측 기반 기본값, CLAUDE.md 5-1·5-3) */
object StaleThresholds {
    /** 조향: 값이 그대로여도 약 3.1초마다 재전송됨 → 3.5초 넘게 없으면 끊김 */
    const val STEERING_MS = 3_500L

    /** 속도: 연속형, 약 6.4Hz(간격 최대 약 200ms) → 1초 넘게 없으면 끊김 */
    const val SPEED_MS = 1_000L
}
