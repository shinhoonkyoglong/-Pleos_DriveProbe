package com.pleos.departure.ui.state

import com.pleos.departure.DepartureFeature
import com.pleos.departure.model.DataResult
import com.pleos.departure.session.DepartureSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus

/**
 * '출발 전' 화면 상태 홀더(작업 #7 → #12b부터 얇은 표시 계층).
 *
 * 확인 흐름·세션 기억·재확인·실패 처리 같은 동작은 모두 core 의 [DepartureSession]이 한다.
 * 이 클래스는 세션 상태([com.pleos.departure.session.DepartureSessionState])를 [DepartureDisplayMapper.uiState]로
 * 한국어 화면 상태([DepartureUiState])로 바꾸고, 화면 명령을 세션에 그대로 넘기기만 한다.
 * 다른 화면 기술(1번 쪽 UI 등)로 옮길 때는 이 클래스 대신 [DepartureSession]을 직접 쓰면 된다.
 *
 * @param scope 화면 수명 범위(Compose 에서는 rememberCoroutineScope). 이 범위가 끝나면 모든 작업이 멈춘다.
 */
class DepartureScreenModel(
    private val feature: DepartureFeature,
    private val scope: CoroutineScope,
) {
    /** 화면과 무관한 core 세션. 표시 계층을 거치지 않고 상태를 보고 싶을 때 쓴다. */
    val session: DepartureSession = feature.createSession(scope)

    private val mapper = DepartureDisplayMapper(feature.config.timeZone)

    /**
     * 화면 상태. 세션 상태가 바뀔 때마다 바로(같은 호출 흐름 안에서) 다시 만든다 —
     * Unconfined 로 수집해 세션 값과 화면 값 사이에 디스패치 지연이 생기지 않게 한다(변환은 순수 함수라 어느 스레드에서 돌아도 안전).
     */
    val state: StateFlow<DepartureUiState> = session.state
        .map(mapper::uiState)
        .stateIn(scope + Dispatchers.Unconfined, SharingStarted.Eagerly, mapper.uiState(session.state.value))

    /** 화면이 보이기 시작했다(진입·백그라운드 복귀). */
    fun onVisible() = session.onVisible()

    /** 화면이 가려졌다. */
    fun onHidden() = session.onHidden()

    /** 사용자 "다시 확인". */
    fun refresh() = session.refresh()

    /** 사용자 "켰어요". */
    fun confirmTollAvoidanceTurnedOn() = session.confirmTollAvoidanceTurnedOn()

    /** 세션 기억을 지우고 다시 확인한다(예: 시연에서 Fake 시나리오 전환). */
    fun resetSession() = session.resetSession()

    private val _naviSettingsFailed = MutableStateFlow(false)

    /**
     * 마지막 [openNaviSettings]가 실패했는가(내비 미연결·권한 없음 등). 화면은
     * [DepartureDisplayMapper.NAVI_SETTINGS_FALLBACK](메뉴 위치 글 안내)을 보여 준다.
     */
    val naviSettingsFailed = _naviSettingsFailed.asStateFlow()

    /** 사용자 [내비 설정 열기](작업 #28). 어댑터가 예외를 던져도 앱이 죽지 않게 실패로 바꾼다. */
    fun openNaviSettings() {
        scope.launch {
            val result = runCatching { feature.openNaviSettings() }.getOrNull()
            _naviSettingsFailed.value = result !is DataResult.Available
        }
    }

    companion object {
        /** 주행 중 안내 문구. 원본은 [DepartureDisplayMapper.DRIVING_NOTICE]. */
        const val DRIVING_NOTICE = DepartureDisplayMapper.DRIVING_NOTICE
    }
}
