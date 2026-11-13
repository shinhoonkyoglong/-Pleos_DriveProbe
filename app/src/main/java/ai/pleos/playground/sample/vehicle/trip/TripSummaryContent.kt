package ai.pleos.playground.sample.vehicle.trip

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.condition.core.ReasonCode
import ai.pleos.playground.sample.vehicle.trip.data.TripRecordEntity
import ai.pleos.playground.sample.vehicle.trip.source.ComplianceStatus
import ai.pleos.playground.sample.vehicle.trip.source.DataSource
import ai.pleos.playground.sample.vehicle.trip.source.Violation
import ai.pleos.playground.sample.vehicle.trip.source.judgedConditionsOf
import kotlin.math.roundToInt

/** 항목 상태. 화면은 색과 함께 글자(좋음/확인/참고)로도 구분한다 */
enum class SummaryTone { GOOD, WARN, INFO }

/** 요약 항목. 어느 파트 값인지 [key]로 구분한다 */
enum class SummaryKey { DURATION, FATIGUE, REST, PRE_DRIVE, COMPLIANCE }

/**
 * @param value   크게 보여줄 값(예: "최고 72점")
 * @param detail  값 아래 설명 한두 문장(없으면 null)
 * @param isMock  다른 파트 연동 전 임시값이면 true → 화면에 "임시" 표시
 */
data class SummaryItem(
    val key: SummaryKey,
    val title: String,
    val value: String,
    val detail: String?,
    val tone: SummaryTone,
    val isMock: Boolean,
)

/**
 * 주행 종료 요약 — 운전자에게 알려야 할 내용을 추린 것.
 *
 * [headline]은 이번 주행에서 가장 중요한 한 문장(우선순위: 피로 → 운전 조건 → 출발 전 반응 → 판정 없음 → 정상),
 * [items]는 1~4번 파트 값 중 점수·횟수로 나타낼 수 있는 항목이다.
 * 고령 운전자 대상이라 쉬운 말·짧은 문장으로 쓴다.
 */
