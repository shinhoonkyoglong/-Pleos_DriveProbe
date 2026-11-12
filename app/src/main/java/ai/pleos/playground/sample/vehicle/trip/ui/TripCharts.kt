package ai.pleos.playground.sample.vehicle.trip.ui

import ai.pleos.playground.sample.vehicle.trip.report.ChartKind
import ai.pleos.playground.sample.vehicle.trip.report.WeeklyChart
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 주별 그래프 하나(막대 또는 선) — 값만 받아 그리는 순수 Composable.
 *
 * 규칙(데이터 시각화 기준):
 * - y축은 하나, 눈금 3개(0·가운데·맨 위), 눈금선은 흐리게
 * - 막대는 24dp 이하, 위쪽만 4dp 둥글게, 기준선에서 자람. 묶음 막대 사이 2dp 틈
 * - 선은 2.5dp, 점은 10dp에 배경색 테두리 2dp. 값이 없는 주에서 끊는다
 * - 값 글자는 계열마다 마지막 주와 누른 주에만(모든 점에 숫자를 달지 않는다)
 * - 계열이 2개 이상이면 범례를 둔다. 글자는 계열 색이 아니라 기본 글자색
 * - 막대·점(그 주의 칸 어디든)을 누르면 [onSelect](주 번호) → 그 주의 기록을 보여준다(마우스가 없는 차량 화면의 툴팁 대신)
 */

/** 검증된 범주형 팔레트 1·2번(밝은/어두운 배경용). 순서로만 쓴다 */
private val SeriesLight = listOf(Color(0xFF2A78D6), Color(0xFFEB6834))
private val SeriesDark = listOf(Color(0xFF3987E5), Color(0xFFD95926))

@Composable
internal fun WeeklyChartView(
    chart: WeeklyChart,
    labels: List<String>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) SeriesDark else SeriesLight
    val surface = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val tickStyle = TextStyle(fontSize = 16.sp, color = muted)
    val valueStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
    val n = labels.size

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(chart.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = ink)
            if (chart.series.size >= 2) {
                Spacer(Modifier.weight(1f))
                chart.series.forEach { s ->
                    Box(Modifier.size(14.dp).background(colors[s.slot], RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(s.name, fontSize = 18.sp, color = ink)
                    Spacer(Modifier.width(20.dp))
                }
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .semantics { contentDescription = describe(chart, labels) }
                .pointerInput(n) {
                    detectTapGestures { p ->
                        val left = 56.dp.toPx()
                        val slot = (size.width - left - 8.dp.toPx()) / n
                        if (p.x >= left) onSelect(((p.x - left) / slot).toInt().coerceIn(0, n - 1))
                    }
                },
        ) {
            val left = 56.dp.toPx()
            val right = 8.dp.toPx()
            val top = 28.dp.toPx()
            val bottom = 30.dp.toPx()
            val plotW = size.width - left - right
            val plotH = size.height - top - bottom
            val slot = plotW / n
            fun x(i: Int) = left + slot * (i + 0.5f)
            fun y(v: Float) = top + plotH * (1f - (v / chart.yMax).coerceIn(0f, 1f))

            // 누른 주 강조(뒤에 옅게)
            selected?.let { drawRect(ink.copy(alpha = 0.06f), Offset(left + slot * it, top - 20.dp.toPx()), Size(slot, plotH + 20.dp.toPx())) }

            // 눈금선·눈금 글자
            chart.ticks.forEach { t ->
                val ty = y(t)
                drawLine(
                    if (t == 0f) muted.copy(alpha = 0.5f) else ink.copy(alpha = 0.10f),
                    Offset(left, ty), Offset(size.width - right, ty), strokeWidth = 1.dp.toPx(),
                )
                val layout = measurer.measure("${t.toInt()}", tickStyle)
                drawText(layout, topLeft = Offset(left - 10.dp.toPx() - layout.size.width, ty - layout.size.height / 2f))
            }
            labels.forEachIndexed { i, l ->
                val layout = measurer.measure(l, tickStyle)
                drawText(layout, topLeft = Offset(x(i) - layout.size.width / 2f, size.height - layout.size.height))
            }

            val k = chart.series.size
            val barW = minOf(24.dp.toPx(), slot * 0.6f / k)
            val gap = 2.dp.toPx()
            val groupW = k * barW + (k - 1) * gap
            fun barX(si: Int, i: Int) = x(i) - groupW / 2f + si * (barW + gap)

            chart.series.forEachIndexed { si, s ->
                val color = colors[s.slot]
                when (chart.kind) {
                    ChartKind.BAR -> s.values.forEachIndexed { i, v ->
                        if (v == null || v <= 0f) return@forEachIndexed
                        roundTopBar(barX(si, i), y(v), barW, top + plotH - y(v), color)
                    }
                    ChartKind.LINE -> {
                        val path = Path()
                        var drawing = false
                        s.values.forEachIndexed { i, v ->
                            if (v == null) { drawing = false; return@forEachIndexed }
                            if (drawing) path.lineTo(x(i), y(v)) else path.moveTo(x(i), y(v))
                            drawing = true
                        }
                        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), join = StrokeJoin.Round))
                        s.values.forEachIndexed { i, v ->
                            if (v == null) return@forEachIndexed
                            drawCircle(surface, radius = 7.dp.toPx(), center = Offset(x(i), y(v)))
                            drawCircle(color, radius = 5.dp.toPx(), center = Offset(x(i), y(v)))
                        }
                    }
                }
            }

            // 값 글자: 계열마다 마지막 주 + 누른 주
            chart.series.forEachIndexed { si, s ->
                val last = s.values.indexOfLast { it != null }
                listOfNotNull(last.takeIf { it >= 0 }, selected).distinct().forEach { i ->
                    val v = s.values.getOrNull(i) ?: return@forEach
                    val cx = if (chart.kind == ChartKind.BAR) barX(si, i) + barW / 2f else x(i)
                    // 묶음 막대는 막대가 좁아 단위 없이 숫자만(단위는 범례·제목으로 안다)
                    val text = if (chart.kind == ChartKind.BAR && k > 1) "${v.toInt()}" else chart.format(v)
                    val layout = measurer.measure(text, valueStyle)
                    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, y(v) - layout.size.height - 4.dp.toPx()))
                }
            }
        }
        chart.note?.let { Text(it, fontSize = 17.sp, lineHeight = 24.sp, color = muted) }
    }
}

/** 위쪽만 둥근 막대(바닥은 기준선에 평평하게) */
private fun DrawScope.roundTopBar(x: Float, y: Float, w: Float, h: Float, color: Color) {
    if (h <= 0f) return
    val r = minOf(4.dp.toPx(), w / 2f, h)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(x, y, x + w, y + h),
                topLeft = CornerRadius(r), topRight = CornerRadius(r),
                bottomRight = CornerRadius.Zero, bottomLeft = CornerRadius.Zero,
            ),
        )
    }
    drawPath(path, color)
}

/** 화면 읽기 프로그램용 설명(그래프 숫자를 말로) */
private fun describe(chart: WeeklyChart, labels: List<String>): String = buildString {
    append(chart.title).append(". ")
    chart.series.forEach { s ->
        if (chart.series.size > 1) append(s.name).append(": ")
        append(s.values.mapIndexed { i, v -> "${labels[i]} ${v?.let(chart::format) ?: "없음"}" }.joinToString(", "))
        append(". ")
    }
}
