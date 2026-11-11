package com.pleos.departure.ui.state

import com.pleos.departure.model.AppliedEta
import com.pleos.departure.model.ConditionSource
import com.pleos.departure.model.DeadlineStage
import com.pleos.departure.model.DepartureDeadlineResult
import com.pleos.departure.model.DepartureSnapshot
import com.pleos.departure.model.EstimateSource
import com.pleos.departure.model.OriginSource
import com.pleos.departure.model.OriginSunset
import com.pleos.departure.model.PointRadiusCheck
import com.pleos.departure.model.RadiusCheckResult
import com.pleos.departure.model.ResolvedCondition
import com.pleos.departure.model.RouteConditionCheck
import com.pleos.departure.model.RouteNote
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.RouteUserAction
import com.pleos.departure.model.SunsetPlace
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.session.CheckOutcome
import com.pleos.departure.session.DepartureSessionState
import java.time.ZoneId

/**
 * 결과에 덧붙이는 표시 조건. [DepartureDisplayMapper.uiState]가 core 세션 상태(RecheckProgress·controlsEnabled·tollToggleNotYetApplied)로 만든다.
 * @property rechecking 경로 교체 중일 수 있어 다시 확인하는 중(DepartureConfig.routeChangeRecheckInterval). 이때 "목적지 없음"·"안내 중 아님"을 확정하지 않는다.
 * @property recheckAttempt 몇 번째 재확인인가(1부터). 표시용.
 * @property recheckMaxAttempts DepartureConfig.routeChangeRecheckMaxAttempts.
 * @property controlsEnabled 주행 중이 아니어서 조작 안내·버튼을 보여도 되는가.
 * @property tollToggleNotYetApplied 사용자가 "켰어요"를 눌렀는데 다시 확인해도 아직 유료도로 우회가 적용되지 않았다.
 */
data class DisplayContext(
    val rechecking: Boolean = false,
    val recheckAttempt: Int = 0,
    val recheckMaxAttempts: Int = 0,
    val controlsEnabled: Boolean = true,
    val tollToggleNotYetApplied: Boolean = false,
)

/**
 * core 결과([DepartureSnapshot]) → 화면 표시([DepartureContent]) 변환. 한국어 문구는 모두 여기서 만든다.
 *
 * 규칙(리뷰 task-04b #7, task-05 #7·#7 추가, 감독관 결정 D-9):
 * - 모든 결과 타입과 [DeadlineStage]를 `when`으로 빠짐없이 처리한다(`else` 없음). 새 결과가 생기면 컴파일 오류로 드러난다.
 * - 확인 불가는 [Tone.UNKNOWN] + 물음표 아이콘 + "확인 불가" 문구. 안전([Tone.OK])처럼 보이지 않게 한다.
 * - 마감 지남·야간(PASSED, NIGHT_NOW)은 음수 시간이나 출발을 부추기는 문구 없이 "출발을 자제하세요"만. 경로 조작 안내도 숨긴다.
 * - ETA 가 SDK 실측이 아니면 "예상치"를 굵게 표시한다.
 * - 경로 확인의 참고 안내(notes)는 숨기지 않는다.
 *
 * @param zone 시각 표시 시간대(DepartureConfig.timeZone).
 */
class DepartureDisplayMapper(private val zone: ZoneId) {

    /**
     * core 세션 상태 → 화면 전체 상태(작업 #12b). 세션 동작은 core([com.pleos.departure.session.DepartureSession])가 하고,
     * 여기서는 문구·톤·아이콘만 입힌다. 같은 세션 상태면 늘 같은 화면 상태가 나온다(순수 함수).
     */
    fun uiState(session: DepartureSessionState): DepartureUiState {
        val controls = session.controlsEnabled
        val content = when (val outcome = session.outcome) {
            null -> null
            CheckOutcome.Failed -> checkFailed(DisplayContext(controlsEnabled = controls))
            is CheckOutcome.Checked -> map(
                outcome.snapshot,
                DisplayContext(
                    rechecking = outcome.recheck.rechecking,
                    recheckAttempt = outcome.recheck.attempt,
                    recheckMaxAttempts = outcome.recheck.maxAttempts,
                    controlsEnabled = controls,
                    tollToggleNotYetApplied = outcome.tollToggleNotYetApplied,
                ),
            )
        }
        return DepartureUiState(
            content = content,
            loading = session.loading,
            showLoadingIndicator = session.showLoadingIndicator,
            controlsEnabled = controls,
            drivingNotice = if (controls) null else DRIVING_NOTICE,
        )
    }

