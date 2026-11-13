package com.pleos.departure.ui

import androidx.compose.ui.graphics.Color

/*
 * '출발 전' 화면 색. 앱('계속운전') 디자인 시스템(app/.../ui/kd/KdTheme.kt 의 Kd)과 같은 값이다.
 * :departure-ui 는 이식성 규칙상 앱 패키지를 참조할 수 없어 값을 복사해 둔다. 한쪽을 바꾸면 다른 쪽도 바꾼다.
 *
 * - 면은 테두리 대신 밝기 차이로 구분한다. 상태 카드만 옅은 상태색 테두리 + 아주 옅은 상태색 바탕
 * - 상태 기호는 옅은 상태색 동그라미 + 진한 상태색 기호
 */
internal object DepartureColors {
    val Background = Color(0xFF080C13)
    val Surface = Color(0xFF121A25)
    val SurfaceRaised = Color(0xFF1B2532)
    val Ink = Color(0xFFEEF3F8)
    val InkMuted = Color(0xFF9CAABB)
    val Primary = Color(0xFF00AAD2) // 현대 액티브 블루
    val OnPrimary = Color(0xFF04213A)
    /** 홈 대표 카드의 은은한 남색 빛 */
    val HeroGlow = Color(0xFF0F3256)

    val Good = Color(0xFF46D293)
    val Info = Color(0xFF45C6E8)
    val Caution = Color(0xFFF5C04E)
    val Danger = Color(0xFFF46A6A)
    val Unknown = Color(0xFF95A3B5)
}
