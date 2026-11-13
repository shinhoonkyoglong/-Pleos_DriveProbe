package com.pleos.departure.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * '출발 전' 화면의 다크/라이트 선택. 앱('계속운전')이 화면 밝기 토글을 바꿀 때 이 값을 함께 바꿔 준다(KdThemeMode).
 * Compose 상태라서 바꾸면 이 값을 읽은 화면이 다시 그려진다. 기본은 다크.
 */
object DepartureTheme {
    var isLight by mutableStateOf(false)
}

/*
 * '출발 전' 화면 색. 앱('계속운전') 디자인 시스템(app/.../ui/kd/KdTheme.kt 의 Kd)과 같은 값이다.
 * :departure-ui 는 이식성 규칙상 앱 패키지를 참조할 수 없어 값을 복사해 둔다. 한쪽을 바꾸면 다른 쪽도 바꾼다.
 *
 * - 면은 테두리 대신 밝기 차이로 구분한다. 상태 카드만 옅은 상태색 테두리 + 아주 옅은 상태색 바탕
 * - 상태 기호는 옅은 상태색 동그라미 + 진한 상태색 기호
 * - 값은 모두 getter(읽을 때마다 [DepartureTheme.isLight]에 맞는 색)다. `private val X = DepartureColors.Surface`처럼
 *   한 번 복사해 두면 모드 전환이 반영되지 않으므로 `get() =`으로 쓴다.
 */
internal object DepartureColors {
    private val light: Boolean get() = DepartureTheme.isLight

    // 라이트 값은 앱의 주행 요약(운행 결과) 화면 톤과 같다(앱 KdTheme.kt LightPalette 참고)
    val Background: Color get() = if (light) Color(0xFFF5F6FA) else Color(0xFF080C13)
    val Surface: Color get() = if (light) Color(0xFFFFFFFF) else Color(0xFF121A25)
    val SurfaceRaised: Color get() = if (light) Color(0xFFEDEEF2) else Color(0xFF1B2532)
    val Ink: Color get() = if (light) Color(0xFF171A22) else Color(0xFFEEF3F8)
    val InkMuted: Color get() = if (light) Color(0xFF626776) else Color(0xFF9CAABB)
    val Primary: Color get() = if (light) Color(0xFF365CF5) else Color(0xFF00AAD2) // 다크: 현대 액티브 블루
    val OnPrimary: Color get() = if (light) Color(0xFFFFFFFF) else Color(0xFF04213A)
    /** 홈 대표 카드의 은은한 빛 */
    val HeroGlow: Color get() = if (light) Color(0xFFE1E7FF) else Color(0xFF0F3256)

    val Good: Color get() = if (light) Color(0xFF177A55) else Color(0xFF46D293)
    val Info: Color get() = if (light) Color(0xFF365CF5) else Color(0xFF45C6E8)
    val Caution: Color get() = if (light) Color(0xFF8A5800) else Color(0xFFF5C04E)
    val Danger: Color get() = if (light) Color(0xFFB3261E) else Color(0xFFF46A6A)
    val Unknown: Color get() = if (light) Color(0xFF626776) else Color(0xFF95A3B5)
}
