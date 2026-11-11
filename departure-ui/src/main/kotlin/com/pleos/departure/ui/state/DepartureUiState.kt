package com.pleos.departure.ui.state

/*
 * 화면 표시 상태(작업 #7). Compose 와 무관한 순수 Kotlin 값이라 JVM 단위 테스트로 검증한다.
 * 한국어 문구는 여기(:departure-ui)에서만 만든다. core 는 결과 타입·키만 준다.
 */

/**
 * 카드의 의미 단계. 화면은 색 + 아이콘 + 제목 문구를 **함께** 쓴다(색만으로 의미를 전달하지 않음 — 고령 운전자 기준).
 */
enum class Tone {
    /** 문제 없음(충족, 여유). */
    OK,

    /** 참고 안내(경고 아님). */
    INFO,

    /** 주의(사용자 확인·조작이 필요). */
    CAUTION,

    /** 위험(마감 임박·지남, 반경 밖). */
    DANGER,

    /** 확인 불가. 안전으로 보이지 않게 OK 와 다른 모양을 쓴다. */
    UNKNOWN,
}

/** 카드 아이콘. 화면은 기호 글자(예: ✓, !, ✕)로 그린다. 같은 Tone 이라도 의미가 다르면 다른 아이콘을 쓴다. */
enum class StatusIcon {
    CHECK,
    INFO,
    WARNING,
    STOP,
    NIGHT,
    QUESTION,
    CLOCK,
    WAIT,
}

/** 카드 본문 한 줄. [emphasis] 면 굵게(예: "예상치" 표기, 확인 불가 사유). */
data class DisplayLine(val text: String, val emphasis: Boolean = false)

/**
 * 상태 카드 하나.
 * @property headline 가장 크게 보여 줄 값(예: "오후 4시 14분까지 출발"). 없으면 null.
 * @property secondary 참고용 카드(작업 #9, 리뷰 task-07 MINOR-5). 주 메시지(예: "오늘은 출발을 자제하세요")와 경쟁하지 않도록
 *   화면은 작게·얇은 테두리로 그리고 "참고" 글자를 붙인다(색만으로 구분하지 않음).
 */
data class StatusCard(
    val tone: Tone,
    val icon: StatusIcon,
    val title: String,
    val headline: String? = null,
    val lines: List<DisplayLine> = emptyList(),
    val secondary: Boolean = false,
)

/**
 * 경로 조건 카드. 사용자 조작 안내([steps])와 "켰어요" 버튼([showConfirmToggle])을 가질 수 있다.
 * 주행 중이면 둘 다 비운다(리뷰 task-05 #7-11).
 * @property notes 참고 안내(RouteNote — 통행료 모름, 고속도로 회피 보장 불가). 숨기지 않고 단계·버튼 아래에 보여 준다.
 */
data class RouteCard(
    val card: StatusCard,
    val steps: List<String> = emptyList(),
    val showConfirmToggle: Boolean = false,
    val notes: List<DisplayLine> = emptyList(),
    /** [steps] 앞에 붙는 한 줄(예: "이번 경로는 …"). 단계가 없으면 null. */
    val stepsIntro: String? = null,
    /**
     * [내비 설정 열기] 버튼을 보여 줄지(작업 #28). 차량 설정 "내비게이션" 탭을 열어 전역 "유료도로 우회"를 한 번 켜 두게 한다.
     * 주행 중이면 false.
     */
    val showOpenNaviSettings: Boolean = false,
)

/**
 * 결과 화면 내용.
 * @property conditionNotice 운전자 조건 관련 알림(조건 확인 불가 등). 없으면 null.
 * @property main 출발 마감 카드(가장 크게). 조건 미설정이면 그 안내 카드.
 * @property radius 반경 카드. 조건 미설정이거나 확인 자체가 실패했으면 null. 출발 자제(마감 지남·야간)면 참고용([StatusCard.secondary]).
 * @property route 경로 조건 카드. 조건 미설정이거나 출발 자제(마감 지남·야간)면 null — 운전을 부추기는 조작 안내를 띄우지 않는다.
 *   확인 자체가 실패했어도 null.
 * @property conditionSet 운전자 조건이 저장돼 있는가. 미설정이면 false — 홈 카드가 "출발해도 좋아요"로 보이지 않게 구분한다(작업 #24).
 * @property destinationSet 내비 목적지가 있는가. 확인된 '목적지 없음'이면 false — 출발 전 확인은 목적지 기준이라
 *   홈 카드가 판정 대신 "내비에 목적지를 정하면 확인해요"를 보여 준다(작업 #26, 사용자 결정 11/11). 목적지 확인 불가는 true(확인 불가로 표시)
 * @property rechecking 목적지가 잠깐 사라져(경로 교체 중일 수 있음) 다시 확인하는 중. 홈 카드는 "확인 중이에요"
 */
data class DepartureContent(
    val conditionNotice: StatusCard?,
    val main: StatusCard,
    val radius: StatusCard?,
    val route: RouteCard?,
    val conditionSet: Boolean = true,
    val destinationSet: Boolean = true,
    val rechecking: Boolean = false,
)

/**
 * 화면 전체 상태.
 * @property content 첫 결과 전에는 null.
 * @property loading 확인 중(결과를 기다리는 중).
 * @property showLoadingIndicator 확인이 DepartureConfig.loadingIndicatorThreshold(2초)를 넘겨 "확인 중" 표시를 띄워야 함.
 * @property controlsEnabled 버튼(다시 확인, 켰어요)을 보여도 되는가. 주행 중이면 false.
 * @property drivingNotice 주행 중 안내 문구. 아니면 null.
 */
data class DepartureUiState(
    val content: DepartureContent?,
    val loading: Boolean,
    val showLoadingIndicator: Boolean,
    val controlsEnabled: Boolean,
    val drivingNotice: String?,
) {
    companion object {
        val Initial = DepartureUiState(
            content = null,
            loading = false,
            showLoadingIndicator = false,
            controlsEnabled = true,
            drivingNotice = null,
        )
    }
}
