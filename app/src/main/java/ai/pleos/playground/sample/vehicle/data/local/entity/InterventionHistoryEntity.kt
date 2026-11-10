package ai.pleos.playground.sample.vehicle.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 주행 중 3단계 피로 개입 이력 Entity
 * 1단계: 공조/환기 제어
 * 2단계: 음성 질문 발화 및 운전자 답변 수집
 * 3단계: 졸음쉼터 경로 재안내
 */
@Entity(tableName = "intervention_histories")
data class InterventionHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,                 // 주행 세션 ID
    val timestamp: Long = System.currentTimeMillis(), // 개입 발생 시각
    val interventionLevel: Int,            // 개입 단계 (1, 2, 3)
    val fatigueScore: Float,               // 조향각/속도 기반 계산된 피로 점수 (0.0 ~ 100.0)
    val triggerReason: String,             // 개입 유발 사유 (예: "조향 정지 시간 초과", "차선 유지 불안정")
    val actionDetails: String,             // 실행된 차량/음성 제어 내역 (예: "공조 외기 순환 및 풍량 4단 On")
    val userResponse: String? = null,      // 음성 답변 또는 스티어링 입력 등 운전자 반응
    val isSynced: Boolean = false          // 서버 동기화 완료 여부
)
