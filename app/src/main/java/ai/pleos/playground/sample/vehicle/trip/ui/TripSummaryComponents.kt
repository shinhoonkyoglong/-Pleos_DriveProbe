package ai.pleos.playground.sample.vehicle.trip.ui

import ai.pleos.playground.sample.vehicle.trip.SummaryItem
import ai.pleos.playground.sample.vehicle.trip.SummaryKey
import ai.pleos.playground.sample.vehicle.trip.SummaryTone
import ai.pleos.playground.sample.vehicle.trip.TripHistoryItem
import ai.pleos.playground.sample.vehicle.trip.TripSummaryContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 주행 요약 UI 조각 — **값만 받아서 그리는** 순수 Composable (DB·TripModule을 모름).
 *
 * - 스스로 스크롤하지 않는다 → 팀원 화면의 verticalScroll/LazyColumn 안에 그대로 넣을 수 있다
 * - 배경·글자색은 호스트의 MaterialTheme(colorScheme)를 따른다. 상태색(좋음/확인/참고)만 고정
 * - 고령 운전자용: 큰 글씨, 색만으로 구분하지 않고 "좋음/확인/참고" 글자를 함께 쓴다
 * - 임시값(MOCK) 항목에는 "임시" 표시
 *
 * DB에서 직접 읽어 그리는 버전은 TripSummarySections.kt, 단독 화면은 TripSummaryScreen.kt
 */

private val GoodColor = Color(0xFF177A55)
private val WarnColor = Color(0xFFB3261E)
internal val InfoColor = Color(0xFF626776)
private val GoodBg = Color(0xFFE3F3EC)
private val WarnBg = Color(0xFFFBE7E6)
internal val InfoBg = Color(0xFFEDEEF2)

internal fun SummaryTone.color() = when (this) {
    SummaryTone.GOOD -> GoodColor
    SummaryTone.WARN -> WarnColor
    SummaryTone.INFO -> InfoColor
}

internal fun SummaryTone.background() = when (this) {
    SummaryTone.GOOD -> GoodBg
    SummaryTone.WARN -> WarnBg
    SummaryTone.INFO -> InfoBg
}

internal fun SummaryTone.label() = when (this) {
    SummaryTone.GOOD -> "좋음"
    SummaryTone.WARN -> "확인"
    SummaryTone.INFO -> "참고"
}

/**
 * 주행 요약 1건 (제목줄 + 한 줄 요약 + 항목 + 임시값 안내).
 * @param showTitle false면 "주행 요약" 제목을 빼고 종료 시각·시연 표시만 보여준다(호스트 섹션에 제목이 있을 때)
 */
@Composable
fun TripSummaryView(
    content: TripSummaryContent,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SummaryHeader(content, showTitle)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = content.headlineTone.background(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                content.headline,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                fontSize = 32.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.Bold,
                color = content.headlineTone.color(),
            )
        }
        content.items.forEach { SummaryItemRow(it) }
        if (content.hasMock) {
            Text(
                "\"임시\" 표시는 다른 기능과 아직 연결되지 않아 예시 값을 넣은 항목이에요.",
                fontSize = 18.sp, color = InfoColor,
            )
        }
    }
}

/** 지난 기록 목록. 줄을 누르면 [onSelect](tripId) */
@Composable
fun TripHistoryList(
    items: List<TripHistoryItem>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (items.isEmpty()) {
            TripEmptyText("아직 주행 기록이 없어요")
        }
        items.forEach { HistoryRow(it, onSelect) }
    }
}

/** 기록이 없을 때·불러오는 중 안내 */
@Composable
fun TripEmptyText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 24.sp, color = InfoColor)
}

@Composable
private fun SummaryHeader(content: TripSummaryContent, showTitle: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showTitle) {
            Text("주행 요약", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.width(16.dp))
        }
        Text(endedText(content.endedAt), fontSize = 22.sp, color = InfoColor)
        if (content.isDemo && SHOW_DEMO_RECORD_TAGS) {
            Spacer(Modifier.width(16.dp))
            Chip("시연 기록", InfoColor, InfoBg)
        }
    }
}

