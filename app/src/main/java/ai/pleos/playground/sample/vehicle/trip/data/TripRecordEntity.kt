package ai.pleos.playground.sample.vehicle.trip.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 주행 1회의 요약 기록 (5번 주행 후 파트 전용 테이블).
 *
 * 위치(GPS 좌표)는 저장하지 않는다. 서버에도 이 집계값만 보낸다.
 * `*Source` 칼럼이 MOCK인 값은 임시값이라 요약·가족 요약·운전 리포트에 "임시"로 표시된다.
 *
 * [주행 기본 — 5번]
 * @param tripId            주행 ID(UUID). 다른 파트 기록과 묶는 방식은 팀원이 정한 방식을 따른다(미정)
 * @param startedAt         주행 시작 시각(벽시계 ms, System.currentTimeMillis)
 * @param endedAt           주행 종료 시각(벽시계 ms)
 * @param durationMs        주행 시간(ms) = endedAt - startedAt
 *
 * [피로 — 3번 컨디션 엔진, 실제 값]
 * @param maxLevel          이번 주행의 최고 피로 단계(FatigueLevel 이름: NORMAL/MILD/CAUTION/DANGER)
 * @param mildEntries       MILD 단계로 올라간 횟수
 * @param cautionEntries    CAUTION 단계로 올라간 횟수
 * @param dangerEntries     DANGER 단계로 올라간 횟수
 * @param reasonCodes       주행 중 나온 이유 코드(ReasonCode 이름, 쉼표 구분). 주간 요약 집계용
 * @param observedMs        엔진 상태를 받은 시간(ms)
 * @param judgedMs          그중 피로를 판정한 시간(ms). 0이면 "판정 없음"(정상이 아님)
 * @param maxFatigueScore   최고 피로 점수(0~100, 4번 intervention_histories.fatigueScore와 같은 단위). 판정 없으면 0
 * @param avgFatigueScore   평균 피로 점수(0~100). 판정 없으면 null
 * @param maxDrivingMinutes 쉬지 않고 이어서 운전한 최대 시간(분)
 * @param isCalibrating     개인 기준선이 아직 없어 점수가 참고용이면 true
 *
 * [출발 전 반응 체크 — 3번]
 * @param preDriveVerdict   ReactionVerdict 이름(NORMAL/SLOWER/MUCH_SLOWER/INCONCLUSIVE). 안 했으면 null
 * @param preDriveSource    REAL = 엔진 값(반응 체크를 안 했으면 preDriveVerdict null), MOCK = 연동 전 임시값(예전 기록·시연)
 *
 * [휴식 권고 — 4번, DANGER 개입(쉼터 안내)]
 * @param restAdviceCount     휴식 권고 횟수
 * @param restRespondedCount  그중 운전자가 답한 횟수
 * @param restAdviceSource    MOCK = 4번 개입 이력 연동 전 임시값
 *
 * [운전 조건 — 2번]
 * @param compliance        ComplianceStatus 이름(COMPLIED/VIOLATED/UNKNOWN/NO_CONDITION)
 * @param violations        어긴 조건 코드(쉼표 구분, 없으면 빈 문자열). 예: NIGHT_DRIVING
 * @param complianceSource  REAL = 2번 판정(DepartureFeature.evaluateTripCompliance), MOCK = 연동 전 임시값
 * @param judgedConditions  실제로 판정한 조건 코드(쉼표 구분). 지금 2번은 야간만 판정한다 → "NIGHT_DRIVING".
 *                          빈 문자열 = 판정 없음 또는 이 칸이 생기기 전 기록(source/judgedConditionsOf 참고)
 *
 * @param driverId          운전자 구분(미정, 지금은 null)
 * @param isDemo            시연 재생(DEMO 모드)·시연용 가상 기록이면 true. 가족 요약·리포트는 시연 기록과 실제 기록을 섞지 않는다
 * @param isSynced          서버 동기화 완료 여부
 */
@Entity(tableName = "trip_records")
data class TripRecordEntity(
    @PrimaryKey
    val tripId: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val maxLevel: String,
    val mildEntries: Int,
    val cautionEntries: Int,
    val dangerEntries: Int,
    val reasonCodes: String,
    val observedMs: Long = 0,
    val judgedMs: Long = 0,
    val maxFatigueScore: Float = 0f,
    val avgFatigueScore: Float? = null,
    val maxDrivingMinutes: Float = 0f,
    val isCalibrating: Boolean = true,
    val preDriveVerdict: String? = null,
    val preDriveSource: String = "MOCK",
    val restAdviceCount: Int,
    val restRespondedCount: Int = 0,
    val restAdviceSource: String,
    val compliance: String,
    val violations: String,
    val complianceSource: String,
    @ColumnInfo(defaultValue = "")
    val judgedConditions: String = "",
    val driverId: String? = null,
    val isDemo: Boolean,
    val isSynced: Boolean = false,
)