    fun map(snapshot: DepartureSnapshot, ctx: DisplayContext): DepartureContent = when (snapshot) {
        is DepartureSnapshot.ConditionNotSet -> DepartureContent(
            conditionNotice = null,
            main = StatusCard(
                tone = Tone.INFO,
                icon = StatusIcon.INFO,
                title = "운전자 조건 미설정",
                lines = listOf(
                    DisplayLine("조건이 없다고 제한이 없는 것은 아니에요", emphasis = true),
                    DisplayLine("운전자 조건을 설정하면 출발 마감과 허용 반경을 알려 드려요"),
                ),
            ),
            radius = null,
            route = null,
            conditionSet = false,
        )
        is DepartureSnapshot.Checked -> {
            val discourages = discouragesDeparture(snapshot.deadline.stage)
            val radius = radiusCard(snapshot.radius, snapshot.condition, ctx)
            DepartureContent(
                conditionNotice = conditionNotice(snapshot.condition),
                main = deadlineCard(snapshot.deadline, ctx),
                radius = if (discourages) asSecondary(radius) else radius,
                route = if (discourages) null else routeCard(snapshot.route, snapshot.condition, ctx),
                // 작업 #26: 출발 전 확인은 내비 목적지 기준. 확인된 '목적지 없음'만 false(확인 불가는 따로 '확인 불가'로 보인다)
                destinationSet = (snapshot.destination as? com.pleos.departure.model.DataResult.Available)?.value
                    != com.pleos.departure.model.NaviDestination.NotSet,
                rechecking = ctx.rechecking,
            )
        }
    }

    /**
     * 확인 자체가 예외로 끝났을 때(작업 #9, 리뷰 task-07 MINOR-2). 1번 어댑터가 `DataResult` 대신 예외를 던진 경우 등.
     * 이전 결과는 오래됐을 수 있어 보여 주지 않고, 안전해 보이지 않게 "확인 불가"(UNKNOWN + ?)만 보여 준다.
     * 반경·경로 카드는 없다(한 화면 한 메시지). 상태 홀더가 주기 확인을 계속하므로 복구되면 자동으로 바뀐다.
     */
    fun checkFailed(ctx: DisplayContext): DepartureContent = DepartureContent(
        conditionNotice = null,
        main = StatusCard(
            tone = Tone.UNKNOWN,
            icon = StatusIcon.QUESTION,
            title = CHECK_FAILED_TITLE,
            lines = listOfNotNull(
                DisplayLine("확인 중 오류가 났어요", emphasis = true),
                DisplayLine("출발 마감·허용 반경·경로 조건을 지금은 알려 드릴 수 없어요"),
                DisplayLine("잠시 뒤 자동으로 다시 확인해요"),
                if (ctx.controlsEnabled) DisplayLine("'다시 확인'을 눌러도 돼요") else null,
            ),
        ),
        radius = null,
        route = null,
    )

    /**
     * 출발 자제 단계에서 반경 카드를 참고용으로 낮춘다(리뷰 task-07 MINOR-5).
     * "허용 반경 안"(OK, 초록 ✓)이 "출발을 자제하세요"와 같은 비중으로 보이면 출발해도 된다는 신호로 읽힐 수 있다 → OK 는 INFO 톤으로.
     * 반경 밖(DANGER)·확인 불가(UNKNOWN)는 톤을 그대로 둔다(출발 자제와 같은 방향이거나 안전해 보이면 안 되는 값). 아이콘·문구는 그대로.
     * 단 참고용 카드는 화면이 맨 위에 "참고" 글자를 붙이므로 본문 줄의 "참고: " 머리말은 뗀다("참고"가 두 번 나오지 않게 — 리뷰 task-09 MINOR-3).
     */
    private fun asSecondary(card: StatusCard): StatusCard = card.copy(
        tone = when (card.tone) {
            Tone.OK -> Tone.INFO
            Tone.INFO, Tone.CAUTION, Tone.DANGER, Tone.UNKNOWN -> card.tone
        },
        lines = card.lines.map { it.copy(text = it.text.removePrefix(REFERENCE_PREFIX)) },
        secondary = true,
    )

    // ---------------------------------------------------------------- 운전자 조건(D-9)

