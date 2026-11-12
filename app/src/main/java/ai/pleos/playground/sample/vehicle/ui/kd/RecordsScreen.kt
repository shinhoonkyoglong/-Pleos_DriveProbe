package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.data.local.entity.InterventionHistoryEntity
import ai.pleos.playground.sample.vehicle.data.local.entity.ReactionScoreEntity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 차트 점 하나 */
data class TrendPoint(val label: String, val valueMs: Long)

/**
 * 주행 후 '내 기록'. 기획서 3장 주행 후: 컨디션 추이 + 가족 안심 요약 + 조건 해제 리포트로 가는 입구.
 *
 * 실제 데이터: 반응 체크(reaction_scores), 개입 이력(intervention_histories).
 * 아직 저장하는 곳이 없는 값(주행 횟수·조건 준수율)은 [KdDemoData] 예시값을 쓰고 화면에 '예시'라고 표시한다.
 */
@Composable
fun RecordsScreen(state: KdUiState, onOpen: (KdPage) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val weekAgo = System.currentTimeMillis() - WEEK_MS
    val weekInterventions = state.interventions.filter { it.timestamp >= weekAgo }
    val restSuggestions = weekInterventions.count { it.interventionLevel == 3 }
    val trend = reactionTrend(state.reactionHistory)

    KdScreen(scrollable = true, testTag = "kd-records") {
        KdTopBar("내 기록", onBack = onBack)

        Row(horizontalArrangement = Arrangement.spacedBy(Kd.Gap), modifier = Modifier.fillMaxWidth()) {
            KdCard(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("이번 주", style = Kd.Title, modifier = Modifier.weight(1f))
                    KdTag("운전 횟수·준수율은 예시")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Kd.Gap)) {
                    KdStat("${KdDemoData.weekTrips}회", "운전", Modifier.weight(1f))
                    KdStat("${KdDemoData.weekCompliancePercent}%", "조건 준수", Modifier.weight(1f), tone = KdTone.GOOD)
                    KdStat("${restSuggestions}회", "휴식 권고", Modifier.weight(1f))
                    KdStat("${weekInterventions.size}회", "도움 받은 횟수", Modifier.weight(1f))
                }
            }
        }

        KdCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("출발 전 반응 속도", style = Kd.Title)
                    Text("낮을수록 빨라요 · 최근 ${trend.points.size}회", style = Kd.BodyMuted)
                }
                if (trend.isDemo) KdTag("예시 데이터 — 체크를 2회 이상 하면 내 기록으로 바뀌어요")
            }
            ReactionTrendChart(trend.points, Modifier.fillMaxWidth().height(320.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Kd.Gap), modifier = Modifier.fillMaxWidth()) {
            KdButton(
                "가족 안심 요약",
                subText = "가족에게 보내는 주간 요약 미리보기",
                onClick = { onOpen(KdPage.FAMILY_SUMMARY) },
                modifier = Modifier.weight(1f).testTag("kd-records-family"),
                minHeight = 128.dp,
            )
            KdButton(
                "조건 해제 리포트",
                subText = "재평가 때 낼 수 있는 요약",
                onClick = { onOpen(KdPage.REPORT) },
                style = KdButtonStyle.SECONDARY,
                modifier = Modifier.weight(1f).testTag("kd-records-report"),
                minHeight = 128.dp,
            )
        }

        KdSectionTitle("최근 도움 기록")
        if (state.interventions.isEmpty()) {
            Text("아직 주행 중 도움을 받은 적이 없어요", style = Kd.BodyMuted)
        } else {
            KdCard(Modifier.fillMaxWidth()) {
                state.interventions.take(6).forEachIndexed { i, h ->
                    if (i > 0) KdDivider()
                    InterventionRow(h)
                }
            }
        }
    }
}

@Composable
private fun InterventionRow(h: InterventionHistoryEntity) {
    val (tone, title) = when (h.interventionLevel) {
        1 -> KdTone.CAUTION to "환기"
        2 -> KdTone.SERIOUS to "안전 확인 질문"
        3 -> KdTone.DANGER to "휴식 권고"
        else -> KdTone.INFO to "기타"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        KdStatusBadge(tone, title, modifier = Modifier.weight(1f))
        Text(formatDateTime(h.timestamp), style = Kd.BodyMuted)
    }
}

data class ReactionTrend(val points: List<TrendPoint>, val isDemo: Boolean)

fun reactionTrend(history: List<ReactionScoreEntity>): ReactionTrend {
    val real = history.sortedBy { it.timestamp }.takeLast(10)
        .map { TrendPoint(formatShortDate(it.timestamp), it.simpleReactionTimeMs) }
    return if (real.size >= 2) ReactionTrend(real, isDemo = false) else ReactionTrend(KdDemoData.reactionTrend, isDemo = true)
}

/**
 * 한 줄 선 차트(계열 하나 → 범례 없이 제목이 이름). 선 2dp, 점 10dp, 옅은 가로 눈금 3줄,
 * 처음·마지막 값만 직접 표시. 값 글자는 계열 색이 아니라 글자 색으로 쓴다.
 */
@Composable
fun ReactionTrendChart(points: List<TrendPoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) return
    val min = (points.minOf { it.valueMs } - 40).coerceAtLeast(0)
    val max = points.maxOf { it.valueMs } + 40
    Column(modifier) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.fillMaxHeight(),verticalArrangement = Arrangement.SpaceBetween) {
                Text("${max}ms", style = Kd.BodyMuted.copy(fontSize = Kd.BodyMuted.fontSize * 0.8f))
                Text("${min}ms", style = Kd.BodyMuted.copy(fontSize = Kd.BodyMuted.fontSize * 0.8f))
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val padX = 24.dp.toPx()
                val padY = 20.dp.toPx()
                val w = size.width - padX * 2
                val h = size.height - padY * 2
                fun x(i: Int) = padX + if (points.size == 1) w / 2 else w * i / (points.size - 1)
                fun y(v: Long) = padY + h * (1f - (v - min).toFloat() / (max - min).coerceAtLeast(1))
                // 눈금(옅게)
                for (k in 0..2) {
                    val gy = padY + h * k / 2
                    drawLine(
                        Kd.Outline, Offset(padX, gy), Offset(size.width - padX, gy), strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                    )
                }
                for (i in 1 until points.size) {
                    drawLine(
                        Kd.Accent, Offset(x(i - 1), y(points[i - 1].valueMs)), Offset(x(i), y(points[i].valueMs)),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
                points.forEachIndexed { i, p ->
                    val c = Offset(x(i), y(p.valueMs))
                    drawCircle(Kd.Surface, radius = 7.dp.toPx(), center = c) // 겹침 방지 바탕 고리
                    drawCircle(Kd.Accent, radius = 5.dp.toPx(), center = c)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(points.first().label + "  " + points.first().valueMs + "ms", style = Kd.BodyMuted, modifier = Modifier.weight(1f))
            Text("최근 " + points.last().label + "  " + points.last().valueMs + "ms", style = Kd.Label)
        }
    }
}

private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
