package ai.pleos.playground.sample.vehicle.condition.core

import kotlin.math.PI
import kotlin.math.sin

/** 테스트용 신호 생성 도우미 (10Hz 기준) */
object TestSignals {
    const val PERIOD = 100L

    /** 진폭 amp°, 주기 periodSec초 사인파 (정상 주행의 잦은 미세 수정 흉내) */
    fun sine(seconds: Int, amp: Float, periodSec: Float, offset: Float = 0f): FloatArray {
        val n = seconds * 10
        return FloatArray(n) { i -> offset + amp * sin(2 * PI * (i / 10.0) / periodSec).toFloat() }
    }

    fun flat(samples: Int, value: Float = 0f) = FloatArray(samples) { value }

    fun ramp(samples: Int, from: Float, to: Float) =
        FloatArray(samples) { i -> from + (to - from) * (i + 1) / samples }

    fun concat(vararg parts: FloatArray): FloatArray = parts.reduce { a, b -> a + b }

    /** 조향 배열을 유효한 고속 직진 프레임으로 감싼다 */
    fun frames(
        steering: FloatArray,
        startMs: Long = 0L,
        speedKph: Float = 80f,
        gear: Gear = Gear.DRIVE,
        turn: (Int) -> TurnSignal = { TurnSignal.OFF },
        steeringStale: (Int) -> Boolean = { false },
    ): List<SignalFrame> = steering.mapIndexed { i, s ->
        SignalFrame(
            timeMs = startMs + i * PERIOD,
            steeringDeg = s,
            steeringStale = steeringStale(i),
            speedMps = speedKph / 3.6f,
            speedStale = false,
            gear = gear,
            turnSignal = turn(i),
        )
    }

    fun features(small: Float, large: Float, jerks: Int = 0, std: Float = 2f) =
        SteeringFeatures(small, large, jerks, std, meanAbsDeg = 1f, validSeconds = 60f)
}