    private fun conditionNotice(resolved: ResolvedCondition): StatusCard? {
        val reasonLine = resolved.lookupFailure?.let { DisplayLine("사유: ${reasonText(it)}") }
        return when (resolved.source) {
            ConditionSource.LOADED -> null
            ConditionSource.LAST_KNOWN -> StatusCard(
                tone = Tone.UNKNOWN,
                icon = StatusIcon.QUESTION,
                title = "운전자 조건을 새로 확인하지 못했어요",
                lines = listOfNotNull(DisplayLine("마지막으로 확인한 조건으로 계산했어요", emphasis = true), reasonLine),
            )
            ConditionSource.ASSUMED_NIGHT_RESTRICTION -> StatusCard(
                tone = Tone.UNKNOWN,
                icon = StatusIcon.QUESTION,
                title = CONDITION_UNAVAILABLE_TITLE,
                lines = listOfNotNull(
                    DisplayLine("운전자 조건을 확인하지 못해 야간 운전 제한이 있다고 보고 계산했어요", emphasis = true),
                    reasonLine,
                ),
            )
        }
    }

    // ---------------------------------------------------------------- 출발 마감

    /** 단계별 공통 모양(색 + 아이콘 + 기본 제목). 색만으로 구분하지 않도록 아이콘·제목이 단계마다 다르다. */
    private data class StageLook(val tone: Tone, val icon: StatusIcon, val title: String)

    private fun look(stage: DeadlineStage): StageLook = when (stage) {
        DeadlineStage.NO_RESTRICTION -> StageLook(Tone.OK, StatusIcon.CHECK, "야간 운전 제한 없음")
        DeadlineStage.RELAXED -> StageLook(Tone.OK, StatusIcon.CLOCK, "출발 시간이 넉넉해요")
        DeadlineStage.CAUTION -> StageLook(Tone.CAUTION, StatusIcon.WARNING, "출발 마감이 다가와요")
        DeadlineStage.URGENT -> StageLook(Tone.DANGER, StatusIcon.WARNING, "곧 출발 마감이에요")
        DeadlineStage.PASSED -> StageLook(Tone.DANGER, StatusIcon.STOP, STOP_TITLE)
        DeadlineStage.NIGHT_NOW -> StageLook(Tone.DANGER, StatusIcon.NIGHT, "지금은 야간이에요")
        DeadlineStage.NO_DESTINATION -> StageLook(Tone.INFO, StatusIcon.INFO, "목적지를 설정해 주세요")
    }

    /** 출발을 자제하도록 안내하는 단계인가. 이때는 경로 조작 안내처럼 운전을 부추기는 요소를 보이지 않는다. */
    private fun discouragesDeparture(stage: DeadlineStage): Boolean = when (stage) {
        DeadlineStage.PASSED, DeadlineStage.NIGHT_NOW -> true
        DeadlineStage.NO_RESTRICTION, DeadlineStage.RELAXED, DeadlineStage.CAUTION,
        DeadlineStage.URGENT, DeadlineStage.NO_DESTINATION,
        -> false
    }

    private fun deadlineCard(result: DepartureDeadlineResult, ctx: DisplayContext): StatusCard = when (result) {
        is DepartureDeadlineResult.NoNightRestriction -> look(result.stage).let {
            StatusCard(it.tone, it.icon, it.title, lines = listOf(DisplayLine("운전자 조건에 야간 운전 제한이 없어요")))
        }
        is DepartureDeadlineResult.Deadline -> deadlineCard(result)
        is DepartureDeadlineResult.BeforeSunrise -> look(result.stage).let {
            StatusCard(
                it.tone, it.icon, it.title,
                headline = "오늘은 출발을 자제하세요",
                lines = listOf(
                    DisplayLine("아직 해가 뜨기 전이에요"),
                    DisplayLine("해가 뜬 뒤(${KoreanFormat.time(result.sunrise, zone)} 이후)에 다시 확인해 주세요"),
                ),
            )
        }
        is DepartureDeadlineResult.NoSunsetToday -> look(result.stage).let {
            StatusCard(it.tone, it.icon, "오늘은 해가 지지 않아요", lines = listOf(DisplayLine("야간 운전 제한에 걸리지 않아요")))
        }
        is DepartureDeadlineResult.AllDayNight -> look(result.stage).let {
            StatusCard(
                it.tone, it.icon, "오늘은 하루 종일 야간이에요",
                headline = "오늘은 출발을 자제하세요",
                lines = listOf(DisplayLine("오늘은 해가 뜨지 않아요")),
            )
        }
        is DepartureDeadlineResult.DestinationNotSet -> if (ctx.rechecking) {
            recheckingDestinationCard()
        } else {
            val l = look(result.stage)
            val origin = originSunsetText(result.originSunset)
            StatusCard(l.tone, l.icon, "내비에 목적지를 설정하면 출발 마감을 알려 드려요", headline = origin.first, lines = origin.second)
        }
        is DepartureDeadlineResult.DestinationUnavailable -> if (ctx.rechecking) {
            recheckingDestinationCard()
        } else {
            val origin = originSunsetText(result.originSunset)
            StatusCard(
                tone = Tone.UNKNOWN,
                icon = StatusIcon.QUESTION,
                title = "목적지를 확인할 수 없어요",
                headline = origin.first,
                lines = listOf(
                    DisplayLine("사유: ${reasonText(result.reason)}", emphasis = true),
                    DisplayLine("출발 마감을 계산하지 못했어요"),
                ) + origin.second,
            )
        }
    }

