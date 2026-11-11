package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pleos.departure.ui.state.DepartureScreenModel

/**
 * 홈 카드와 '출발 전' 확인 전체 화면이 함께 쓰는 상태 홀더(작업 #25).
 *
 * 2번 [DepartureScreenModel](세션 + 한국어 표시 상태)을 viewModelScope 로 만든다. 홈 ↔ 상세를 오가도 같은 세션이라
 * 확인 결과·재확인 진행이 끊기지 않고, Activity 재생성(주간/야간 전환 등)에도 유지된다.
 * 확인은 화면이 보일 때만 돈다(DepartureHomeCard / DepartureScreen 이 onVisible·onHidden 을 부른다).
 *
 * 데이터: Application.departureFeature — 1번 NaviHelper·Vehicle Repository + Room 운전자 조건(작업 #12c~#15)
 */
class DepartureHomeViewModel(application: Application) : AndroidViewModel(application) {
    val model: DepartureScreenModel =
        DepartureScreenModel((application as DriveProbeApplication).departureFeature, viewModelScope)
}
