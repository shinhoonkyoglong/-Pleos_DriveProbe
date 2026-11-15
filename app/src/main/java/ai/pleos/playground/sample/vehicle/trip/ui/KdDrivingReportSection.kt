package ai.pleos.playground.sample.vehicle.trip.ui

import ai.pleos.playground.sample.vehicle.trip.SummaryTone
import ai.pleos.playground.sample.vehicle.trip.TripModule
import ai.pleos.playground.sample.vehicle.trip.report.ConditionRow
import ai.pleos.playground.sample.vehicle.trip.report.ConditionStatus
import ai.pleos.playground.sample.vehicle.trip.report.DrivingReport
import ai.pleos.playground.sample.vehicle.trip.report.FatigueTrend
import ai.pleos.playground.sample.vehicle.trip.report.ReportCondition
import ai.pleos.playground.sample.vehicle.trip.report.charts
import ai.pleos.playground.sample.vehicle.ui.kd.Kd
import ai.pleos.playground.sample.vehicle.ui.kd.KdCard
import ai.pleos.playground.sample.vehicle.ui.kd.KdDivider
import ai.pleos.playground.sample.vehicle.ui.kd.KdStat
import ai.pleos.playground.sample.vehicle.ui.kd.KdStatusBadge
import ai.pleos.playground.sample.vehicle.ui.kd.KdTag
import ai.pleos.playground.sample.vehicle.ui.kd.KdTone
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/*
 * '계속운전' 앱 화면(ui/kd, 팀원 디자인)에 넣는 5번 운전 리포트 — **데이터는 5번(TripModule), 모양은 팀원 디자인(Kd*)**.
 *
 * 팀원 화면(ReportScreen)에서: KdDrivingReportSection()
 * 내용: 한 줄 변화 → 숫자 4개 → 주요 변화 → 주별 그래프 3개(누르면 그 주 기록) → 운전 조건 → 안내문.
 * 판정 등급은 매기지 않는다(조건부 면허는 시행 전이라 "조건 해제 근거"로 내세우지 않음). 계산은 report/DrivingReport.kt
 */

/** 최근 [weeks]주 운전 리포트. 스스로 스크롤하지 않는다(호스트 KdScreen이 스크롤) */
@Composable
fun KdDrivingReportSection(modifier: Modifier = Modifier, weeks: Int = 8) {
    val flow = remember(weeks) { TripModule.observeDrivingReport(weeks) }
    val report by flow.collectAsStateWithLifecycle(initialValue = null)
    val r = report
    if (r == null) {
        KdCard(modifier.fillMaxWidth()) { Text("주행 기록을 불러오는 중이에요", style = Kd.BodyMuted) }
    } else {
        KdDrivingReportContent(r, modifier)
    }
}

