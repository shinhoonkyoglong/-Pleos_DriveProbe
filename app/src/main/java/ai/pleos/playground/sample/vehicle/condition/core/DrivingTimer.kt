package ai.pleos.playground.sample.vehicle.condition.core

/**
 * 연속 주행 시간. 움직이는 동안(P가 아니고 속도 ≥ movingSpeedMps)만 늘어나고,
 * 멈춘 상태가 restResetMs(기본 10분) 이상 이어지면 휴식으로 보고 0으로 리셋한다.
 * 신호등 대기처럼 짧게 멈춘 시간은 더하지도 리셋하지도 않는다.
 */
class DrivingTimer(
    private val periodMs: Long = Resampler.DEFAULT_PERIOD_MS,
    private val restResetMs: Long = 10 * 60_000L,
    private val movingSpeedMps: Float = 1f,
) {
    private var driveMs = 0L
    private var restMs = 0L

    val minutes: Float get() = driveMs / 60_000f

    fun add(f: SignalFrame) {
        val speed = f.speedMps
        val moving = !f.speedStale && speed != null && speed >= movingSpeedMps && f.gear != Gear.PARK
        if (moving) {
            driveMs += periodMs
            restMs = 0
        } else {
            restMs += periodMs
            if (restMs >= restResetMs) driveMs = 0
        }
    }

    fun reset() {
        driveMs = 0
        restMs = 0
    }
}