@Composable
internal fun SummaryItemRow(item: SummaryItem) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToneDot(item.tone)
                    Spacer(Modifier.width(12.dp))
                    Text(item.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    if (item.isMock) {
                        Spacer(Modifier.width(12.dp))
                        Chip("임시", InfoColor, InfoBg)
                    }
                }
                item.detail?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 20.sp, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(item.value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = item.tone.color(), textAlign = TextAlign.End)
                Text(item.tone.label(), fontSize = 18.sp, color = item.tone.color())
            }
        }
    }
}

@Composable
private fun HistoryRow(item: TripHistoryItem, onSelect: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp) // 차량 화면 터치 영역을 넉넉하게
            .clickable { onSelect(item.tripId) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToneDot(item.tone)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dateText(item.endedAt), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    if (item.isDemo && SHOW_DEMO_RECORD_TAGS) {
                        Spacer(Modifier.width(12.dp))
                        Chip("시연", InfoColor, InfoBg)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(item.headline, fontSize = 20.sp, lineHeight = 28.sp, color = item.tone.color())
            }
            Spacer(Modifier.width(20.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(item.duration, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("피로 ${item.fatigue}", fontSize = 18.sp, color = InfoColor)
            }
        }
    }
}

@Composable
internal fun ToneDot(tone: SummaryTone) {
    Box(Modifier.size(14.dp).background(tone.color(), CircleShape))
}

@Composable
internal fun Chip(text: String, fg: Color, bg: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 18.sp, color = fg)
    }
}

private fun endedText(endedAt: Long): String =
    SimpleDateFormat("a h:mm 종료", Locale.KOREAN).format(Date(endedAt))

private fun dateText(endedAt: Long): String =
    SimpleDateFormat("M월 d일 a h:mm", Locale.KOREAN).format(Date(endedAt))

// ---- 미리보기 ----

internal val PreviewContent = TripSummaryContent(
    tripId = "preview",
    headline = "운전 중 피로 신호가 있었어요. 다음에는 중간에 꼭 쉬어 가세요.",
    headlineTone = SummaryTone.WARN,
    items = listOf(
        SummaryItem(SummaryKey.DURATION, "운전 시간", "1시간 5분", "쉬지 않고 가장 오래 운전한 시간은 45분이에요.", SummaryTone.INFO, false),
        SummaryItem(SummaryKey.FATIGUE, "피로 점수", "최고 72점", "졸음 위험 단계까지 올라갔어요. 평균 31점이에요(높을수록 피곤).", SummaryTone.WARN, false),
        SummaryItem(SummaryKey.REST, "휴식 권고", "2번", "안내에 2번 답하셨어요.", SummaryTone.WARN, true),
        SummaryItem(SummaryKey.PRE_DRIVE, "출발 전 반응", "평소와 비슷", null, SummaryTone.GOOD, true),
        SummaryItem(SummaryKey.COMPLIANCE, "운전 조건", "모두 지킴", null, SummaryTone.GOOD, true),
    ),
    endedAt = 0L,
    isDemo = true,
)

@Preview(widthDp = 1100)
@Composable
private fun TripSummaryViewPreview() {
    MaterialTheme { TripSummaryView(PreviewContent, Modifier.padding(24.dp)) }
}

@Preview(widthDp = 1100)
@Composable
private fun TripHistoryListPreview() {
    MaterialTheme {
        TripHistoryList(
            items = listOf(
                TripHistoryItem("a", 0L, "3분", "최고 75점", "운전 중 피로 신호가 있었어요. 다음에는 중간에 꼭 쉬어 가세요.", SummaryTone.WARN, true),
                TripHistoryItem("b", 0L, "42분", "판정 없음", "운전을 마치셨어요. 이번에는 피로를 판정할 구간이 없었어요.", SummaryTone.INFO, false),
            ),
            onSelect = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}
