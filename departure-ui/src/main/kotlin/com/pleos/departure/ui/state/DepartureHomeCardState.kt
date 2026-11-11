package com.pleos.departure.ui.state

/*
 * 홈 카드 표시 상태(작업 #24, 임시). 앱 홈 화면에 들어갈 '오늘 운전해도 되나요?' 요약 카드 1장.
 * 팀 UI 구성 제안(docs/pleos/ui-structure-proposal.md 5절): 각 파트는 홈 카드 1개 + 전체 화면 1개를 진입점으로 낸다.
 * 제안이 팀에서 합의되기 전이라 모양은 바뀔 수 있다.
 *
 * 새 판정을 하지 않는다. 전체 화면과 같은 [DepartureUiState]를 요약만 한다 — 두 화면이 서로 다른 말을 하지 않게.
 * Pleos 문구 기준(문단 최대 3줄, 메시지 120자)에 맞춰 판정 1줄 + 핵심 값 1줄 + 요약 최대 2줄로 자른다.
 */

/** 홈 카드 요약 한 줄(반경·경로·조건 알림 카드의 제목). 색 + 아이콘 + 문구를 함께 쓴다. */
data class HomeCardLine(val tone: Tone, val icon: StatusIcon, val text: String)

/**
 * 홈 카드 상태.
 * @property verdict 판정 문구(가장 크게). 예: "출발해도 좋아요"
 * @property headline 핵심 값. 예: "오후 4시 14분까지 출발". 없으면 null
 * @property lines 요약 최대 [HomeCardSummary.MAX_LINES]줄
 * @property openEnabled 눌러서 전체 화면을 열 수 있는가. 주행 중이면 false
 */
data class HomeCardState(
    val tone: Tone,
    val icon: StatusIcon,
    val verdict: String,
    val headline: String?,
    val lines: List<HomeCardLine>,
    val openEnabled: Boolean,
)

/** [DepartureUiState] → [HomeCardState]. 순수 함수. */
object HomeCardSummary {
    const val MAX_LINES = 2

    const val VERDICT_OK = "출발해도 좋아요"
    const val VERDICT_CAUTION = "출발 전에 확인할 것이 있어요"
    const val VERDICT_DANGER = "출발 전에 꼭 확인하세요"
    const val VERDICT_UNKNOWN = "확인하지 못한 항목이 있어요"
    const val VERDICT_CONDITION_NOT_SET = "운전 조건을 먼저 정해 주세요"
    const val VERDICT_NO_DESTINATION = "내비에 목적지를 정하면 확인해요"
    const val VERDICT_CHECKING = "확인 중이에요"
    const val VERDICT_NOT_CHECKED = "아직 확인하지 않았어요"

    fun from(ui: DepartureUiState): HomeCardState {
        val content = ui.content
            ?: return HomeCardState(
                tone = Tone.UNKNOWN,
                icon = StatusIcon.WAIT,
                verdict = if (ui.loading) VERDICT_CHECKING else VERDICT_NOT_CHECKED,
                headline = null,
                lines = emptyList(),
                openEnabled = ui.controlsEnabled,
            )

        if (!content.conditionSet) {
            return HomeCardState(
                tone = Tone.INFO,
                icon = StatusIcon.INFO,
                verdict = VERDICT_CONDITION_NOT_SET,
                headline = null,
                lines = emptyList(),
                openEnabled = ui.controlsEnabled,
            )
        }

        // 경로 교체 중일 수 있어 목적지를 다시 확인하는 중 → 판정하지 않는다
        if (content.rechecking) {
            return HomeCardState(Tone.UNKNOWN, StatusIcon.WAIT, VERDICT_CHECKING, null, emptyList(), ui.controlsEnabled)
        }

        // 출발 전 확인은 내비 목적지 기준(작업 #26). 목적지가 없으면 판정하지 않고 목적지를 정하도록 안내한다
        // — 목적지 없이 "출발해도 좋아요"로 보이면 해가 진 뒤에도 그렇게 보일 수 있다. 오늘 일몰(출발지 기준)은 참고로 보여 준다
        if (!content.destinationSet) {
            return HomeCardState(
                tone = Tone.INFO,
                icon = StatusIcon.INFO,
                verdict = VERDICT_NO_DESTINATION,
                headline = content.main.headline,
                lines = listOf(HomeCardLine(Tone.INFO, StatusIcon.INFO, "출발 마감·허용 반경·경로 조건을 확인해요")),
                openEnabled = ui.controlsEnabled,
            )
        }

        // 참고용(secondary) 카드는 판정에 넣지 않는다 — 전체 화면에서도 주 메시지와 경쟁하지 않게 작게 그리는 카드다
        val judged = listOfNotNull(
            content.conditionNotice,
            content.main,
            content.radius?.takeUnless { it.secondary },
            content.route?.card,
        )
        val worst = judged.maxBy { severity(it.tone) }.tone
        val (verdictTone, verdictIcon, verdict) = when (worst) {
            Tone.DANGER -> Triple(Tone.DANGER, StatusIcon.STOP, VERDICT_DANGER)
            Tone.UNKNOWN -> Triple(Tone.UNKNOWN, StatusIcon.QUESTION, VERDICT_UNKNOWN)
            Tone.CAUTION -> Triple(Tone.CAUTION, StatusIcon.WARNING, VERDICT_CAUTION)
            Tone.INFO, Tone.OK -> Triple(Tone.OK, StatusIcon.CHECK, VERDICT_OK)
        }

        val summary = listOfNotNull(content.conditionNotice, content.radius, content.route?.card)
            .map { HomeCardLine(it.tone, it.icon, it.title) }
            .take(MAX_LINES)

        return HomeCardState(
            tone = verdictTone,
            icon = verdictIcon,
            verdict = verdict,
            headline = content.main.headline ?: content.main.title,
            lines = summary,
            openEnabled = ui.controlsEnabled,
        )
    }

    /**
     * 판정 순위. 위험 > 확인 불가 > 주의 > 참고·문제 없음.
     * 확인 불가를 주의보다 위에 둔다 — 모르는 것을 '조금 주의' 정도로 보이게 하지 않기 위해서다(안전 쪽).
     */
    private fun severity(tone: Tone): Int = when (tone) {
        Tone.DANGER -> 4
        Tone.UNKNOWN -> 3
        Tone.CAUTION -> 2
        Tone.INFO -> 1
        Tone.OK -> 0
    }
}
