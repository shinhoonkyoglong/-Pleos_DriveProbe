package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.condition.core.ReactionInput
import ai.pleos.playground.sample.vehicle.condition.core.ReactionPhase
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.condition.core.TrialOutcome
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * 출발 전 반응 체크. 정차(P) 중에만 진행되고, 기어가 바뀌거나 차가 움직이면 바로 멈춘다(ReactionGate).
 *
 * 흐름: 안내 → [시작] → 화면 가운데 원이 초록으로 바뀌면 반응 → 제때 반응 10회 또는 최대 40초 → 결과
 * 반응 방법: 실제 차량 = 브레이크 페달을 밟았다 뗀다 / 시연 재생 = 화면 아무 곳이나 누른다(ReactionUiState.input)
 * 시각은 SystemClock.elapsedRealtime 기준(브레이크 이벤트 시각과 같은 시계)
 */
@Composable
fun ReactionCheckScreen(viewModel: ReactionCheckViewModel, isReplay: Boolean, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val running = state.phase == ReactionPhase.WAITING || state.phase == ReactionPhase.STIMULUS

    val leave = {
        viewModel.cancel()
        onBack()
    }
    BackHandler(onBack = leave)

    // 준비 화면: 차량 상태가 바뀌면 시작 가능 여부를 다시 계산한다
    LaunchedEffect(state.phase) {
        if (state.phase == ReactionPhase.READY) {
            while (true) {
                viewModel.refreshGate()
                delay(500)
            }
        }
    }
    // 진행 중: 매 프레임 세션을 진행시킨다(시각은 터치·브레이크와 같은 elapsedRealtime 기준)
    LaunchedEffect(running) {
        if (running) {
            while (true) {
                withFrameMillis { }
                viewModel.onFrame(SystemClock.elapsedRealtime())
            }
        }
    }

    when {
        running -> RunningView(state, isReplay, onTap = { viewModel.onTap(SystemClock.elapsedRealtime()) }, onStop = leave)
        state.phase == ReactionPhase.READY -> IntroView(
            state = state,
            recordCount = viewModel.personalRecordCount,
            isReplay = isReplay,
            onStart = { viewModel.start(SystemClock.elapsedRealtime()) },
            onBack = leave,
        )
        else -> ResultView(state, isReplay, onRetry = viewModel::reset, onDone = leave)
    }
}

@Composable
private fun IntroView(state: ReactionUiState, recordCount: Int, isReplay: Boolean, onStart: () -> Unit, onBack: () -> Unit) {
    KdScreen(testTag = "kd-reaction-intro") {
        KdTopBar("출발 전 컨디션 체크", onBack = onBack) { if (isReplay) KdTag("시연 모드") }
        // 두 카드가 남은 높이를 채운다(이전에는 카드 아래가 통째로 비어 있었다)
        Row(horizontalArrangement = Arrangement.spacedBy(Kd.Gap), modifier = Modifier.fillMaxWidth().weight(1f)) {
            KdCard(modifier = Modifier.weight(1f).fillMaxHeight(), spacing = 20.dp) {
                Text("이렇게 해요", style = Kd.Eyebrow)
                val brake = state.input == ReactionInput.BRAKE
                if (brake) {
                    Step("1", "브레이크에서 발을 떼고 화면 가운데 원을 바라봐 주세요")
                    Step("2", "원이 초록색으로 바뀌면 브레이크를 바로 밟았다가 떼 주세요")
                } else {
                    Step("1", "화면 가운데 원을 바라봐 주세요")
                    Step("2", "원이 초록색으로 바뀌면 화면 아무 곳이나 바로 눌러 주세요")
                }
                Step("3", "제때 ${state.targetValid}번 하면 끝나요(최대 ${state.totalMs / 1000}초). 미리 ${if (brake) "밟지" else "누르지"} 마세요")
                StimulusPreview(actionLabel = if (brake) "바로 밟기" else "바로 누르기", modifier = Modifier.weight(1f))
            }
            KdCard(modifier = Modifier.weight(1f).fillMaxHeight(), spacing = 20.dp) {
                Text("결과는 이렇게 나와요", style = Kd.Eyebrow)
                Text("시험이 아니라 평소 내 기록과 비교해요", style = Kd.Title)
                // 실제 결과 문구(ReactionVerdict.toStatus)를 그대로 미리 보여 준다
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    VerdictExample(ReactionVerdict.NORMAL)
                    VerdictExample(ReactionVerdict.SLOWER)
                    VerdictExample(ReactionVerdict.MUCH_SLOWER)
                }
                Text("결과가 나빠도 시동을 막지 않아요. 쉬어 갈지는 직접 정하세요.", style = Kd.BodyMuted)
                RecordProgress(recordCount)
            }
        }
        if (state.blockedReason != null) {
            KdStatusBadge(KdTone.CAUTION, state.blockedReason)
        }
        KdButton(
            "시작하기",
            onClick = onStart,
            enabled = state.blockedReason == null,
            modifier = Modifier.fillMaxWidth().testTag("kd-reaction-start"),
            minHeight = 120.dp,
        )
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Kd.AccentContainer),
            contentAlignment = Alignment.Center,
        ) { Text(number, style = Kd.Label.copy(color = Kd.Accent)) }
        Spacer(Modifier.size(20.dp))
        Text(text, style = Kd.Body, modifier = Modifier.weight(1f))
    }
}

