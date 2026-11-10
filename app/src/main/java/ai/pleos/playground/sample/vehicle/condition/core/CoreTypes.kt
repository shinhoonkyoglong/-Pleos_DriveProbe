package ai.pleos.playground.sample.vehicle.condition.core

/**
 * core가 쓰는 기어 값. SDK/AAOS 숫자 코드(N=1, R=2, P=4, D=8)는 signals에서 이 enum으로 바꿔 넣는다.
 * core가 AAOS 상수를 몰라도 되게 하기 위함이다.
 */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 방향지시등. SDK 값 LEFT/RIGHT/OFF와 같다(콘솔의 Blink/Flash 구분은 앱에 안 옴). */
enum class TurnSignal { OFF, LEFT, RIGHT }

/**
 * 100ms 격자 한 칸에 모든 신호를 모은 것. [FrameBuilder]가 만든다.
 * 값이 null이면 그 신호를 아직 한 번도 받지 못한 것이다.
 */
data class SignalFrame(
    val timeMs: Long,
    val steeringDeg: Float?,
    val steeringStale: Boolean,
    val speedMps: Float?,
    val speedStale: Boolean,
    val gear: Gear?,
    val turnSignal: TurnSignal?,
)
