package ai.pleos.playground.sample.vehicle.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 출발 전 고령 운전자 반응 체크 및 컨디션 기록 Entity
 */
@Entity(tableName = "reaction_scores")
data class ReactionScoreEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,                 // 주행 세션 고유 ID (UUID 등)
    val timestamp: Long = System.currentTimeMillis(), // 측정 시각
    val simpleReactionTimeMs: Long,        // 단순 반응 속도 (ms)
    val choiceReactionTimeMs: Long? = null,// 선택/인지 반응 속도 (ms)
    val reactionScore: Int,                // 환산 반응 점수 (0 ~ 100)
    val passed: Boolean,                   // 안전 출발 기준 통과 여부
    val notes: String? = null,             // 특이사항 및 인지 상태 코멘트
    val isSynced: Boolean = false          // 서버 동기화 완료 여부 (1번 포지션 동기화 API용)
)
