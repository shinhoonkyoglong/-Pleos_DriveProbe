package com.pleos.departure

import com.pleos.departure.model.DrivingRestriction
import com.pleos.departure.session.DepartureSession
import com.pleos.departure.usecase.ConditionRouteUseCase
import com.pleos.departure.usecase.DepartureCheckUseCase
import com.pleos.departure.usecase.DepartureDeadlineUseCase
import com.pleos.departure.usecase.RadiusCheckUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import java.time.Clock

/**
 * 2번 '출발 전' 기능의 외부 진입점. 표시 계층(:departure-ui 또는 1번 쪽 화면)은 이 객체만 받아서 동작한다.
 *
 * 진입점(작업 #12b부터 core 만으로 동작 — 화면 기술과 무관):
 * - [createSession] → [DepartureSession]: 진입·복귀 확인, 주기 갱신, 경로 교체 재확인, 실패 처리, 세션 기억(ETA·마지막 조건)을
 *   돌리고 `StateFlow<DepartureSessionState>`(core 모델, 문구·색·아이콘 없음)로 알린다.
 *   명령: onVisible / onHidden / refresh("다시 확인") / confirmTollAvoidanceTurnedOn("켰어요") / resetSession
 * - [departureCheck] 등 유스케이스: 한 번 계산만 필요할 때 직접 호출
 *
 * :departure-ui 는 얇은 표시 계층이다: `DepartureScreenModel`(= 세션 + 한국어 표시 변환) / `rememberDepartureScreenModel(feature)`.
 * 유스케이스·세션은 생성자 주입으로 만든다(DI 프레임워크 없음). 화면 문구(한국어)는 core 에 두지 않는다(표시 계층 몫).
 */
class DepartureFeature(
    private val dependencies: DepartureDependencies,
) {
    /** 출발 마감 계산(작업 #4b). */
    val departureDeadline: DepartureDeadlineUseCase = DepartureDeadlineUseCase(
        naviRepository = dependencies.naviRepository,
        clock = dependencies.clock,
        config = dependencies.config,
    )

    /** 내비 목적지 허용 반경 확인(작업 #5). */
    val radiusCheck: RadiusCheckUseCase = RadiusCheckUseCase(
        naviRepository = dependencies.naviRepository,
        config = dependencies.config,
    )

    /** 조건 맞춤 경로 확인(작업 #5b). 내비 경로 상태를 읽어 충족 여부·사용자 조작 안내 키를 돌려준다. 부작용 없음. */
    val conditionRoute: ConditionRouteUseCase = ConditionRouteUseCase(
        naviRepository = dependencies.naviRepository,
    )

    /** 화면 한 번 갱신분(운전자 조건 D-9 처리 + 목적지 1회 조회 + 마감·반경·경로)을 모아 계산한다(작업 #7). */
    val departureCheck: DepartureCheckUseCase = DepartureCheckUseCase(
        naviRepository = dependencies.naviRepository,
        driverConditionRepository = dependencies.driverConditionRepository,
        departureDeadline = departureDeadline,
        radiusCheck = radiusCheck,
        conditionRoute = conditionRoute,
        clock = dependencies.clock,
    )

    /** 동작 수치(화면의 재확인 간격·로딩 표시 기준 등). */
    val config: DepartureConfig get() = dependencies.config

    /** 현재 시각(Fake 시나리오 시계 포함). 화면은 시각 표시에 이 시계와 config.timeZone 만 쓴다. */
    val clock: Clock get() = dependencies.clock

    /**
     * 확인 세션을 새로 만든다. 화면(또는 그에 해당하는 수명) 하나에 하나씩 만든다.
     * @param scope 세션 수명 범위. 이 범위가 끝나면 세션의 모든 작업이 멈춘다.
     */
    fun createSession(scope: CoroutineScope): DepartureSession = DepartureSession(this, scope)

    /** 주행 규제 상태. 화면은 RESTRICTED 이면(기본 설정에서는 UNKNOWN 도) 조작 요소를 숨긴다. */
    fun observeDrivingRestriction(): Flow<DrivingRestriction> = dependencies.vehicleRepository.observeDrivingRestriction()

    companion object {
        /** 2번 계약 버전. CommonRepositoryContract / 도메인 모델이 바뀔 때 올린다. */
        // 0.7.0(#11): 시그니처 변경 없음. 어댑터 의무 변경 — 주차 중이면 UNRESTRICTED 를 명시(UNKNOWN 은 기본 제한),
        //   경로 교체 중·안내 없음의 0 값 목적지는 둘 다 NotSet.
        // 0.8.0(#12b): 포트 시그니처 변경 없음. 세션 동작(재확인·주기 갱신·실패 처리·세션 기억)을 ui 에서 core 로 옮겨 공개 API 추가 —
        //   DepartureFeature.createSession, session 패키지(DepartureSession, DepartureSessionState, CheckOutcome, RecheckProgress).
        const val VERSION: String = "0.8.0"
    }
}
