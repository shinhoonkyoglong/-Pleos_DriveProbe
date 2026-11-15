package ai.pleos.playground.sample.vehicle.ui.kd

import ai.pleos.playground.sample.vehicle.trip.ui.KdDrivingReportSection
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
        TrendPoint("11월 3일", 372), TrendPoint("11월 4일", 358), TrendPoint("11월 5일", 365),
        TrendPoint("11월 6일", 349), TrendPoint("11월 7일", 361), TrendPoint("11월 9일", 344),
        TrendPoint("11월 10일", 352),
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
    // 화면 높이를 꽉 채운다: 왼쪽 휴대폰 미리보기와 오른쪽 카드 묶음이 남은 높이를 나눠 쓴다(이전에는 아래 절반이 비었다)
    KdScreen(testTag = "kd-family") {
        KdTopBar("가족 안심 요약", onBack = onBack, backLabel = "← 내 기록") { KdTag("목업 · 서버 연동 전") }
        Row(horizontalArrangement = Arrangement.spacedBy(Kd.Gap), modifier = Modifier.fillMaxWidth().weight(1f)) {
            // 왼쪽: 가족이 받는 화면(휴대폰 모양)
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(48.dp))
                    .background(Kd.Surface)
                    .border(8.dp, Kd.SurfaceHigh, RoundedCornerShape(48.dp))
                    .padding(horizontal = 40.dp, vertical = 36.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(120.dp).height(8.dp).clip(RoundedCornerShape(50)).background(Kd.SurfaceHigh))
                Text("계속운전 · 주간 요약", style = Kd.Eyebrow)
                Text("${KdDemoData.driverName} 님의 이번 주", style = Kd.Title)
                KdStatusBadge(KdTone.GOOD, "조건 준수, 컨디션 양호")
                // 숫자 4개를 2×2 칸으로 크게(휴대폰 화면 높이를 채운다)
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PhoneStat("운전", "${KdDemoData.weekTrips}회", Modifier.weight(1f))
                        PhoneStat("조건 준수", "${KdDemoData.weekCompliancePercent}%", Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PhoneStat("휴식 권고", "${maxOf(rest, KdDemoData.weekRestSuggestions)}회", Modifier.weight(1f))
                        PhoneStat("반응 속도", "평소와 비슷", Modifier.weight(1f))
                    }
                }
                Text(
                    "위치와 경로는 공유하지 않아요",
                    style = Kd.BodyMuted,
                    modifier = Modifier.fillMaxWidth().clip(Kd.ButtonShape).background(Kd.SurfaceRaised).padding(20.dp),
                )
            }
            // 오른쪽: 운전자가 정하는 공유 범위
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Kd.Gap)) {
                KdCard(Modifier.fillMaxWidth().weight(1f)) {
                    Text("무엇을 보내나요?", style = Kd.Title)
                    // 네 줄이 카드 높이를 똑같이 나눠 쓴다
                    ShareRow("운전 횟수와 조건 준수율", shared = true, modifier = Modifier.weight(1f))
                    ShareRow("휴식 권고 횟수", shared = true, modifier = Modifier.weight(1f))
                    ShareRow("반응 속도 변화(좋음·느려짐만)", shared = true, modifier = Modifier.weight(1f))
                    ShareRow("위치·경로·목적지", shared = false, modifier = Modifier.weight(1f))
                }
                KdCard(Modifier.fillMaxWidth()) {
                    Text("언제 보내나요?", style = Kd.Title)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("매주 월요일 아침 9시, 한 번만 보내요", style = Kd.Body)
                            Text("보낼 내용은 운전하시는 분이 정해요", style = Kd.BodyMuted)
                        }
                        KdTag("매주 월 09:00", color = Kd.Accent)
                    }
                }
            }
        }
    }
}

/** 휴대폰 미리보기 안의 숫자 칸 */
@Composable
private fun PhoneStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxHeight().clip(Kd.ButtonShape).background(Kd.SurfaceRaised).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(label, style = Kd.Eyebrow)
        Text(value, style = Kd.Title.copy(fontSize = if (value.length > 5) 28.sp else 40.sp, lineHeight = 48.sp))
    }
}

/** 보내는 항목 한 줄: 옅은 면 + 상태 기호 + 꼬리표 */
@Composable
private fun ShareRow(label: String, shared: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(Kd.ButtonShape).background(Kd.SurfaceRaised).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KdToneDot(if (shared) KdTone.GOOD else KdTone.DANGER, size = 44.dp, symbol = if (shared) "✓" else "✕")
        Spacer(Modifier.width(20.dp))
        Text(label, style = Kd.Body, modifier = Modifier.weight(1f))
        KdTag(if (shared) "보냄" else "안 보냄", color = if (shared) Kd.Good else Kd.Danger)
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
