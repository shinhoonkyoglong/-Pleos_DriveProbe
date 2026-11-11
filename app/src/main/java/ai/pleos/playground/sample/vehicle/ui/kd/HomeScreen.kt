package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.data.local.entity.ReactionScoreEntity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 첫 화면(정차 중). 출발 전에 "오늘 운전해도 되나요?"와 "오늘 컨디션"을 한눈에 보고, 큰 버튼 3개로 이동한다.
 *
 * @param departureCard 2번 '출발 전' 홈 카드 자리. feat/departure-home 의 DepartureHomeCard 를 그대로 넣는다.
 *                      main 에는 아직 그 모듈이 없어 기본값은 자리 표시 카드다.
 */
@Composable
fun HomeScreen(
    state: KdUiState,
    onOpen: (KdPage) -> Unit,
    onDismissTripEnd: () -> Unit,
    onSkipCheck: () -> Unit = {},
    departureCard: @Composable (Modifier) -> Unit = { DeparturePlaceholderCard(it, onClick = { onOpen(KdPage.DEPARTURE_DETAIL) }) },
) {
    val recommended = state.checkAdvice.isRecommended
    KdScreen(testTag = "kd-home") {
        HomeHeader(state)

        if (state.showTripEnd) {
            TripEndBanner(state, onOpenRecords = { onOpen(KdPage.RECORDS) }, onDismiss = onDismissTripEnd)
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
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

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Kd.Gap)) {
            KdButton(
                "컨디션 체크",
                subText = "원할 때 · 정차 중 1분",
                onClick = { onOpen(KdPage.REACTION_CHECK) },
                // 권유할 때만 강조한다(팀 결정: 체크를 강요하지 않음)
                style = if (recommended) KdButtonStyle.PRIMARY else KdButtonStyle.SECONDARY,
                modifier = Modifier.weight(1f).testTag("kd-home-reaction"),
                minHeight = 128.dp,
            )
            KdButton(
                "내 운전 조건",
                subText = "야간·반경·도로 조건",
                onClick = { onOpen(KdPage.DRIVER_CONDITION) },
                style = KdButtonStyle.SECONDARY,
                modifier = Modifier.weight(1f).testTag("kd-home-condition"),
                minHeight = 128.dp,
            )
            KdButton(
                "내 기록",
                subText = "컨디션 추이·가족 요약",
                onClick = { onOpen(KdPage.RECORDS) },
                style = KdButtonStyle.SECONDARY,
                modifier = Modifier.weight(1f).testTag("kd-home-records"),
                minHeight = 128.dp,
            )
        }

        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            KdButton(
                "개발자 도구",
                onClick = { onOpen(KdPage.DEV_TOOLS) },
                style = KdButtonStyle.QUIET,
                minHeight = 72.dp,
            )
        }
    }
}

@Composable
private fun HomeHeader(state: KdUiState) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("계속운전", style = Kd.Headline)
            Text(formatToday(), style = Kd.BodyMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.naviDemo) KdTag("내비 예시 데이터")
            if (state.vehicle.isReplay) KdTag("시연 모드")
            KdTag(state.vehicle.gear.label(), color = Kd.InkMuted)
        }
    }
}

/** 2번 홈 카드가 들어올 자리. 문구는 2번 카드의 '목적지 없음' 상태와 같은 톤으로 둔다 */
@Composable
fun DeparturePlaceholderCard(modifier: Modifier = Modifier, onClick: () -> Unit) {
    KdCard(modifier = modifier, borderColor = Kd.Accent, onClick = onClick) {
        Text("오늘 운전해도 되나요?", style = Kd.BodyMuted)
        KdStatusBadge(KdTone.INFO, "내비에 목적지를 정하면 확인해요")
        Text("출발 마감 시각 · 허용 반경 · 무료도로 경로를 차가 먼저 따져 드려요", style = Kd.Body)
        Spacer(Modifier.weight(1f))
        Text("자세히 보기 ›", style = Kd.Label.copy(color = Kd.Accent))
    }
}

/**
 * '오늘 컨디션' 카드. 팀 결정에 따라 체크를 강요하지 않는다:
 * - 권유(밤·새벽, 지난 주행 피로 신호): "체크할까요?" + [체크하기] [바로 출발]
 * - 그 밖: "원하면 체크할 수 있어요" 한 줄(눈에 띄지 않게)
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
                Text("오늘 컨디션", style = Kd.BodyMuted)
                KdStatusBadge(status.tone, status.title)
                Text(status.detail, style = Kd.Body)
                Text("반응 ${latest.simpleReactionTimeMs}ms · ${formatDateTime(latest.timestamp)}", style = Kd.BodyMuted)
                Spacer(Modifier.weight(1f))
                Text("다시 체크하기 ›", style = Kd.Label.copy(color = Kd.Accent))
            }
            return
        }
    }

    if (advice.isRecommended) {
        KdCard(modifier = modifier, borderColor = Kd.Caution) {
            Text("오늘 컨디션", style = Kd.BodyMuted)
            KdStatusBadge(KdTone.CAUTION, "출발 전에 1분 체크할까요?")
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
        Text("오늘 컨디션", style = Kd.BodyMuted)
        KdStatusBadge(KdTone.INFO, "바로 출발해도 괜찮아요")
        Text(
            if (advice == CheckAdvice.SKIPPED_TODAY) "오늘은 체크를 건너뛰었어요"
            else "궁금할 때 1분 반응 체크로 평소와 비교해 볼 수 있어요",
            style = Kd.Body,
        )
        Spacer(Modifier.weight(1f))
        Text("원하면 체크하기 ›", style = Kd.Label.copy(color = Kd.InkMuted))
    }
}

@Composable
private fun TripEndBanner(state: KdUiState, onOpenRecords: () -> Unit, onDismiss: () -> Unit) {
    val trip = state.vehicle.lastTrip
    val max = trip?.maxLevel ?: FatigueLevel.NORMAL
    val status = max.toStatus()
    KdCard(modifier = Modifier.fillMaxWidth(), borderColor = status.tone.color, background = Kd.SurfaceRaised) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