    private fun deadlineCard(d: DepartureDeadlineResult.Deadline): StatusCard {
        val l = look(d.stage)
        val deadlineText = KoreanFormat.time(d.deadline, zone)
        val sunsetText = KoreanFormat.time(d.sunset.sunset, zone)
        return when (d.stage) {
            // 출발 자제: 남은 시간(음수)·이동 시간·"지금 출발" 같은 값은 보여 주지 않는다.
            DeadlineStage.PASSED, DeadlineStage.NIGHT_NOW -> {
                val afterSunset = !d.evaluatedAt.isBefore(d.sunset.sunset.toInstant())
                StatusCard(
                    l.tone, l.icon, STOP_TITLE,
                    lines = listOf(
                        if (afterSunset) {
                            DisplayLine("해가 졌어요 (일몰 $sunsetText)", emphasis = true)
                        } else {
                            DisplayLine("출발 마감($deadlineText)이 지났어요", emphasis = true)
                        },
                        DisplayLine("일몰 전에 도착하기 어려워요"),
                        DisplayLine("목적지: ${d.destination.displayName}"),
                    ),
                )
            }
            DeadlineStage.NO_RESTRICTION, DeadlineStage.RELAXED, DeadlineStage.CAUTION,
            DeadlineStage.URGENT, DeadlineStage.NO_DESTINATION,
            -> StatusCard(
                l.tone, l.icon, l.title,
                headline = "${deadlineText}까지 출발",
                lines = listOf(
                    DisplayLine("남은 시간 ${KoreanFormat.remaining(d.remaining)}", emphasis = d.stage != DeadlineStage.RELAXED),
                    DisplayLine("목적지: ${d.destination.displayName}"),
                    DisplayLine("일몰 $sunsetText (${sunsetPlaceText(d.sunset.place)})"),
                    etaLine(d.eta),
                    DisplayLine("준비 여유 ${KoreanFormat.durationCeil(d.margin)} 포함"),
                ),
            )
        }
    }

    private fun sunsetPlaceText(place: SunsetPlace): String = when (place) {
        SunsetPlace.ORIGIN -> "출발지 기준"
        SunsetPlace.DESTINATION -> "목적지 기준"
    }

    /** ETA 출처별 표기. SDK 실측이 아니면 "예상치"를 굵게(리뷰 task-04b #7-2). */
    private fun etaLine(eta: AppliedEta): DisplayLine {
        val minutes = KoreanFormat.durationCeil(eta.duration)
        return when (eta.source) {
            EstimateSource.SDK_MEASURED -> DisplayLine("예상 이동 시간 $minutes (내비 기준)")
            EstimateSource.ESTIMATED -> DisplayLine("예상 이동 시간 약 $minutes (예상치 · 무료도로 경로로 늘려 잡음)", emphasis = true)
            EstimateSource.DEFAULT -> {
                val why = eta.unavailableReason?.let { " · ${reasonText(it)}" } ?: ""
                DisplayLine("이동 시간을 몰라 ${minutes}으로 잡았어요 (예상치$why)", emphasis = true)
            }
        }
    }

