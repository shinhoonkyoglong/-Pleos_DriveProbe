package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.trip.ui.KdDrivingReportSection
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 아직 저장·서버가 없는 값의 예시. 1차 제출(11/15)은 목업, 2차 PT(12/3)에 실제 데이터로 바꾼다(기획서 5장 개발 범위).
 * 화면에는 늘 '예시' 표시를 붙인다.
 */
object KdDemoData {
    const val weekTrips = 5
    const val weekCompliancePercent = 100
    const val weekRestSuggestions = 1
    const val driverName = "김순자"

    val reactionTrend = listOf(
        TrendPoint("11/3", 372), TrendPoint("11/4", 358), TrendPoint("11/5", 365),
        TrendPoint("11/6", 349), TrendPoint("11/7", 361), TrendPoint("11/9", 344),
        TrendPoint("11/10", 352),
    )

    data class MonthRow(val month: String, val trips: Int, val compliance: Int, val reactionMs: Int, val warnings: Int)

    val months = listOf(
        MonthRow("8월", 18, 94, 378, 3),
        MonthRow("9월", 21, 97, 366, 2),
        MonthRow("10월", 22, 100, 359, 1),
        MonthRow("11월", 9, 100, 352, 0),
    )
}

/** 가족 안심 요약: 가족 휴대폰에 가는 주간 메시지 미리보기. 위치·경로는 넣지 않는다 */
@Composable
fun FamilySummaryScreen(state: KdUiState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val rest = state.interventions.count {
        it.interventionLevel == 3 && System.currentTimeMillis() - it.timestamp < 7L * 24 * 3600 * 1000
    }
    KdScreen(scrollable = true, testTag = "kd-family") {
        KdTopBar("가족 안심 요약", onBack = onBack, backLabel = "← 내 기록") { KdTag("목업 · 서버 연동 전") }
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp), modifier = Modifier.fillMaxWidth()) {
            // 왼쪽: 가족이 받는 화면(휴대폰 모양)
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .clip(RoundedCornerShape(48.dp))
                    .background(Kd.SurfaceRaised)
                    .border(6.dp, Kd.Outline, RoundedCornerShape(48.dp))
                    .padding(36.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("계속운전 · 주간 요약", style = Kd.BodyMuted)
                Text("${KdDemoData.driverName} 님의 이번 주", style = Kd.Title)
                KdStatusBadge(KdTone.GOOD, "조건 준수, 컨디션 양호")
                KdDivider()
                PhoneRow("운전", "${KdDemoData.weekTrips}회")
                PhoneRow("조건 준수", "${KdDemoData.weekCompliancePercent}%")
                PhoneRow("휴식 권고", "${maxOf(rest, KdDemoData.weekRestSuggestions)}회")
                PhoneRow("반응 속도", "평소와 비슷")
                KdDivider()
                Text("위치와 경로는 공유하지 않아요", style = Kd.BodyMuted)
            }
            // 오른쪽: 운전자가 정하는 공유 범위
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Kd.Gap)) {
                KdCard(Modifier.fillMaxWidth()) {
                    Text("무엇을 보내나요?", style = Kd.Title)
                    ShareRow("운전 횟수와 조건 준수율", shared = true)
                    ShareRow("휴식 권고 횟수", shared = true)
                    ShareRow("반응 속도 변화(좋음·느려짐만)", shared = true)
                    ShareRow("위치·경로·목적지", shared = false)
                }
                KdCard(Modifier.fillMaxWidth()) {
                    Text("언제 보내나요?", style = Kd.Title)
                    Text("매주 월요일 아침 9시, 한 번만 보내요", style = Kd.Body)
                    Text("보낼 내용은 운전하시는 분이 정해요", style = Kd.BodyMuted)
                }
            }
        }
    }
}

@Composable
private fun PhoneRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = Kd.Body.copy(color = Kd.InkMuted), modifier = Modifier.weight(1f))
        Text(value, style = Kd.Body.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun ShareRow(label: String, shared: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (shared) "✓" else "✕",
            color = if (shared) Kd.Good else Kd.Danger,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(48.dp),
        )
        Text(label, style = Kd.Body, modifier = Modifier.weight(1f))
        Text(if (shared) "보냄" else "안 보냄", style = Kd.BodyMuted)
    }
}

/**
 * 운전 리포트: 최근 8주 운전을 주별 그래프와 변화 문장으로 보여준다.
 * [5번] 리포트 본문은 trip/ui/KdDrivingReportSection.kt(주행 기록 데이터). 조건부 면허는 시행 전이라 "조건 해제 근거"로 내세우지 않는다.
 */
@Composable
fun ReportScreen(state: KdUiState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val trend = reactionTrend(state.reactionHistory)
    KdScreen(scrollable = true, testTag = "kd-report") {
        KdTopBar("운전 리포트", onBack = onBack, backLabel = "← 내 기록")

        KdDrivingReportSection() // [5번] 8주 운전 리포트(그래프·변화 문장·운전 조건)

        KdCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("출발 전 반응 속도 추이", style = Kd.Title, modifier = Modifier.weight(1f))
                if (trend.isDemo) KdTag("예시 데이터")
            }
            ReactionTrendChart(trend.points, Modifier.fillMaxWidth().height(260.dp))
        }

        KdButton(
            "PDF로 저장 (준비 중)",
            onClick = {},
            enabled = false,
            style = KdButtonStyle.SECONDARY,
            modifier = Modifier.fillMaxWidth().testTag("kd-report-export"),
        )
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        cells.forEach {
            Text(
                it,
                style = if (header) Kd.Label.copy(color = Kd.InkMuted) else Kd.Body,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
