package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.BuildConfig
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.data.local.entity.ReactionScoreEntity
import ai.pleos.playground.sample.vehicle.trip.ui.rememberTripWeekNumbers
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 첫 화면(정차 중). 출발 전 확인과 오늘 컨디션을 보고, 주행 도움을 포함한 주요 기능(타일 4개)으로 이동한다.
 *
 * 화면 높이를 남김없이 쓴다: 위 두 카드가 남은 높이를 채우고, 그 아래에 '이번 주' 요약 띠와 메뉴 타일을 둔다.
 * (이전에는 아래 절반이 비어 있었다)
 *
 * @param departureCard 2번 '출발 전' 홈 카드 자리. 받은 Modifier 로 높이를 채워야 한다(fillMaxHeight 포함).
 */
@Composable
fun HomeScreen(
    state: KdUiState,
    onOpen: (KdPage) -> Unit,
    onDismissTripEnd: () -> Unit,
    onSkipCheck: () -> Unit = {},
    assistSummary: String = "음성 준비 필요",
    departureCard: @Composable (Modifier) -> Unit = { DeparturePlaceholderCard(it, onClick = { onOpen(KdPage.DEPARTURE_DETAIL) }) },
) {
    val recommended = state.checkAdvice.isRecommended
    KdScreen(testTag = "kd-home") {
        HomeHeader(state, onOpenDevTools = { onOpen(KdPage.DEV_TOOLS) })

        if (state.showTripEnd) {
            TripEndBanner(state, onOpenRecords = { onOpen(KdPage.RECORDS) }, onDismiss = onDismissTripEnd)
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Kd.Gap),
        ) {
            departureCard(Modifier.weight(1f).fillMaxHeight())
            TodayConditionCard(
                advice = state.checkAdvice,
                latest = state.reactionHistory.firstOrNull(),
                onStart = { onOpen(KdPage.REACTION_CHECK) },
                onSkip = onSkipCheck,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        WeekStrip(state, onClick = { onOpen(KdPage.RECORDS) })

        Row(modifier = Modifier.fillMaxWidth().height(176.dp), horizontalArrangement = Arrangement.spacedBy(Kd.Gap)) {
            KdNavTile(
                "컨디션 체크",
                subtitle = "정차 중 약 30초",
                icon = Icons.Rounded.Favorite,
                onClick = { onOpen(KdPage.REACTION_CHECK) },
                // 권유할 때만 강조한다(팀 결정: 체크를 강요하지 않음)
                highlighted = recommended,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("kd-home-reaction"),
            )
            KdNavTile(
                "주행 도움",
                subtitle = assistSummary,
                icon = Icons.Rounded.Notifications,
                onClick = { onOpen(KdPage.DRIVING_ASSIST) },
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("kd-home-assist"),
            )
            KdNavTile(
                "내 운전 조건",
                subtitle = "야간, 반경, 도로 조건",
                icon = Icons.Rounded.Settings,
                onClick = { onOpen(KdPage.DRIVER_CONDITION) },
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("kd-home-condition"),
            )
            KdNavTile(
                "내 기록",
                subtitle = "변화와 가족 요약",
                icon = Icons.Rounded.DateRange,
                onClick = { onOpen(KdPage.RECORDS) },
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("kd-home-records"),
            )
        }
    }
}

/** 홈의 [개발자 도구] 버튼은 debug 빌드에서만 보인다(시연용 release 빌드에서는 숨김) */
private val SHOW_DEV_TOOLS = BuildConfig.DEBUG

@Composable
private fun HomeHeader(state: KdUiState, onOpenDevTools: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("계속운전", style = Kd.Headline)
            Text(formatToday(), style = Kd.BodyMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.naviDemo) KdTag("내비 예시 데이터")
            if (state.vehicle.isReplay) KdTag("시연 모드")
            KdTag(state.vehicle.gear.label(), color = Kd.InkMuted)
            Spacer(Modifier.width(4.dp))
            // 화면 밝기 전환(다크 ↔ 라이트). 정차 중 홈에서만 보이고, 주행 화면에는 두지 않는다
            KdThemeToggleButton()
            // 운전자용 기능이 아니라 오른쪽 위에 작게 둔다(이전: 화면 아래 큰 버튼)
            if (SHOW_DEV_TOOLS) {
                KdButton(
                    "개발자 도구",
                    onClick = onOpenDevTools,
                    style = KdButtonStyle.QUIET,
                    minHeight = 64.dp,
                    icon = Icons.Rounded.Build,
                    modifier = Modifier.testTag("kd-home-devtools"),
                )
            }
        }
    }
}