    /** 목적지 없을 때 보여 줄 출발지 일몰 → (headline, lines). */
    private fun originSunsetText(origin: OriginSunset?): Pair<String?, List<DisplayLine>> {
        if (origin == null) return null to listOf(DisplayLine("현재 위치를 몰라 일몰 시각을 확인할 수 없어요", emphasis = true))
        val sunset = origin.sunset
            ?: return null to listOf(DisplayLine("오늘은 이곳의 일몰 시각을 계산할 수 없어요", emphasis = true))
        val where = when (origin.source) {
            OriginSource.CURRENT_LOCATION -> "현재 위치 기준"
            OriginSource.HOME -> "집 기준 (현재 위치를 몰라요)"
        }
        return "오늘 일몰 ${KoreanFormat.time(sunset.sunset, zone)}" to listOf(DisplayLine(where))
    }

    private fun recheckingDestinationCard() = StatusCard(
        tone = Tone.INFO,
        icon = StatusIcon.WAIT,
        title = RECHECKING_DESTINATION_TITLE,
        lines = listOf(DisplayLine("내비가 경로를 바꾸는 중일 수 있어요")),
    )

    // ---------------------------------------------------------------- 반경

    private fun radiusCard(result: RadiusCheckResult, condition: ResolvedCondition, ctx: DisplayContext): StatusCard = when (result) {
        is RadiusCheckResult.Checked -> {
            val outside = result.isDestinationOutside
            StatusCard(
                tone = if (outside) Tone.DANGER else Tone.OK,
                icon = if (outside) StatusIcon.WARNING else StatusIcon.CHECK,
                title = if (outside) "목적지가 허용 반경 밖이에요" else "목적지가 허용 반경 안이에요",
                headline = "집에서 ${KoreanFormat.distance(result.destinationCheck.distanceMeters)} · 허용 ${KoreanFormat.radius(result.radiusMeters)}",
                lines = listOf(
                    DisplayLine("목적지: ${result.destination.displayName}"),
                    currentLocationLine(result.currentLocationCheck),
                    DisplayLine("직선거리 기준이에요. 가는 길 중간은 확인하지 않아요"),
                ),
            )
        }
        RadiusCheckResult.DestinationNotSet -> if (ctx.rechecking) {
            recheckingDestinationCard()
        } else {
            StatusCard(Tone.INFO, StatusIcon.INFO, "목적지가 없어 허용 반경을 확인하지 않았어요")
        }
        is RadiusCheckResult.DestinationUnavailable -> StatusCard(
            tone = Tone.UNKNOWN,
            icon = StatusIcon.QUESTION,
            title = RADIUS_UNKNOWN_TITLE,
            lines = listOf(
                DisplayLine("사유: ${reasonText(result.reason)}", emphasis = true),
                DisplayLine("목적지가 반경 안인지 확인하지 못했어요"),
            ),
        )
        is RadiusCheckResult.CenterNotSet -> StatusCard(
            tone = Tone.UNKNOWN,
            icon = StatusIcon.QUESTION,
            title = RADIUS_UNKNOWN_TITLE,
            lines = listOf(
                DisplayLine(
                    when (condition.source) {
                        ConditionSource.ASSUMED_NIGHT_RESTRICTION -> "운전자 조건을 확인하지 못해 기준 위치(집)를 몰라요"
                        ConditionSource.LOADED, ConditionSource.LAST_KNOWN -> "운전자 조건에 기준 위치(집)가 없어요"
                    },
                    emphasis = true,
                ),
                DisplayLine("목적지: ${result.destination.displayName}"),
            ),
        )
    }

    /**
     * 현재 위치 판정은 참고로만 보여 준다(경고 색은 목적지 판정에만 — 리뷰 task-05 #7-2).
     * 머리말 [REFERENCE_PREFIX]는 카드가 참고용([asSecondary])이면 뗀다.
     */
    private fun currentLocationLine(check: PointRadiusCheck): DisplayLine = when (check) {
        is PointRadiusCheck.Measured -> DisplayLine(
            "${REFERENCE_PREFIX}현재 위치는 집에서 ${KoreanFormat.distance(check.distanceMeters)} (반경 ${if (check.within) "안" else "밖"})",
        )
        is PointRadiusCheck.Unavailable -> DisplayLine("${REFERENCE_PREFIX}현재 위치는 확인하지 못했어요")
    }

    // ---------------------------------------------------------------- 경로 조건