/** 결과 예시 한 줄: 상태 기호 + 제목 + 설명 */
@Composable
private fun VerdictExample(verdict: ReactionVerdict) {
    val status = verdict.toStatus()
    Row(
        Modifier.fillMaxWidth().clip(Kd.ButtonShape).background(Kd.SurfaceRaised).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KdToneDot(status.tone, size = 48.dp)
        Spacer(Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(status.title, style = Kd.Label.copy(fontSize = 26.sp))
            Text(status.detail, style = Kd.BodyMuted.copy(fontSize = 22.sp, lineHeight = 30.sp))
        }
    }
}

/** 원이 '기다려요' → '지금!'으로 바뀌는 모습을 미리 보여 준다(설명 글을 읽지 않아도 알 수 있게). 남은 높이를 채운다 */
@Composable
private fun StimulusPreview(actionLabel: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(Kd.ButtonShape).background(Kd.SurfaceRaised).padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewCircle(active = false, actionLabel = actionLabel)
        Text("→", style = Kd.Headline.copy(color = Kd.InkMuted), modifier = Modifier.padding(horizontal = 40.dp))
        PreviewCircle(active = true, actionLabel = actionLabel)
    }
}

@Composable
private fun PreviewCircle(active: Boolean, actionLabel: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(168.dp)
                .clip(CircleShape)
                .background(if (active) Kd.Good else Kd.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (active) "지금!" else "기다려요",
                color = if (active) Kd.InkOnAccent else Kd.InkMuted,
                fontSize = if (active) 36.sp else 26.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(if (active) actionLabel else "기다리기", style = Kd.BodyMuted)
    }
}

/** 평소 기록 모으기 진행(3회가 모이면 내 기록과 비교) */
@Composable
private fun RecordProgress(recordCount: Int) {
    val done = recordCount >= 3
    Column(
        Modifier.fillMaxWidth().clip(Kd.ButtonShape).background(Kd.SurfaceRaised).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("비교 기준", style = Kd.Eyebrow)
        Text(
            if (done) "평소 기록 ${recordCount}회와 비교해요" else "평소 기록을 모으는 중이에요 (${recordCount}/3회)",
            style = Kd.Title,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(3) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(CircleShape)
                        .background(if (i < recordCount) Kd.Accent else Kd.SurfaceHigh),
                )
            }
        }
        if (!done) Text("3회가 모이기 전에는 일반 기준과 비교해요", style = Kd.BodyMuted)
    }
}

