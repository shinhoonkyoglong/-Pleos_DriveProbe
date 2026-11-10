package ai.pleos.playground.sample.vehicle.condition.core

/**
 * 신호 4종을 각각 [Resampler]로 100ms 격자에 맞춘 뒤, 같은 격자 시각끼리 묶어 [SignalFrame]으로 내보낸다.
 *
 * - 조향이 기준 신호다. 조향을 한 번도 못 받았으면 프레임을 만들지 않는다.
 * - 속도를 아직 못 받았으면 speedMps=null, speedStale=true.
 * - 입력(on*)은 받은 순서대로 넣고, 주기적으로 [advanceTo]를 부른다.
 */
class FrameBuilder(private val periodMs: Long = Resampler.DEFAULT_PERIOD_MS) {
    private val steering = Resampler<Float>(periodMs, StaleThresholds.STEERING_MS)
    private val speed = Resampler<Float>(periodMs, StaleThresholds.SPEED_MS)
    private val gear = Resampler<Gear>(periodMs, null)
    private val turn = Resampler<TurnSignal>(periodMs, null)

    private val qSteer = ArrayDeque<ResampledValue<Float>>()
    private val qSpeed = ArrayDeque<ResampledValue<Float>>()
    private val qGear = ArrayDeque<ResampledValue<Gear>>()
    private val qTurn = ArrayDeque<ResampledValue<TurnSignal>>()

    fun onSteering(timeMs: Long, deg: Float) { qSteer.addAll(steering.push(TimedValue(timeMs, deg))) }
    fun onSpeed(timeMs: Long, mps: Float) { qSpeed.addAll(speed.push(TimedValue(timeMs, mps))) }
    fun onGear(timeMs: Long, value: Gear) { qGear.addAll(gear.push(TimedValue(timeMs, value))) }
    fun onTurnSignal(timeMs: Long, value: TurnSignal) { qTurn.addAll(turn.push(TimedValue(timeMs, value))) }

    /** nowMs까지(포함) 확정된 프레임을 시간순으로 반환한다. */
    fun advanceTo(nowMs: Long): List<SignalFrame> {
        qSteer.addAll(steering.advanceTo(nowMs))
        qSpeed.addAll(speed.advanceTo(nowMs))
        qGear.addAll(gear.advanceTo(nowMs))
        qTurn.addAll(turn.advanceTo(nowMs))

        val out = ArrayList<SignalFrame>(qSteer.size)
        while (qSteer.isNotEmpty() && qSteer.first().timeMs <= nowMs) {
            val s = qSteer.removeFirst()
            val t = s.timeMs
            val sp = takeAt(qSpeed, t)
            val g = takeAt(qGear, t)
            val tu = takeAt(qTurn, t)
            out += SignalFrame(
                timeMs = t,
                steeringDeg = s.value,
                steeringStale = s.isStale,
                speedMps = sp?.value,
                speedStale = sp?.isStale ?: true,
                gear = g?.value,
                turnSignal = tu?.value,
            )
        }
        // 조향이 아직 없을 때 다른 큐가 무한히 쌓이지 않게 정리
        if (qSteer.isEmpty()) {
            val keepFrom = nowMs - KEEP_WITHOUT_STEERING_MS
            dropBefore(qSpeed, keepFrom); dropBefore(qGear, keepFrom); dropBefore(qTurn, keepFrom)
        }
        return out
    }

    private fun <T> takeAt(q: ArrayDeque<ResampledValue<T>>, t: Long): ResampledValue<T>? {
        while (q.isNotEmpty() && q.first().timeMs < t) q.removeFirst()
        return if (q.isNotEmpty() && q.first().timeMs == t) q.removeFirst() else null
    }

    private fun <T> dropBefore(q: ArrayDeque<ResampledValue<T>>, t: Long) {
        while (q.isNotEmpty() && q.first().timeMs < t) q.removeFirst()
    }

    private companion object {
        const val KEEP_WITHOUT_STEERING_MS = 2_000L
    }
}