    private fun routeCard(result: RouteConditionCheck, condition: ResolvedCondition, ctx: DisplayContext): RouteCard = when (result) {
        RouteConditionCheck.NoRouteCondition -> when (condition.source) {
            ConditionSource.ASSUMED_NIGHT_RESTRICTION -> RouteCard(
                StatusCard(
                    Tone.UNKNOWN, StatusIcon.QUESTION, "경로 조건 확인 불가",
                    lines = listOf(DisplayLine("운전자 조건을 확인하지 못해 경로 조건(무료도로 등)을 알 수 없어요", emphasis = true)),
                ),
            )
            ConditionSource.LOADED, ConditionSource.LAST_KNOWN -> RouteCard(
                StatusCard(Tone.INFO, StatusIcon.INFO, "경로 조건 없음", lines = listOf(DisplayLine("운전자 조건에 경로 조건이 없어요"))),
            )
        }
        RouteConditionCheck.DestinationNotSet -> RouteCard(
            if (ctx.rechecking) recheckingDestinationCard() else StatusCard(Tone.INFO, StatusIcon.INFO, "목적지가 없어 경로 조건을 확인하지 않았어요"),
        )
        is RouteConditionCheck.DestinationUnavailable -> RouteCard(
            StatusCard(
                Tone.UNKNOWN, StatusIcon.QUESTION, ROUTE_UNKNOWN_TITLE,
                lines = listOf(DisplayLine("사유: ${reasonText(result.reason)}", emphasis = true)),
            ),
        )
        is RouteConditionCheck.NotGuiding -> RouteCard(
            if (ctx.rechecking) {
                StatusCard(
                    Tone.INFO, StatusIcon.WAIT, RECHECKING_ROUTE_TITLE,
                    lines = listOf(
                        DisplayLine("내비가 경로를 다시 찾는 중일 수 있어요"),
                        DisplayLine("잠시 뒤 다시 확인해요 (${ctx.recheckAttempt}/${ctx.recheckMaxAttempts})"),
                    ),
                )
            } else {
                StatusCard(
                    Tone.UNKNOWN, StatusIcon.QUESTION, "내비 경로 안내가 시작되지 않았어요",
                    lines = listOfNotNull(
                        DisplayLine("무료도로 적용 여부를 확인하지 못했어요", emphasis = true),
                        if (ctx.controlsEnabled) DisplayLine("내비에서 안내를 시작한 뒤 '다시 확인'을 눌러 주세요") else null,
                    ),
                )
            },
        )
        is RouteConditionCheck.Unconfirmed -> RouteCard(
            StatusCard(
                Tone.UNKNOWN, StatusIcon.QUESTION, "무료도로 적용 확인 불가",
                lines = listOf(
                    DisplayLine("사유: ${reasonText(result.reason)}", emphasis = true),
                    DisplayLine("내비 화면에서 경로를 확인하세요"),
                ),
            ),
        )
        is RouteConditionCheck.Satisfied -> RouteCard(
            StatusCard(
                Tone.OK, StatusIcon.CHECK, "무료도로 경로로 안내 중이에요",
                lines = listOfNotNull(tollFareLine(result.routeState)),
            ),
            notes = result.notes.map(::noteLine),
        )
        is RouteConditionCheck.OtherRouteOption -> RouteCard(
            StatusCard(
                Tone.CAUTION, StatusIcon.QUESTION, "무료도로 적용을 확인해 주세요",
                lines = listOf(
                    DisplayLine("내비가 다른 경로 옵션을 쓰고 있어요"),
                    DisplayLine("내비 경로 설정에서 '유료도로 우회'가 켜져 있는지 확인해 주세요", emphasis = true),
                ),
            ),
            notes = result.notes.map(::noteLine),
        )
        is RouteConditionCheck.NeedsUserAction -> needsUserActionCard(result, ctx)
        is RouteConditionCheck.ReferenceOnly -> RouteCard(
            StatusCard(Tone.INFO, StatusIcon.INFO, "고속도로 회피는 참고만 해 주세요"),
            notes = result.notes.map(::noteLine),
        )
    }

