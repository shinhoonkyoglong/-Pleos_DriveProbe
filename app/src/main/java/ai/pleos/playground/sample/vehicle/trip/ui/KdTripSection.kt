package ai.pleos.playground.sample.vehicle.trip.ui

import ai.pleos.playground.sample.vehicle.trip.SummaryItem
import ai.pleos.playground.sample.vehicle.trip.SummaryTone
import ai.pleos.playground.sample.vehicle.trip.TripHistoryItem
import ai.pleos.playground.sample.vehicle.trip.TripModule
import ai.pleos.playground.sample.vehicle.trip.TripSummaryContent
import ai.pleos.playground.sample.vehicle.trip.share.ShareResult
import ai.pleos.playground.sample.vehicle.ui.kd.Kd
import ai.pleos.playground.sample.vehicle.ui.kd.KdButton
import ai.pleos.playground.sample.vehicle.ui.kd.KdButtonStyle
import ai.pleos.playground.sample.vehicle.ui.kd.KdCard
import ai.pleos.playground.sample.vehicle.ui.kd.KdDivider
import ai.pleos.playground.sample.vehicle.ui.kd.KdSectionTitle
import ai.pleos.playground.sample.vehicle.ui.kd.KdStatusBadge
import ai.pleos.playground.sample.vehicle.ui.kd.KdTag
import ai.pleos.playground.sample.vehicle.ui.kd.KdTone
import ai.pleos.playground.sample.vehicle.ui.kd.formatDateTime
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * '계속운전' 앱 화면(ui/kd, 팀원 디자인)에 넣는 5번 주행 기록 — **데이터는 5번(TripModule), 모양은 팀원 디자인(Kd*)**.
 *
 * 팀원 화면(RecordsScreen)에서 쓰는 것:
 *   KdTripSection()              최근 주행 요약 + [가족에게 보내기] + 지난 주행 목록(누르면 위 요약이 바뀜)
 *   rememberTripWeekNumbers()    "이번 주" 카드의 운전 횟수·조건 준수율
 *
 * 팀원 디자인 부품(Kd, KdCard, KdStatusBadge, KdButton, KdTag, KdTone, formatDateTime)에 기대므로,
 * 그쪽 이름이 바뀌면 이 파일만 고치면 된다. 계산·저장은 5번 쪽 그대로다.
 */

/** "이번 주" 카드에 넣을 값(글자) */
data class TripWeekNumbers(
    val trips: String,
    val compliance: String,
    val complianceTone: KdTone?,
    /** 카드 오른쪽 꼬리표(임시값·시연 기록일 때만). null이면 꼬리표 없음 */
    val tag: String?,
)

/** 최근 7일(오늘 포함) 운전 횟수·조건 준수율. 기록이 바뀌면 다시 그린다 */
@Composable
fun rememberTripWeekNumbers(): TripWeekNumbers {
    val flow = remember { TripModule.observeWeekStats() }
    val week by flow.collectAsStateWithLifecycle(initialValue = null)
    val w = week ?: return TripWeekNumbers("-", "-", null, null)
    val rate = w.complianceRate
    return TripWeekNumbers(
        trips = "${w.tripCount}회",
        compliance = rate?.let { "${(it * 100).roundToInt()}%" } ?: "-",
        complianceTone = when {
            rate == null -> null
            rate >= 1f -> KdTone.GOOD
            else -> KdTone.CAUTION
        },
        tag = when {
            w.isDemo && SHOW_DEMO_RECORD_TAGS -> "시연 기록"
            w.complianceMock -> "조건 준수는 임시값"
            else -> null
        },
    )
}

/**
 * 최근 주행 요약 + [가족에게 보내기] + 지난 주행 목록. 스스로 스크롤하지 않는다(호스트 KdScreen이 스크롤).
 * @param historyLimit 지난 주행 목록에 보여줄 개수
 */
@Composable
fun KdTripSection(modifier: Modifier = Modifier, historyLimit: Int = 5) {
    var selectedTripId by rememberSaveable { mutableStateOf<String?>(null) } // null = 가장 최근
    val summaryFlow = remember(selectedTripId) { TripModule.observeSummary(selectedTripId).map { KdLoaded(it) } }
    val summary by summaryFlow.collectAsStateWithLifecycle(initialValue = null)
    val historyFlow = remember(historyLimit) { TripModule.observeHistory(historyLimit) }
    val history by historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(modifier = modifier.testTag("kd-trip-section"), verticalArrangement = Arrangement.spacedBy(Kd.Gap)) {
        KdSectionTitle(if (selectedTripId == null) "최근 주행" else "주행 요약")
        val content = summary?.value
        when {
            summary == null -> KdCard(Modifier.fillMaxWidth()) { Text("주행 기록을 불러오는 중이에요", style = Kd.BodyMuted) }
            content == null -> KdCard(Modifier.fillMaxWidth()) {
                Text("아직 주행 기록이 없어요. 운전을 마치면 여기에 요약이 생겨요.", style = Kd.BodyMuted)
            }
            else -> KdTripSummaryCard(content)
        }

        if (history.size > 1 || (history.size == 1 && selectedTripId != null)) {
            KdSectionTitle("지난 주행")
            KdCard(Modifier.fillMaxWidth()) {
                history.forEachIndexed { i, item ->
                    if (i > 0) KdDivider()
                    val selected = item.tripId == (selectedTripId ?: history.firstOrNull()?.tripId)
                    KdHistoryRow(item, selected) { selectedTripId = if (i == 0) null else item.tripId }
                }
            }
        }
    }
}