/** '이번 주' 한 줄 요약. 누르면 내 기록으로 간다 */
@Composable
private fun WeekStrip(state: KdUiState, onClick: () -> Unit) {
    val week = rememberTripWeekNumbers()
    val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
    val helps = state.interventions.count { it.timestamp >= weekAgo }
    val latest = state.reactionHistory.firstOrNull()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Kd.CardShape)
            .background(Kd.Surface)
            .clickable(role = Role.Button, onClickLabel = "내 기록 보기", onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 32.dp)
            .testTag("kd-home-week"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("이번 주", style = Kd.Title)
            week.tag?.let { KdTag(it, color = Kd.Unknown) }
        }
        StripDivider()
        KdStat(week.trips, "운전", Modifier.weight(1f).padding(start = 28.dp))
        StripDivider()
        KdStat(week.compliance, "조건 준수", Modifier.weight(1f).padding(start = 28.dp), tone = week.complianceTone)
        StripDivider()
        KdStat("${helps}회", "주행 중 도움", Modifier.weight(1f).padding(start = 28.dp))
        StripDivider()
        KdStat(latest?.let { "${it.simpleReactionTimeMs}ms" } ?: "-", "최근 반응 속도", Modifier.weight(1f).padding(start = 28.dp))
        Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = null, tint = Kd.InkMuted, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun StripDivider() {
    Box(Modifier.width(1.dp).height(72.dp).background(Kd.Outline))
}

/** 2번 홈 카드가 들어올 자리. 문구는 2번 카드의 '목적지 없음' 상태와 같은 톤으로 둔다 */
@Composable
fun DeparturePlaceholderCard(modifier: Modifier = Modifier, onClick: () -> Unit) {
    KdCard(modifier = modifier, onClick = onClick) {
        Text("오늘 운전해도 되나요?", style = Kd.Eyebrow)
        Spacer(Modifier.weight(1f)) // 본문을 세로 가운데로(카드가 높아도 가운데가 비어 보이지 않게)
        KdStatusBadge(KdTone.INFO, "내비에 목적지를 정하면 확인해요")
        Text("출발 마감 시각 · 허용 반경 · 무료도로 경로를 차가 먼저 따져 드려요", style = Kd.Body)
        Spacer(Modifier.weight(1f))
        CardLink("자세히 보기")
    }
}

/** 카드 맨 아래 오른쪽 '›' 링크 */
@Composable
private fun CardLink(text: String, muted: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        val color = if (muted) Kd.InkMuted else Kd.Accent
        Text(text, style = Kd.Label.copy(color = color))
        Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = null, tint = color)
    }
}

/**
 * '오늘 컨디션' 카드. 팀 결정에 따라 체크를 강요하지 않는다:
 * - 권유(밤·새벽, 지난 주행 피로 신호): "체크할까요?" + [체크하기] [바로 출발]
 * - 그 밖: "바로 출발해도 괜찮아요" + 최근 체크 기록(있으면)
 */
