package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.data.local.entity.InterventionHistoryEntity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 주행 중 화면. Pleos 주행 중 주의 분산 가이드에 맞춰:
 * - 누르는 곳 없음(조작은 모두 음성 — 3번·4번 파트의 TTS/STT 개입)
 * - 2초 안에 읽히는 정보만: 컨디션 단계(색 + 기호 + 한 줄), 연속 주행 시간
 * - 글자 최소 32sp, 움직이는 효과 없음
 */
@Composable
fun DrivingScreen(state: KdUiState) {
    val vehicle = state.vehicle
    val condition = vehicle.condition
    val status = condition.level.toStatus()
    // 이번 주행에서 마지막으로 일어난 개입(있으면 한 줄로)
    val lastIntervention = state.interventions.firstOrNull()?.takeIf {
        System.currentTimeMillis() - it.timestamp < RECENT_INTERVENTION_MS
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Kd.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(Kd.ScreenPadding)
            .testTag("kd-driving"),
    ) {
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("주행 중", style = Kd.Title.copy(color = Kd.InkMuted), modifier = Modifier.weight(1f))
            if (vehicle.isReplay) KdTag("시연 모드")
        }

        Row(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            LevelGauge(level = condition.level, modifier = Modifier.size(380.dp))
            Spacer(Modifier.size(64.dp))
            Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.weight(1f, fill = false)) {
                Text(status.title, style = Kd.Display.copy(color = Kd.Ink))
                Text(status.detail, style = Kd.Headline.copy(color = Kd.InkMuted, fontSize = 36.sp))
                // 팀 규칙: 이유(reasons)는 MILD 이상일 때만. NORMAL 에서는 작은 변화도 문구가 떠서 불안하게 만든다
                val reason = condition.reasons.firstOrNull()
                if (condition.level >= FatigueLevel.MILD && reason != null) {
                    Text(reason.message, style = Kd.Title.copy(color = status.tone.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Text("연속 운전 ${formatMinutes(condition.drivingMinutes)}", style = Kd.Title)
                if (condition.isCalibrating) {
                    Text("평소 기록을 모으는 중이에요", style = Kd.BodyMuted.copy(fontSize = 28.sp))
                }
            }
        }

        if (lastIntervention != null) {
            Text(
                interventionLine(lastIntervention),
                style = Kd.Title.copy(color = Kd.InkMuted),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )
        }
    }
}

/** 네 칸 반원 게이지: 지금 단계까지 채우고, 가운데 큰 기호. 색만으로 구분하지 않도록 기호를 같이 둔다 */
@Composable
private fun LevelGauge(level: FatigueLevel, modifier: Modifier = Modifier) {
    val levels = FatigueLevel.entries
    val tone = level.toStatus().tone
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 36.dp.toPx()
            val gap = 6f // 칸 사이 간격(도)
            val sweepEach = (270f - gap * (levels.size - 1)) / levels.size
            val inset = stroke / 2
            levels.forEachIndexed { i, l ->
                val filled = l.ordinal <= level.ordinal
                drawArc(
                    color = if (filled) tone.color else Kd.SurfaceRaised,
                    startAngle = 135f + i * (sweepEach + gap),
                    sweepAngle = sweepEach,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
            }
        }
        Text(tone.symbol, color = tone.color, fontSize = 140.sp, style = Kd.Display)
    }
}

private fun interventionLine(h: InterventionHistoryEntity): String = when (h.interventionLevel) {
    1 -> "환기를 해 드렸어요 · 온도 조금 낮춤, 바람 세게"
    2 -> "안전 확인 질문을 드렸어요"
    3 -> "쉼터 안내를 여쭤봤어요"
    else -> "도움을 드렸어요"
}

private const val RECENT_INTERVENTION_MS = 5 * 60 * 1000L
