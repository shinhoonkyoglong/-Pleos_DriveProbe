package ai.pleos.playground.sample.vehicle.trip.ui

import ai.pleos.playground.sample.vehicle.trip.TripModule
import ai.pleos.playground.sample.vehicle.ui.theme.VehicleSampleAppTheme
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 주행 기록 **임시 호스트** 화면 (5번). 팀원 UI가 생기기 전까지 쓴다.
 * - [TripModule.summaryPresenter] 기본값이 주행이 끝날 때 이 화면을 띄운다
 * - 메인 화면 헤더의 [주행 기록] 버튼([TripRecordsEntryButton])으로 지난 기록부터 연다
 * 팀원 화면에 [TripRecordsSection]을 넣으면 이 Activity는 지워도 된다(docs/trip-summary.md).
 *
 * 기어 P에서 보는 화면이다. Pleos는 주행 중(D)에 앱 화면을 가린다.
 */
class TripSummaryActivity : ComponentActivity() {

    private var tripId by mutableStateOf<String?>(null)
    private var startInHistory by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        setContent {
            VehicleSampleAppTheme {
                TripSummaryScreen(initialTripId = tripId, startInHistory = startInHistory, onClose = ::finish)
            }
        }
    }

    /** 이미 열려 있을 때 새 주행이 끝나면 그 주행으로 바꾼다 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent) {
        tripId = intent.getStringExtra(EXTRA_TRIP_ID)
        startInHistory = intent.getBooleanExtra(EXTRA_START_IN_HISTORY, false)
    }

    companion object {
        private const val EXTRA_TRIP_ID = "tripId"
        private const val EXTRA_START_IN_HISTORY = "startInHistory"

        fun intent(context: Context, tripId: String?, startInHistory: Boolean = false): Intent =
            Intent(context, TripSummaryActivity::class.java)
                .putExtra(EXTRA_TRIP_ID, tripId)
                .putExtra(EXTRA_START_IN_HISTORY, startInHistory)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