@Composable
private fun TodayConditionCard(
    advice: CheckAdvice,
    latest: ReactionScoreEntity?,
    onStart: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (advice == CheckAdvice.DONE_TODAY) {
        val verdict = latest?.notes?.let { n -> ReactionVerdict.entries.firstOrNull { it.name == n } }
        if (latest != null && verdict != null) {
            val status = verdict.toStatus()
            KdCard(modifier = modifier, borderColor = status.tone.color, onClick = onStart) {
                Text("오늘 컨디션", style = Kd.Eyebrow)
                Spacer(Modifier.weight(1f)) // 본문을 세로 가운데로(카드가 높아도 가운데가 비어 보이지 않게)
                KdStatusBadge(status.tone, status.title, large = true)
                Text(status.detail, style = Kd.Body)
                Spacer(Modifier.weight(1f))
                LastCheck(latest)
                CardLink("다시 체크하기")
            }
            return
        }
    }

    if (advice.isRecommended) {
        KdCard(modifier = modifier, borderColor = Kd.Caution) {
            Text("오늘 컨디션", style = Kd.Eyebrow)
            Spacer(Modifier.weight(1f)) // 본문을 세로 가운데로(카드가 높아도 가운데가 비어 보이지 않게)
            KdStatusBadge(KdTone.CAUTION, "출발 전에 30초 체크할까요?", large = true)
            Text(
                if (advice == CheckAdvice.RECOMMEND_NIGHT) "어두운 시간이라 평소보다 반응이 느릴 수 있어요"
                else "지난 운전에서 피곤한 신호가 있었어요",
                style = Kd.Body,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                KdButton(
                    "체크하기",
                    onClick = onStart,
                    modifier = Modifier.weight(1f).testTag("kd-home-check-yes"),
                    minHeight = 88.dp,
                )
                KdButton(
                    "바로 출발",
                    onClick = onSkip,
                    style = KdButtonStyle.SECONDARY,
                    modifier = Modifier.weight(1f).testTag("kd-home-check-skip"),
                    minHeight = 88.dp,
                )
            }
        }
        return
    }

    // OPTIONAL / SKIPPED_TODAY: 권하지 않는다
    KdCard(modifier = modifier, onClick = onStart) {
        Text("오늘 컨디션", style = Kd.Eyebrow)
        Spacer(Modifier.weight(1f)) // 본문을 세로 가운데로(카드가 높아도 가운데가 비어 보이지 않게)
        KdStatusBadge(KdTone.GOOD, "바로 출발해도 괜찮아요", large = true)
        Text(
            if (advice == CheckAdvice.SKIPPED_TODAY) "오늘은 체크를 건너뛰었어요"
            else "궁금할 때 30초 반응 체크로 평소와 비교해 볼 수 있어요",
            style = Kd.Body,
        )
        Spacer(Modifier.weight(1f))
        if (latest != null) LastCheck(latest) else {
            Text("아직 체크한 기록이 없어요", style = Kd.BodyMuted)
        }
        CardLink("원하면 체크하기", muted = true)
    }
}

/** 마지막 체크 결과 한 덩어리(옅은 면) */
@Composable
private fun LastCheck(latest: ReactionScoreEntity) {
    Row(
        Modifier.fillMaxWidth().clip(Kd.ButtonShape).background(Kd.SurfaceRaised).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("마지막 체크", style = Kd.Eyebrow)
            Text(formatDateTime(latest.timestamp), style = Kd.BodyMuted)
        }
        Text("${latest.simpleReactionTimeMs}ms", style = Kd.Title)
    }
}

@Composable
private fun TripEndBanner(state: KdUiState, onOpenRecords: () -> Unit, onDismiss: () -> Unit) {
    val trip = state.vehicle.lastTrip
    val max = trip?.maxLevel ?: FatigueLevel.NORMAL
    val status = max.toStatus()
    KdCard(modifier = Modifier.fillMaxWidth(), borderColor = status.tone.color, contentPadding = 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KdToneDot(status.tone, size = 64.dp)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("운전 수고하셨어요", style = Kd.Title)
                Text(
                    if (max == FatigueLevel.NORMAL) "이번 주행 내내 컨디션이 좋았어요"
                    else "이번 주행에서 가장 높았던 단계: ${status.title}",
                    style = Kd.Body,
                )
                if (trip != null && trip.baselineUpdated) {
                    Text("이번 주행이 평소 기록에 더해졌어요", style = Kd.BodyMuted)
                }
            }
            Spacer(Modifier.width(Kd.Gap))
            KdButton("기록 보기", onClick = onOpenRecords, minHeight = 88.dp)
            Spacer(Modifier.width(16.dp))
            KdButton("닫기", onClick = onDismiss, style = KdButtonStyle.SECONDARY, minHeight = 88.dp)
        }
    }
}
