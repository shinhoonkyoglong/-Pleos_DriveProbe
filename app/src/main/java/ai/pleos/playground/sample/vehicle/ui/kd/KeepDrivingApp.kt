package ai.pleos.playground.sample.vehicle.ui.kd

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * '계속운전' 앱 뼈대. 화면 이동만 하고, 각 파트 화면은 슬롯으로 받는다.
 *
 * @param departureCard   홈의 '오늘 운전해도 되나요?' 카드(2번 DepartureHomeCard). 없으면 자리 표시 카드
 * @param departureDetail '출발 전' 전체 화면(2번 DepartureScreen). 없으면 안내 화면
 * @param driverCondition 운전 조건 설정 화면(2번, 작업 #17)
 * @param devTools        API 탐색기(1번, 개발용)
 */
@Composable
fun KeepDrivingApp(
    appViewModel: KdAppViewModel,
    reactionViewModel: ReactionCheckViewModel,
    driverCondition: @Composable (onBack: () -> Unit) -> Unit,
    devTools: @Composable () -> Unit,
    departureCard: (@Composable (Modifier, onOpen: () -> Unit) -> Unit)? = null,
    departureDetail: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    assistState: DrivingAssistState = DrivingAssistState(),
    drivingAssist: @Composable (onBack: () -> Unit) -> Unit = {},
    restStopSettings: @Composable (onBack: () -> Unit) -> Unit = {},
) {
    val state by appViewModel.state.collectAsStateWithLifecycle()
    val home = appViewModel::home

    KdTheme {
        when (state.page) {
            KdPage.HOME -> HomeScreen(
                state = state,
                onOpen = appViewModel::open,
                onDismissTripEnd = appViewModel::dismissTripEnd,
                onSkipCheck = appViewModel::skipCheckToday,
                assistSummary = assistState.summary,
                departureCard = { m ->
                    val open = { appViewModel.open(KdPage.DEPARTURE_DETAIL) }
                    if (departureCard != null) departureCard(m, open) else DeparturePlaceholderCard(m, open)
                },
            )
            KdPage.REACTION_CHECK -> ReactionCheckScreen(reactionViewModel, isReplay = state.vehicle.isReplay, onBack = home)
            KdPage.DRIVING -> DrivingScreen(state, assistState)
            KdPage.DRIVING_ASSIST -> drivingAssist(home)
            KdPage.REST_STOP_SETTINGS -> restStopSettings { appViewModel.open(KdPage.DRIVING_ASSIST) }
            KdPage.RECORDS -> RecordsScreen(state, onOpen = appViewModel::open, onBack = home)
            KdPage.FAMILY_SUMMARY -> FamilySummaryScreen(state, onBack = { appViewModel.open(KdPage.RECORDS) })
            KdPage.REPORT -> ReportScreen(state, onBack = { appViewModel.open(KdPage.RECORDS) })
            KdPage.DRIVER_CONDITION -> {
                BackHandler(onBack = home)
                driverCondition(home)
            }
            KdPage.DEPARTURE_DETAIL -> Box(Modifier.fillMaxSize()) {
                if (departureDetail != null) departureDetail(home) else DepartureDetailPlaceholder(home)
                if (state.naviDemo) {
                    // 2번 화면 위에 겹쳐 표시: 내비가 응답하지 않아 예시 값으로 판정 중
                    KdTag(
                        "내비 예시 데이터",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(top = 28.dp, end = 40.dp),
                    )
                }
            }
            KdPage.DEV_TOOLS -> {
                BackHandler(onBack = home)
                Column(Modifier.fillMaxSize().background(Kd.Background)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        KdButton("← 계속운전 홈", onClick = home, style = KdButtonStyle.SECONDARY, minHeight = 72.dp)
                    }
                    Box(Modifier.weight(1f)) { devTools() }
                }
            }
        }
    }
}

@Composable
private fun DepartureDetailPlaceholder(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    KdScreen {
        KdTopBar("출발 전 확인", onBack = onBack)
        KdStatusBadge(KdTone.INFO, "출발 전 확인 화면을 연결하는 중이에요")
        Text(
            "2번 파트의 출발 전 화면(:departure-ui DepartureScreen)이 main 에 병합되면 이 자리에 들어와요.",
            style = Kd.Body,
        )
    }
}
