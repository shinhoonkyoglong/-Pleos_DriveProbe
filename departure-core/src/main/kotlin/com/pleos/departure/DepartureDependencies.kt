package com.pleos.departure

import com.pleos.departure.port.DriverConditionRepository
import com.pleos.departure.port.NaviRepository
import com.pleos.departure.port.SttRepository
import com.pleos.departure.port.TtsRepository
import com.pleos.departure.port.VehicleRepository
import java.time.Clock

/**
 * 2번 '출발 전' 기능이 바깥에서 받아야 하는 모든 것(Repository 구현 + 시계 + 설정)을 담는 진입점.
 *
 * - 백엔드 관점으로 보면 "생성자 주입으로 넘기는 의존성 묶음"이다. DI 프레임워크(Hilt 등)는 쓰지 않는다.
 * - 구현은 :departure-fake(시연/테스트용) 또는 1번 공통 Repository 어댑터가 제공하고,
 *   :app 의 DepartureWiring.kt 한 곳에서만 골라 조립한다.
 */
interface DepartureDependencies {
    val vehicleRepository: VehicleRepository
    val naviRepository: NaviRepository
    val driverConditionRepository: DriverConditionRepository

    /** Pleos TTS 준비 중 — 사용 안 함. 1번 계약 정렬용. 유스케이스는 쓰지 않는다. */
    val ttsRepository: TtsRepository

    /** Pleos STT 준비 중 — 사용 안 함. 1번 계약 정렬용. */
    val sttRepository: SttRepository

    /**
     * 현재 시각·일몰 계산용 시계. 우리 전용 포트를 따로 만들지 않고 JDK 표준 `java.time.Clock`을 그대로 받는다
     * (테스트·시연에서 Clock.fixed 또는 Fake 시계로 바꿀 수 있음). 시간대는 DepartureConfig.timeZone 을 쓴다.
     */
    val clock: Clock

    val config: DepartureConfig
}
