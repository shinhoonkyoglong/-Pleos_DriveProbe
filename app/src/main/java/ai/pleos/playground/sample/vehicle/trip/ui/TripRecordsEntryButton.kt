package ai.pleos.playground.sample.vehicle.trip.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * [임시] 메인 화면(ApiCatalogScreen 헤더)에서 주행 기록 화면으로 가는 버튼.
 *
 * 콜백을 MainActivity까지 잇지 않도록 스스로 Activity를 연다 → 공용 파일에는 이 버튼을 부르는 줄만 들어간다.
 * 팀원 UI가 생기면 그 화면에 [TripRecordsSection]을 넣고, 공용 파일의 이 버튼 호출 줄은 지운다.
 */
@Composable
fun TripRecordsEntryButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { context.startActivity(TripSummaryActivity.intent(context, tripId = null, startInHistory = true)) },
        modifier = modifier.heightIn(min = 48.dp).testTag("open-trip-records"),
    ) {
        Text(text = "주행 기록", style = MaterialTheme.typography.labelLarge)
    }
}
