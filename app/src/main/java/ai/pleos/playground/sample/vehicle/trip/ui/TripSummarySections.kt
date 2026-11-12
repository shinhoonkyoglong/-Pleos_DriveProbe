package ai.pleos.playground.sample.vehicle.trip.ui

import ai.pleos.playground.sample.vehicle.trip.TripModule
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

/*
 * 팀원 화면에 그대로 넣는 섹션 — DB(TripModule)에서 직접 읽어 그린다.
 * TripModule.init()이 끝난 뒤(Application.onCreate 이후)에만 쓴다.
 *
 * ```kotlin
 * Column(Modifier.verticalScroll(rememberScrollState())) {   // 호스트 화면
 *     TripSummarySection()                                     // 가장 최근 주행 요약
 *     TripHistorySection(onSelect = { id -> selected = id })   // 지난 기록 목록
 * }
 * ```
 * 스스로 스크롤하지 않으므로 스크롤은 호스트가 맡는다. LazyColumn이면 섹션 전체를 item { } 하나에 넣는다.
 */

/** 불러오기 전과 "기록 없음"을 구분하기 위한 감싸기 */
private class Loaded<T>(val value: T)

/**
 * 주행 요약 섹션(+ [가족에게 보내기] 버튼).
 * @param tripId null이면 가장 최근 주행(새 주행이 끝나면 자동으로 바뀐다)
 * @param showTitle 호스트 섹션에 제목이 있으면 false
 * @param showFamilyShare false면 보내기 버튼을 빼고 요약만(호스트가 [FamilyShareButton]을 다른 곳에 둘 때)
 */
@Composable
fun TripSummarySection(
    modifier: Modifier = Modifier,
    tripId: String? = null,
    showTitle: Boolean = true,
    showFamilyShare: Boolean = true,
) {
    val flow = remember(tripId) { TripModule.observeSummary(tripId).map { Loaded(it) } }
    val loaded by flow.collectAsStateWithLifecycle(initialValue = null)
    val state = loaded
    when {
        state == null -> TripEmptyText("주행 기록을 불러오는 중이에요", modifier)
        state.value == null -> TripEmptyText("아직 주행 기록이 없어요", modifier)
        !showFamilyShare -> TripSummaryView(state.value, modifier, showTitle)
        else -> Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TripSummaryView(state.value, showTitle = showTitle)
            FamilyShareSection(tripId = state.value.tripId)
        }
    }
}

/**
 * [가족에게 보내기] 버튼 + 보내기 처리(TripModule.shareToFamily). 요약과 떨어진 곳에 둘 때 쓴다.
 * @param tripId 보낼 주행(null = 가장 최근)
 */
@Composable
fun FamilyShareSection(tripId: String?, modifier: Modifier = Modifier) {
    var share by remember(tripId) { mutableStateOf<FamilyShareState>(FamilyShareState.Idle) }
    val scope = rememberCoroutineScope()
    FamilyShareButton(
        state = share,
        onClick = {
            scope.launch {
                share = FamilyShareState.Sending
                share = FamilyShareState.Done(TripModule.shareToFamily(tripId))
            }
        },
        modifier = modifier,
    )
}

/**
 * 지난 기록 목록 섹션(최근 순).
 * @param onSelect 줄을 눌렀을 때 tripId. 보통 [TripSummarySection]의 tripId로 넘긴다
 * @param limit    최대 몇 건까지 보여줄지
 */
@Composable
fun TripHistorySection(
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    limit: Int = 30,
) {
    val flow = remember(limit) { TripModule.observeHistory(limit).map { Loaded(it) } }
    val loaded by flow.collectAsStateWithLifecycle(initialValue = null)
    val state = loaded
    if (state == null) TripEmptyText("주행 기록을 불러오는 중이에요", modifier)
    else TripHistoryList(state.value, onSelect, modifier)
}
