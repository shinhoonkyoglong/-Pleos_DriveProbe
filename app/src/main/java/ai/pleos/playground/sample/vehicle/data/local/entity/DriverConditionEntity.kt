package ai.pleos.playground.sample.vehicle.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 운전자 조건(조건부 면허 조건) Entity — 운전자 한 명 기준이라 행은 항상 하나([SINGLE_ROW_ID])다.
 *
 * 필드는 2번 '출발 전' 모델 `com.pleos.departure.model.DriverCondition` 과
 * 서버 계약(2번 저장소 docs/pleos/contracts/driver-condition-api.md §4)을 그대로 따른다.
 * 모델 변환은 `departure/DriverConditionEntityMapper` 에 있다(이 Entity 는 2번 모델을 모른다).
 *
 * - 집합(Set) 필드는 enum 이름을 쉼표로 이은 문자열로 저장한다(오름차순, 중복 없음, 비어 있으면 ""). 계약 §3 "응답 배열은 enum 이름 오름차순"과 같은 순서.
 * - 행이 없음 = 조건 미설정. 행이 있고 모든 값이 비어 있음 = 조건 해제(계약 4.3 "해제 ≠ 미설정").
 * - 2026-11-10 DB 버전 2에서 추가(AppDatabase.MIGRATION_1_2).
 */
@Entity(tableName = "driver_conditions")
data class DriverConditionEntity(
    @PrimaryKey
    val id: Int = SINGLE_ROW_ID,           // 단일 행 고정 키
    val timeConstraints: String,           // TimeConstraint 이름 목록 (예: "NO_NIGHT_DRIVING")
    val routeConstraints: String,          // RouteConstraint 이름 목록 (예: "AVOID_HIGHWAY,PREFER_TOLL_FREE")
    val radiusCenterLatitude: Double?,     // 허용 반경 중심 위도 (WGS84, 경도와 함께 null 이거나 함께 값)
    val radiusCenterLongitude: Double?,    // 허용 반경 중심 경도
    val allowedRadiusMeters: Double?,      // 허용 반경 (m). null 이면 앱 기본 반경 사용
    val updatedAt: Long = System.currentTimeMillis(), // 마지막 저장 시각 (epoch ms)
    val isSynced: Boolean = false          // 서버 동기화 완료 여부 (1번 포지션 동기화 API용, PUT /v1/drivers/me/driver-condition)
) {
    companion object {
        /** 단일 행의 고정 기본 키. */
        const val SINGLE_ROW_ID = 1

        /** 집합 필드 구분자. */
        const val LIST_SEPARATOR = ","
    }
}
