package ai.pleos.playground.sample.vehicle.trip.ui

import ai.pleos.playground.sample.vehicle.trip.SummaryItem
import ai.pleos.playground.sample.vehicle.trip.SummaryKey
import ai.pleos.playground.sample.vehicle.trip.SummaryTone
import ai.pleos.playground.sample.vehicle.trip.TripSummaryContent.Companion.durationText
import ai.pleos.playground.sample.vehicle.trip.report.ConditionRow
import ai.pleos.playground.sample.vehicle.trip.report.DrivingReport
import ai.pleos.playground.sample.vehicle.trip.report.charts
import ai.pleos.playground.sample.vehicle.trip.report.ConditionStatus
import ai.pleos.playground.sample.vehicle.trip.report.ReportCondition
import ai.pleos.playground.sample.vehicle.trip.report.WeekRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/*
 * 운전 리포트 UI 조각 — **값만 받아서 그리는** 순수 Composable.
 * 규칙은 TripSummaryComponents.kt와 같다(스스로 스크롤 안 함, 호스트 테마, 상태색만 고정, "임시" 표시).
 * DB에서 읽어 그리는 버전은 TripReportSections.kt
 */

/**
 * 운전 리포트(여러 주) — 한 줄 변화 + 주요 변화 문장 + 주별 그래프 3개 + 운전 조건.
 * 그래프의 막대·점을 누르면 그 주의 기록이 위 칸에 나온다. 인쇄용은 DrivingReportHtml
 */
@Composable
fun DrivingReportView(report: DrivingReport, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val labels = remember(report) { report.weekly.map { it.label() } }
    val charts = remember(report) { report.charts() }
    var selected by rememberSaveable(report.weeks) { mutableStateOf(report.weeks - 1) }
    val week = report.weekly.getOrNull(selected)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TitleRow(if (showTitle) "운전 리포트" else null, "${report.periodText()} · ${report.weeks}주", report.isDemo)
        HeadlineBox(report.headline, report.headlineTone)

        if (report.findings.isNotEmpty()) {
            Card {
                SectionLabel("주요 변화")
                report.findings.forEach { f ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 10.dp)) { ToneDot(f.tone) }
                        Spacer(Modifier.width(12.dp))
                        Text(f.text, fontSize = 21.sp, lineHeight = 30.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text(f.tone.label(), fontSize = 18.sp, color = f.tone.color())
                    }
                }
            }
        }

        Card {
            SectionLabel("주별 그래프")
            if (week != null) {
                Surface(shape = RoundedCornerShape(12.dp), color = InfoBg, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        weekDetail(week),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        fontSize = 20.sp, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("막대나 점을 누르면 그 주의 기록이 위에 나와요.", fontSize = 17.sp, color = InfoColor)
            }
            charts.forEach { chart ->
                Spacer(Modifier.height(20.dp))
                WeeklyChartView(chart, labels, selected, onSelect = { selected = it })
            }
        }

        Card {
            SectionLabel("운전 조건")
            report.conditionRows.forEach { ConditionRowView(it, report.conditions?.radiusKm, report.weeks) }
        }

        report.dataNotes.forEach { FootNote("· $it") }
        FootNote(DrivingReport.DISCLAIMER)
    }
}

/** 누른 주의 기록 한 줄 */
internal fun weekDetail(week: WeekRow): String {
    val s = week.stats
    if (s.tripCount == 0) return "${week.rangeLabel()} · 운전 기록 없음"
    return buildString {
        append(week.rangeLabel()).append(" · ").append(s.tripCount).append("번 운전, ").append(durationText(s.totalDurationMs))
        append(" · 평균 피로 ").append(s.avgFatigueScore?.let { "${it.roundToInt()}점" } ?: "판정 없음")
        append(" · 피로 신호 ").append(s.fatigueSignalTrips).append("번")
        append(" · 조건 못 지킴 ").append(s.violatedTrips).append("번")
    }
}

@Composable
private fun ConditionRowView(row: ConditionRow, radiusKm: Double?, weeks: Int) {
    val name = row.condition.label +
        if (row.condition == ReportCondition.RADIUS && radiusKm != null) " (${"%.0f".format(radiusKm)}km)" else ""
    val (value, tone) = when (row.status) {
        ConditionStatus.KEPT -> "${weeks}주 동안 모두 지킴" to SummaryTone.GOOD
        ConditionStatus.VIOLATED -> "${row.violations}번 못 지킴" to SummaryTone.WARN
        ConditionStatus.NOT_JUDGED -> "판정 안 함" to SummaryTone.INFO
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ToneDot(tone)
        Spacer(Modifier.width(12.dp))
        Text(name, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface)
        if (row.isMock) {
            Spacer(Modifier.width(12.dp))
            Chip("임시", InfoColor, InfoBg)
        }
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = tone.color())
    }
}

@Composable
private fun TitleRow(title: String?, subtitle: String, isDemo: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (title != null) {
            Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.width(16.dp))
        }
        Text(subtitle, fontSize = 22.sp, color = InfoColor)
        if (isDemo) {
            Spacer(Modifier.width(16.dp))
            Chip("시연 기록", InfoColor, InfoBg)
        }
    }
}

@Composable
private fun HeadlineBox(text: String, tone: SummaryTone) {
    Surface(shape = RoundedCornerShape(20.dp), color = tone.background(), modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
            fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = tone.color(),
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) { content() }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun FootNote(text: String) {
    Text(text, fontSize = 18.sp, lineHeight = 26.sp, color = InfoColor)
}