/** 주행 요약 카드 한 장(값만 받음) */
@Composable
fun KdTripSummaryCard(content: TripSummaryContent, modifier: Modifier = Modifier) {
    KdCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDateTime(content.endedAt) + " 종료", style = Kd.BodyMuted, modifier = Modifier.weight(1f))
            if (content.isDemo && SHOW_DEMO_RECORD_TAGS) KdTag("시연 기록", color = Kd.Unknown)
        }
        KdStatusBadge(content.headlineTone.kd(), content.headline, modifier = Modifier.fillMaxWidth())
        KdDivider()
        content.items.forEach { KdSummaryRow(it) }
        if (content.hasMock) {
            Text("\"임시\"는 다른 기능과 아직 연결되지 않아 예시 값을 넣은 항목이에요.", style = Kd.BodyMuted)
        }
        KdFamilyShare(content.tripId)
    }
}

@Composable
private fun KdSummaryRow(item: SummaryItem) {
    val tone = item.tone.kd()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tone.symbol, color = tone.color, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
            Text(item.title, style = Kd.Body)
            if (item.isMock) {
                Spacer(Modifier.width(12.dp))
                KdTag("임시", color = Kd.Unknown)
            }
            Spacer(Modifier.weight(1f))
            Text(item.value, color = tone.color, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
        }
        item.detail?.let { Text(it, style = Kd.BodyMuted, modifier = Modifier.padding(start = 48.dp)) }
    }
}

/** [가족에게 보내기] — 운전자가 눌렀을 때만 보낸다(지금은 서버가 없어 임시) */
@Composable
private fun KdFamilyShare(tripId: String) {
    var share by remember(tripId) { mutableStateOf<FamilyShareState>(FamilyShareState.Idle) }
    val scope = rememberCoroutineScope()
    val sent = (share as? FamilyShareState.Done)?.result is ShareResult.Sent
    KdButton(
        text = when {
            share is FamilyShareState.Sending -> "보내는 중…"
            sent -> "가족에게 다시 보내기"
            else -> "가족에게 보내기"
        },
        subText = "위치와 경로는 보내지 않아요",
        onClick = {
            scope.launch {
                share = FamilyShareState.Sending
                share = FamilyShareState.Done(TripModule.shareToFamily(tripId))
            }
        },
        enabled = share !is FamilyShareState.Sending,
        style = KdButtonStyle.SECONDARY,
        modifier = Modifier.fillMaxWidth().testTag("kd-share-to-family"),
    )
    when (val s = share) {
        is FamilyShareState.Done -> when (val r = s.result) {
            is ShareResult.Sent ->
                if (r.isMock) Text("보냈어요. (임시: 가족 연결 전이라 실제로는 전송되지 않아요)", style = Kd.BodyMuted)
                else Text("가족에게 보냈어요.", style = Kd.Body.copy(color = Kd.Good))
            is ShareResult.Failed -> Text(r.reason, style = Kd.Body.copy(color = Kd.Danger))
        }
        else -> Unit
    }
}

@Composable
private fun KdHistoryRow(item: TripHistoryItem, selected: Boolean, onClick: () -> Unit) {
    val tone = item.tone.kd()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Kd.MinTouch)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tone.symbol, color = tone.color, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDateTime(item.endedAt), style = if (selected) Kd.Label.copy(color = Kd.Accent) else Kd.Label)
                if (item.isDemo && SHOW_DEMO_RECORD_TAGS) {
                    Spacer(Modifier.width(12.dp))
                    KdTag("시연", color = Kd.Unknown)
                }
            }
            Text(item.headline, style = Kd.BodyMuted)
        }
        Spacer(Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(item.duration, style = Kd.Label)
            Text("피로 ${item.fatigue}", style = Kd.BodyMuted)
        }
    }
}

/** 5번 상태(좋음/확인/참고) → 팀원 디자인 상태 */
private fun SummaryTone.kd(): KdTone = when (this) {
    SummaryTone.GOOD -> KdTone.GOOD
    SummaryTone.WARN -> KdTone.CAUTION
    SummaryTone.INFO -> KdTone.INFO
}

/** 불러오기 전(null)과 "기록 없음"(value null)을 구분 */
private class KdLoaded<T>(val value: T)
