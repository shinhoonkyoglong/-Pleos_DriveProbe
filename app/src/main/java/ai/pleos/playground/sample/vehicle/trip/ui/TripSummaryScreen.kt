package ai.pleos.playground.sample.vehicle.trip.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** [TripRecordsSection]의 페이지 */
enum class TripRecordsPage { SUMMARY, HISTORY, REPORT }

/**
 * **주행 기록 섹션 하나로 끝** — 주행 요약(가족에게 보내기 포함) ↔ 지난 기록 ↔ 운전 리포트 이동까지 들어 있는 묶음.
 * 팀원 화면에 넣을 때 이것 하나만 쓰면 된다.
 *
 * ```kotlin
 * Column(Modifier.verticalScroll(rememberScrollState())) {   // 호스트 화면 섹션
 *     TripRecordsSection()
 * }
 * ```
 * - 스스로 스크롤하지 않는다(스크롤은 호스트 몫)
 * - 요약 말고 다른 페이지에서 뒤로 가기를 누르면 요약으로 돌아온다
 *
 * @param initialTripId 처음 보여줄 주행(null = 가장 최근). 값이 바뀌면 그 주행 요약으로 돌아간다
 * @param startInHistory true면 지난 기록 목록부터 보여준다(= startPage HISTORY)
 * @param startPage 처음 보여줄 페이지
 * @param onClose null이 아니면 요약 아래에 [확인] 버튼을 두고 누르면 부른다(단독 화면용)
 */
@Composable
fun TripRecordsSection(
    modifier: Modifier = Modifier,
    initialTripId: String? = null,
    startInHistory: Boolean = false,
    onClose: (() -> Unit)? = null,
    startPage: TripRecordsPage = if (startInHistory) TripRecordsPage.HISTORY else TripRecordsPage.SUMMARY,
) {
    var page by rememberSaveable(initialTripId, startPage) { mutableStateOf(startPage) }
    var tripId by rememberSaveable(initialTripId) { mutableStateOf(initialTripId) }
    BackHandler(enabled = page != TripRecordsPage.SUMMARY) { page = TripRecordsPage.SUMMARY }
    val toSummary = { page = TripRecordsPage.SUMMARY }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (page) {
            TripRecordsPage.HISTORY -> {
                PageTitle("지난 기록", toSummary)
                TripHistorySection(onSelect = { id -> tripId = id; page = TripRecordsPage.SUMMARY })
            }
            TripRecordsPage.REPORT -> {
                PageTitle("운전 리포트", toSummary)
                DrivingReportSection(showTitle = false)
            }
            TripRecordsPage.SUMMARY -> {
                TripSummarySection(tripId = tripId)
                Row {
                    BigOutlinedButton("지난 기록", Modifier.weight(1f)) { page = TripRecordsPage.HISTORY }
                    Spacer(Modifier.width(16.dp))
                    BigOutlinedButton("운전 리포트", Modifier.weight(1f)) { page = TripRecordsPage.REPORT }
                }
                if (onClose != null) {
                    Button(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("확인", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.weight(1f))
        BigOutlinedButton("요약으로", onClick = onBack)
    }
}

/**
 * 단독 화면(TripSummaryActivity)의 껍데기: 배경·여백·스크롤만 담당하고 내용은 [TripRecordsSection].
 * 팀원 UI로 옮기면 이 함수와 Activity는 지워도 된다.
 */
@Composable
fun TripSummaryScreen(initialTripId: String?, startInHistory: Boolean, onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            TripRecordsSection(
                modifier = Modifier
                    .widthIn(max = 1100.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                initialTripId = initialTripId,
                startInHistory = startInHistory,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun BigOutlinedButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 72.dp), shape = RoundedCornerShape(16.dp)) {
        Text(text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    }
}