/** 운전 리포트 한 장(값만 받음) */
@Composable
fun KdDrivingReportContent(report: DrivingReport, modifier: Modifier = Modifier) {
    val labels = remember(report) { report.weekly.map { it.label() } }
    val charts = remember(report) { report.charts() }
    var selected by rememberSaveable(report.weeks) { mutableStateOf(report.weeks - 1) }
    val week = report.weekly.getOrNull(selected)
    val t = report.total

    Column(modifier = modifier.testTag("kd-driving-report"), verticalArrangement = Arrangement.spacedBy(Kd.Gap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${report.periodText()} · ${report.weeks}주", style = Kd.BodyMuted, modifier = Modifier.weight(1f))
            if (report.isDemo && SHOW_DEMO_RECORD_TAGS) KdTag("시연 기록", color = Kd.Unknown)
            if (report.hasMock) {
                Spacer(Modifier.width(12.dp))
                KdTag("임시값 포함", color = Kd.Unknown)
            }
        }

        KdCard(Modifier.fillMaxWidth()) {
            KdStatusBadge(report.headlineTone.kd(), report.headline, modifier = Modifier.fillMaxWidth())
        }

        // 숫자 4개
        Row(horizontalArrangement = Arrangement.spacedBy(Kd.Gap), modifier = Modifier.fillMaxWidth()) {
            KdCard(Modifier.weight(1f)) { KdStat("${report.activeWeeks}/${report.weeks}주", "운전한 주") }
            KdCard(Modifier.weight(1f)) { KdStat("${t.tripCount}회", "운전 횟수") }
            KdCard(Modifier.weight(1f)) {
                val rate = t.complianceRate
                KdStat(
                    rate?.let { "${(it * 100).roundToInt()}%" } ?: "-",
                    if (t.complianceMock) "조건 준수율(임시)" else "조건 준수율",
                    tone = when {
                        rate == null -> null
                        rate >= 1f -> KdTone.GOOD
                        else -> KdTone.CAUTION
                    },
                )
            }
            KdCard(Modifier.weight(1f)) {
                val first = report.firstHalfAvg?.roundToInt()
                val second = report.secondHalfAvg?.roundToInt()
                KdStat(
                    second?.let { "${it}점" } ?: "-",
                    if (first != null) "평균 피로(앞 ${report.halfWeeks}주 ${first}점)" else "평균 피로 점수",
                    tone = when (report.fatigueTrend) {
                        FatigueTrend.IMPROVING, FatigueTrend.STEADY -> KdTone.GOOD
                        FatigueTrend.WORSENING -> KdTone.CAUTION
                        FatigueTrend.UNKNOWN -> null
                    },
                )
            }
        }

        if (report.findings.isNotEmpty()) {
            KdCard(Modifier.fillMaxWidth()) {
                Text("주요 변화", style = Kd.Title)
                report.findings.forEach { f ->
                    val tone = f.tone.kd()
                    Row(verticalAlignment = Alignment.Top) {
                        Text(tone.symbol, color = tone.color, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                        Text(f.text, style = Kd.Body, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        KdCard(Modifier.fillMaxWidth()) {
            Text("주별 그래프", style = Kd.Title)
            if (week != null) {
                Text(weekDetail(week), style = Kd.Body.copy(color = Kd.Accent))
                Text("막대나 점을 누르면 그 주의 기록이 위에 나와요.", style = Kd.BodyMuted)
            }
            charts.forEach { chart ->
                KdDivider()
                WeeklyChartView(chart, labels, selected, onSelect = { selected = it })
            }
        }

        KdCard(Modifier.fillMaxWidth()) {
            Text("운전 조건", style = Kd.Title)
            report.conditionRows.forEachIndexed { i, row ->
                if (i > 0) KdDivider()
                KdConditionRow(row, report.conditions?.radiusKm, report.weeks)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            report.dataNotes.forEach { Text("· $it", style = Kd.BodyMuted) }
            Text(DrivingReport.DISCLAIMER, style = Kd.BodyMuted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun KdConditionRow(row: ConditionRow, radiusKm: Double?, weeks: Int) {
    val name = row.condition.label +
        if (row.condition == ReportCondition.RADIUS && radiusKm != null) " (${"%.0f".format(radiusKm)}km)" else ""
    val (value, tone) = when (row.status) {
        ConditionStatus.KEPT -> "${weeks}주 동안 모두 지킴" to KdTone.GOOD
        ConditionStatus.VIOLATED -> "${row.judgedTrips}번 중 ${row.violations}번 못 지킴" to KdTone.CAUTION
        ConditionStatus.NOT_JUDGED -> "판정 안 함" to KdTone.UNKNOWN
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tone.symbol, color = tone.color, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
        Text(name, style = Kd.Body)
        if (row.isMock) {
            Spacer(Modifier.width(12.dp))
            KdTag("임시", color = Kd.Unknown)
        }
        Spacer(Modifier.weight(1f))
        Text(value, color = tone.color, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

private fun SummaryTone.kd(): KdTone = when (this) {
    SummaryTone.GOOD -> KdTone.GOOD
    SummaryTone.WARN -> KdTone.CAUTION
    SummaryTone.INFO -> KdTone.INFO
}