data class TripSummaryContent(
    val tripId: String,
    val headline: String,
    val headlineTone: SummaryTone,
    val items: List<SummaryItem>,
    val endedAt: Long,
    val isDemo: Boolean,
) {
    val hasMock: Boolean get() = items.any { it.isMock }

    companion object {

        fun from(r: TripRecordEntity): TripSummaryContent {
            val level = enumOrNull<FatigueLevel>(r.maxLevel) ?: FatigueLevel.NORMAL
            val compliance = enumOrNull<ComplianceStatus>(r.compliance) ?: ComplianceStatus.UNKNOWN
            val preDrive = r.preDriveVerdict?.let { enumOrNull<ReactionVerdict>(it) }
            val items = listOf(
                durationItem(r),
                fatigueItem(r, level),
                restItem(r),
                preDriveItem(r, preDrive),
                complianceItem(r, compliance),
            )
            val (headline, tone) = headline(r, level, compliance, preDrive)
            return TripSummaryContent(r.tripId, headline, tone, items, r.endedAt, r.isDemo)
        }

        // ---- 한 줄 요약 ----

        private fun headline(
            r: TripRecordEntity,
            level: FatigueLevel,
            compliance: ComplianceStatus,
            preDrive: ReactionVerdict?,
        ): Pair<String, SummaryTone> = when {
            level >= FatigueLevel.CAUTION || r.restAdviceCount > 0 ->
                "운전 중 피로 신호가 있었어요. 다음에는 중간에 꼭 쉬어 가세요." to SummaryTone.WARN
            compliance == ComplianceStatus.VIOLATED ->
                "지키지 못한 운전 조건이 있었어요. 다음 운전 전에 확인해 주세요." to SummaryTone.WARN
            preDrive == ReactionVerdict.SLOWER || preDrive == ReactionVerdict.MUCH_SLOWER ->
                "출발 전 반응이 평소보다 느렸어요. 피곤할 땐 쉬었다 운전하세요." to SummaryTone.WARN
            r.judgedMs == 0L ->
                "운전을 마치셨어요. 이번에는 피로를 판정할 구간이 없었어요." to SummaryTone.INFO
            else ->
                "오늘도 안전하게 운전하셨어요." to SummaryTone.GOOD
        }

        // ---- 항목 ----

        /** 운전 시간 (5번 주행 기록 + 3번 연속 주행 시간) */
        fun durationItem(r: TripRecordEntity): SummaryItem {
            val longDrive = ReasonCode.LONG_DRIVE.name in r.reasonCodes.split(',')
            val continuous = r.maxDrivingMinutes.roundToInt()
            val detail = when {
                longDrive -> "쉬지 않고 ${durationText(continuous * 60_000L)} 이어서 운전하셨어요. 오래 운전할 땐 중간에 쉬어 주세요."
                continuous >= 1 && continuous < (r.durationMs / 60_000) -> "쉬지 않고 가장 오래 운전한 시간은 ${durationText(continuous * 60_000L)}이에요."
                else -> null
            }
            return SummaryItem(
                SummaryKey.DURATION, "운전 시간", durationText(r.durationMs), detail,
                if (longDrive) SummaryTone.WARN else SummaryTone.INFO, isMock = false,
            )
        }

        /** 피로 점수 (3번 컨디션 엔진). 판정 구간이 없으면 "정상"이 아니라 "판정 없음" */
        fun fatigueItem(r: TripRecordEntity, level: FatigueLevel): SummaryItem {
            if (r.judgedMs == 0L) {
                return SummaryItem(
                    SummaryKey.FATIGUE, "피로 점수", "판정 없음",
                    "시속 60km 이상으로 곧게 달린 구간이 없어 피로를 판정하지 못했어요.",
                    SummaryTone.INFO, isMock = false,
                )
            }
            val levelText = when (level) {
                FatigueLevel.NORMAL -> "평소와 비슷한 컨디션이었어요."
                FatigueLevel.MILD -> "가벼운 피로 신호가 있었어요."
                FatigueLevel.CAUTION -> "피로 주의 단계까지 올라갔어요."
                FatigueLevel.DANGER -> "졸음 위험 단계까지 올라갔어요."
            }
            val avg = r.avgFatigueScore?.roundToInt()
            val detail = buildString {
                append(levelText)
                if (avg != null) append(" 평균 ${avg}점이에요(높을수록 피곤).")
                if (r.isCalibrating) append(" 아직 평소 운전 기록이 적어 참고용이에요.")
            }
            val tone = when (level) {
                FatigueLevel.NORMAL -> SummaryTone.GOOD
                FatigueLevel.MILD -> SummaryTone.INFO
                FatigueLevel.CAUTION, FatigueLevel.DANGER -> SummaryTone.WARN
            }
            return SummaryItem(SummaryKey.FATIGUE, "피로 점수", "최고 ${r.maxFatigueScore.roundToInt()}점", detail, tone, isMock = false)
        }

        /** 휴식 권고 (4번 DANGER 개입) */
        fun restItem(r: TripRecordEntity): SummaryItem {
            val mock = r.restAdviceSource == DataSource.MOCK.name
            return if (r.restAdviceCount <= 0) {
                SummaryItem(SummaryKey.REST, "휴식 권고", "없음", null, SummaryTone.GOOD, mock)
            } else {
                SummaryItem(
                    SummaryKey.REST, "휴식 권고", "${r.restAdviceCount}번",
                    "안내에 ${r.restRespondedCount}번 답하셨어요.", SummaryTone.WARN, mock,
                )
            }
        }

        /** 출발 전 반응 체크 (3번) */
        fun preDriveItem(r: TripRecordEntity, verdict: ReactionVerdict?): SummaryItem {
            val mock = r.preDriveSource == DataSource.MOCK.name
            val (value, detail, tone) = when (verdict) {
                null -> Triple("하지 않음", "출발 전에 반응 체크를 하면 컨디션을 더 정확히 볼 수 있어요.", SummaryTone.INFO)
                ReactionVerdict.NORMAL -> Triple("평소와 비슷", null, SummaryTone.GOOD)
                ReactionVerdict.SLOWER -> Triple("조금 느림", "평소보다 반응이 조금 느렸어요.", SummaryTone.WARN)
                ReactionVerdict.MUCH_SLOWER -> Triple("많이 느림", "평소보다 반응이 많이 느렸어요.", SummaryTone.WARN)
                ReactionVerdict.INCONCLUSIVE -> Triple("판단 보류", "반응 기록이 충분하지 않아 평소와 비교하지 않았어요.", SummaryTone.INFO)
            }
            return SummaryItem(SummaryKey.PRE_DRIVE, "출발 전 반응", value, detail, tone, mock)
        }

        /**
         * 운전 조건 준수 (2번). 판정한 조건만 말한다 — 판정하지 못한 조건(지금은 고속도로·반경)이 있으면
         * "모두 지킴"이라고 단정하지 않는다(2번 연동 규칙).
         */
        fun complianceItem(r: TripRecordEntity, status: ComplianceStatus): SummaryItem {
            val mock = r.complianceSource == DataSource.MOCK.name
            return when (status) {
                ComplianceStatus.COMPLIED -> {
                    val judged = judgedConditionsOf(r).sortedBy { it.ordinal }
                    val (value, detail) = when {
                        judged.size == Violation.entries.size -> "모두 지킴" to null
                        judged.isEmpty() -> "지킴" to null
                        else -> "지킴" to "${judged.joinToString(", ") { it.label() }} 조건을 지켰어요."
                    }
                    SummaryItem(SummaryKey.COMPLIANCE, "운전 조건", value, detail, SummaryTone.GOOD, mock)
                }
                ComplianceStatus.VIOLATED -> {
                    val names = r.violations.split(',').mapNotNull { enumOrNull<Violation>(it.trim()) }.map { it.label() }
                    val detail = if (names.isEmpty()) "지키지 못한 조건이 있었어요."
                    else "${names.joinToString(", ")} 조건을 지키지 못했어요."
                    SummaryItem(SummaryKey.COMPLIANCE, "운전 조건", "지키지 못함", detail, SummaryTone.WARN, mock)
                }
                ComplianceStatus.UNKNOWN ->
                    SummaryItem(SummaryKey.COMPLIANCE, "운전 조건", "확인 못 함", "운전 조건 확인 결과가 없어요.", SummaryTone.INFO, mock)
                ComplianceStatus.NO_CONDITION ->
                    SummaryItem(SummaryKey.COMPLIANCE, "운전 조건", "설정 안 함", "설정한 운전 조건이 없어요.", SummaryTone.INFO, mock)
            }
        }

        fun durationText(durationMs: Long): String {
            val totalMin = durationMs / 60_000
            val hours = totalMin / 60
            val minutes = totalMin % 60
            return when {
                totalMin < 1 -> "1분 미만"
                hours == 0L -> "${minutes}분"
                minutes == 0L -> "${hours}시간"
                else -> "${hours}시간 ${minutes}분"
            }
        }

        private fun Violation.label(): String = when (this) {
            Violation.NIGHT_DRIVING -> "야간 운전"
            Violation.HIGHWAY -> "고속도로"
            Violation.OUT_OF_RADIUS -> "운전 반경"
        }

        private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
            enumValues<T>().firstOrNull { it.name == name }
    }
}

/**
 * 지난 기록 목록의 한 줄.
 * @param headline 그 주행의 한 줄 요약(요약 화면 맨 위 문장과 같음)
 * @param tone     한 줄 요약의 상태(색·글자 표시용)
 */
data class TripHistoryItem(
    val tripId: String,
    val endedAt: Long,
    val duration: String,
    val fatigue: String,
    val headline: String,
    val tone: SummaryTone,
    val isDemo: Boolean,
) {
    companion object {
        fun from(r: TripRecordEntity): TripHistoryItem {
            val c = TripSummaryContent.from(r)
            return TripHistoryItem(
                tripId = r.tripId,
                endedAt = r.endedAt,
                duration = c.items.first { it.key == SummaryKey.DURATION }.value,
                fatigue = c.items.first { it.key == SummaryKey.FATIGUE }.value,
                headline = c.headline,
                tone = c.headlineTone,
                isDemo = r.isDemo,
            )
        }
    }
}