    private fun needsUserActionCard(result: RouteConditionCheck.NeedsUserAction, ctx: DisplayContext): RouteCard {
        val first = result.actions.firstOrNull()
        val title = when (first) {
            RouteUserAction.TURN_ON_TOLL_AVOIDANCE -> "유료도로를 지나는 경로예요"
            RouteUserAction.REVIEW_TOLL_ON_ROUTE -> "통행료가 있는 경로예요"
            null -> "경로를 확인해 주세요"
        }
        val lines = mutableListOf<DisplayLine>()
        tollFareLine(result.routeState)?.let(lines::add)
        var steps = emptyList<String>()
        var confirm = false
        for (action in result.actions) {
            when (action) {
                RouteUserAction.TURN_ON_TOLL_AVOIDANCE -> {
                    if (ctx.tollToggleNotYetApplied) {
                        lines += DisplayLine("아직 '유료도로 우회'가 적용되지 않았어요. '적용'까지 눌렀는지 확인해 주세요", emphasis = true)
                    }
                    if (ctx.controlsEnabled) {
                        lines += DisplayLine("무료도로로 가려면 내비에서 아래 순서대로 해 주세요", emphasis = true)
                        steps = TOGGLE_STEPS
                        confirm = true
                    } else {
                        lines += DisplayLine(PARKED_ONLY_GUIDE, emphasis = true)
                    }
                }
                RouteUserAction.REVIEW_TOLL_ON_ROUTE -> {
                    lines += DisplayLine("'유료도로 우회'는 켜져 있지만 통행료가 확인됐어요", emphasis = true)
                    lines += DisplayLine("내비 경로 화면에서 경로를 확인해 주세요")
                }
            }
        }
        return RouteCard(
            card = StatusCard(Tone.CAUTION, StatusIcon.WARNING, title, lines = lines),
            steps = steps,
            showConfirmToggle = confirm,
            notes = result.notes.map(::noteLine),
        )
    }

    /** 통행료는 받았을 때만 표시한다(못 받으면 notes 의 TOLL_FARE_NOT_OBSERVED 가 대신 설명). */
    private fun tollFareLine(state: RouteState.Guiding): DisplayLine? = state.tollFare?.let { DisplayLine("통행료 ${KoreanFormat.won(it)}") }

    private fun noteLine(note: RouteNote): DisplayLine = when (note) {
        RouteNote.AVOID_HIGHWAY_NOT_GUARANTEED ->
            DisplayLine("고속도로 회피는 내비 기능으로 보장할 수 없어요. 통행료 없는 고속도로가 포함될 수 있어요")
        RouteNote.TOLL_FARE_NOT_OBSERVED ->
            DisplayLine("이 경로의 통행료 정보는 받지 못했어요. '유료도로 우회' 설정만 확인했어요")
    }

    // ---------------------------------------------------------------- 공통

    private fun reasonText(reason: UnavailableReason): String = when (reason) {
        UnavailableReason.NOT_SUPPORTED -> "이 차량에서는 확인할 수 없어요"
        UnavailableReason.TIMEOUT -> "응답이 없어요"
        UnavailableReason.NO_DATA -> "정보가 없어요"
        UnavailableReason.SERVICE_ERROR -> "처리 중 오류가 났어요"
        UnavailableReason.PERMISSION_DENIED -> "권한이 없어요"
        UnavailableReason.INVALID_REQUEST -> "요청 정보가 올바르지 않아요"
    }

    companion object {
        /** 감독관 결정 D-9 문구(조회 실패 + 마지막 값 없음). */
        const val CONDITION_UNAVAILABLE_TITLE = "조건 확인 불가 — 야간 제한 기준"
        const val STOP_TITLE = "오늘은 출발을 자제하세요"
        const val RECHECKING_DESTINATION_TITLE = "목적지를 다시 확인하고 있어요"
        const val RECHECKING_ROUTE_TITLE = "경로를 확인하고 있어요"
        const val RADIUS_UNKNOWN_TITLE = "허용 반경 확인 불가"
        const val ROUTE_UNKNOWN_TITLE = "경로 조건 확인 불가"
        const val PARKED_ONLY_GUIDE = "정차한 뒤 설정 방법을 안내해 드려요"
        const val CHECK_FAILED_TITLE = "출발 전 확인 불가"
        /** 주행 중(조작 불가) 안내 문구. */
        const val DRIVING_NOTICE = "주행 중에는 화면 조작을 할 수 없어요"
        /** 반경 카드의 현재 위치 줄 머리말. 참고용 카드에서는 화면의 "참고" 글자와 겹치므로 뗀다. */
        const val REFERENCE_PREFIX = "참고: "

        /** 내비 "유료도로 우회" 켜는 순서(api-notes/navi-runtime-check.md §10.4 — 경로 설정 → 유료도로 우회 → 적용). */
        val TOGGLE_STEPS = listOf(
            "내비 화면에서 '경로 설정'을 누르세요",
            "'유료도로 우회'를 켜세요",
            "'적용'을 누르세요",
        )
    }
}