@Composable
private fun RunningView(state: ReactionUiState, isReplay: Boolean, onTap: () -> Unit, onStop: () -> Unit) {
    val brake = state.input == ReactionInput.BRAKE
    // 터치 입력: 화면 전체가 누르는 곳(손이 떨려도 원을 정확히 누를 필요가 없게). 브레이크 입력: 터치를 받지 않는다
    val touchArea = if (brake) Modifier else Modifier.pointerInput(Unit) { detectTapGestures(onPress = { onTap() }) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Kd.Background)
            .then(touchArea)
            .testTag("kd-reaction-running"),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val color = if (state.stimulusVisible) Kd.Good else Kd.SurfaceRaised
            Box(
                Modifier
                    .size(360.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.stimulusVisible) "지금!" else "기다려요",
                    fontSize = 56.sp,
                    color = if (state.stimulusVisible) Kd.InkOnAccent else Kd.InkMuted,
                    style = Kd.Display,
                )
            }
            Spacer(Modifier.height(40.dp))
            val message = if (brake && state.pedalHeld && !state.stimulusVisible) "브레이크에서 발을 떼 주세요"
            else feedback(state.lastOutcome, brake)
            Text(message, style = Kd.Title, textAlign = TextAlign.Center)
        }
        // 남은 시간 막대
        val progress = if (state.totalMs == 0L) 0f else state.remainingMs.toFloat() / state.totalMs
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth(progress).height(12.dp).background(Kd.Accent))
            Row(
                Modifier.fillMaxWidth().padding(Kd.ScreenPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "제때 ${state.validCount}/${state.targetValid}회 · 최대 ${(state.remainingMs + 999) / 1000}초",
                    style = Kd.Title,
                    modifier = Modifier.weight(1f),
                )
                if (isReplay) KdTag("시연 모드", modifier = Modifier.padding(end = 24.dp))
                KdButton("그만하기", onClick = onStop, style = KdButtonStyle.SECONDARY, minHeight = 80.dp)
            }
        }
    }
}

private fun feedback(outcome: TrialOutcome?, brake: Boolean): String {
    val act = if (brake) "브레이크를 밟아" else "눌러"
    return when (outcome) {
        TrialOutcome.VALID -> "좋아요"
        TrialOutcome.FALSE_START -> "조금 일렀어요. 초록색이 되면 $act 주세요"
        TrialOutcome.TIMEOUT -> "괜찮아요. 다음 원을 기다려 주세요"
        null -> "원이 초록색으로 바뀌면 $act 주세요"
    }
}

@Composable
private fun ResultView(state: ReactionUiState, isReplay: Boolean, onRetry: () -> Unit, onDone: () -> Unit) {
    val result = state.result
    KdScreen(testTag = "kd-reaction-result") {
        KdTopBar("컨디션 체크 결과", onBack = onDone) { if (isReplay) KdTag("시연 모드") }
        if (result == null) {
            KdStatusBadge(KdTone.UNKNOWN, "체크가 중간에 멈췄어요", large = true)
            Text("기어가 바뀌거나 차가 움직이면 체크를 멈춰요. 정차한 뒤 다시 해 주세요.", style = Kd.Body)
        } else {
            val status = result.verdict.toStatus()
            val compared = result.verdict != ReactionVerdict.INCONCLUSIVE
            KdCard(modifier = Modifier.fillMaxWidth().weight(1f), borderColor = status.tone.color) {
                Text("오늘 컨디션", style = Kd.Eyebrow)
                KdStatusBadge(status.tone, status.title, large = true)
                Text(result.message, style = Kd.Body)
                if (compared && result.isCalibrating) {
                    Text("평소 기록이 3회 모이기 전이라 일반 기준과 비교했어요", style = Kd.BodyMuted)
                }
            }
            val s = result.summary
            Row(horizontalArrangement = Arrangement.spacedBy(Kd.Gap), modifier = Modifier.fillMaxWidth()) {
                StatCard(s.medianReactionMs?.let { "${it}ms" } ?: "-", "반응 속도(가운데 값)", Modifier.weight(1f))
                StatCard("${s.validCount}회", "제때 반응한 횟수", Modifier.weight(1f))
                StatCard("${s.slowCount}회", "느렸거나 놓친 횟수", Modifier.weight(1f))
            }
            Text(
                if (compared) "결과는 다음 주행의 피로 판단에 참고하고, 내 기록에 남겨요."
                else "이번 결과는 피로 판단과 내 기록에 쓰지 않아요.",
                style = Kd.BodyMuted,
            )
        }
        if (result == null) Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(Kd.Gap), modifier = Modifier.fillMaxWidth()) {
            KdButton("다시 하기", onClick = onRetry, style = KdButtonStyle.SECONDARY, modifier = Modifier.weight(1f))
            KdButton("확인", onClick = onDone, modifier = Modifier.weight(1f).testTag("kd-reaction-done"))
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(Kd.CardShape)
            .background(Kd.Surface)
            .padding(32.dp),
    ) { KdStat(value, label) }
}
