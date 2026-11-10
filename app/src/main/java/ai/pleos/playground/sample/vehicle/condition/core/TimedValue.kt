package ai.pleos.playground.sample.vehicle.condition.core

/**
 * 시각이 붙은 값 하나. 신호 수집(signals)이 SDK 콜백을 받을 때마다 하나씩 만든다.
 *
 * @param timeMs 단조 증가 시계 기준 밀리초 (안드로이드에서는 SystemClock.elapsedRealtime(), 재생 모드에서는 데이터의 t)
 * @param value  신호 값 (조향각 °, 속도 m/s, 기어 코드 등)
 */
data class TimedValue<out T>(
    val timeMs: Long,
    val value: T,
)

/**
 * 재샘플링된 격자 위의 값 하나.
 *
 * @param timeMs  격자 시각 (periodMs의 배수)
 * @param value   그 시각까지 마지막으로 받은 값 (zero-order hold)
 * @param isStale 마지막 입력 이후 staleAfterMs가 지났으면 true → 신호 끊김. 이 값으로 점수를 계산하면 안 된다
 */
data class ResampledValue<out T>(
    val timeMs: Long,
    val value: T,
    val isStale: Boolean,
)
