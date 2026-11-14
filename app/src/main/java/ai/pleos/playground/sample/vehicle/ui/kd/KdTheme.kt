package ai.pleos.playground.sample.vehicle.ui.kd

import android.app.Activity
import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.pleos.departure.ui.DepartureTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * '계속운전' 디자인 시스템.
 *
 * 기준(기획서 + Pleos Driver Distraction 가이드 + 2번 출발 전 화면과 맞춤):
 * - 고령 운전자: 본문 24sp 이상, 제목 30sp 이상, 누르는 곳 높이 88dp 이상
 * - 어두운 바탕 + 고대비 글자(밤에도 눈부시지 않게). 보조 글자도 면 위에서 대비 7:1 수준
 * - 상태는 색만으로 구분하지 않는다: 늘 기호(✓ ! !! !!! ?) + 글자를 함께 쓴다
 *
 * 2026-11-13 디자인 정리:
 * - 면은 테두리 대신 밝기 차이(Background < Surface < SurfaceRaised)로 구분한다. 모든 카드에 두꺼운 선을 두르면 화면이 싸 보인다
 * - 색 테두리는 '상태를 알리는 카드'에만, 옅게(+ 면에 같은 색을 아주 살짝) 쓴다
 * - 꼬리표·상태 기호는 원색 덩어리 대신 옅은 색 바탕 + 진한 색 글자
 * - 현대차 차량 화면 방향: 현대 블루로 물든 남색 바탕 + 액티브 블루 강조 하나. 주 버튼은 액티브 블루 + 남색 글자, 나머지 버튼은 면 색
 * - 굵게는 제목·버튼에만. 큰 숫자는 보통 굵기, 본문은 Regular
 *
 * 2026-11-13 라이트 모드 추가:
 * - 다크(기본)·라이트 두 벌. 홈 머리의 [밝은 화면]/[어두운 화면] 버튼(KdThemeToggleButton)으로 바꾸고, 설정은 저장된다(KdThemeMode)
 * - 새 색을 추가하면 KdPalette·DarkPalette·LightPalette 세 곳과 Kd getter 에 모두 넣는다
 * - :departure-ui(2번)는 이 파일을 쓸 수 없어 같은 값을 departure-ui/.../DepartureColors.kt 에 따로 둔다. 바꾸면 둘 다 바꾼다
 */
/** 색 한 벌. 다크·라이트 두 벌을 두고 [KdThemeMode]에 따라 고른다. */
private class KdPalette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val outlineStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkSubtle: Color,
    val inkOnAccent: Color,
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val accentContainer: Color,
    val heroGlow: Color,
    val good: Color,
    val caution: Color,
    val serious: Color,
    val danger: Color,
    val unknown: Color,
)

// 다크(기본): 현대차 브랜드 '현대 블루(#002C5F)' 계열로 살짝 물든 남색 검정. 순수 회색보다 차 안 화면과 어울린다
private val DarkPalette = KdPalette(
    background = Color(0xFF080C13),
    surface = Color(0xFF121A25),
    surfaceRaised = Color(0xFF1B2532),
    surfaceHigh = Color(0xFF263242),
    outline = Color(0xFF243142), // 꼭 필요한 곳(구분선·표 눈금)에만
    outlineStrong = Color(0xFF3A4A5E),
    ink = Color(0xFFEEF3F8),
    inkMuted = Color(0xFF9CAABB), // Surface 위 대비 약 7.5:1
    inkSubtle = Color(0xFF7F8EA1), // 눈금 숫자 등 부수 정보만
    inkOnAccent = Color(0xFF071526), // 상태색·밝은 강조색 위 글자
    // 강조: 현대 '액티브 블루'(#00AAD2). 주 버튼은 이 색 + 남색 글자, 글자·아이콘 강조는 조금 밝힌 값
    primary = Color(0xFF00AAD2),
    onPrimary = Color(0xFF04213A),
    accent = Color(0xFF45C6E8),
    accentContainer = Color(0xFF0E2A3B),
    heroGlow = Color(0xFF0F3256),
    good = Color(0xFF46D293),
    caution = Color(0xFFF5C04E),
    serious = Color(0xFFF7944F),
    danger = Color(0xFFF46A6A),
    unknown = Color(0xFF95A3B5),
)

// 라이트: 주행 요약(운행 결과) 화면과 같은 톤 — 1번 밝은 테마(ui/theme/Color.kt)·trip/ui/TripSummaryComponents.kt 의 값을 그대로 쓴다.
// 중립 회색 바탕(Canvas) + 흰 면(Panel) + 파랑(Blue) 강조. 그쪽 색을 바꾸면 여기와 departure-ui DepartureColors 도 같이 바꾼다.
// 그쪽에 없는 주의(노랑)·심각(주황)만 따로 정했다: 다크의 밝은 원색은 흰 바탕에서 안 보여서(노랑 1.6:1) 진하게 내린 값.
// 흰 바탕 위 글자 대비: Ink 약 17:1, InkMuted 약 5.6:1, InkSubtle 약 4.9:1, Blue 약 5.2:1, 상태색 모두 5.5:1 이상(WCAG AA 4.5:1 초과)
private val LightPalette = KdPalette(
    background = Color(0xFFF5F6FA), // Canvas
    surface = Color(0xFFFFFFFF), // Panel
    surfaceRaised = Color(0xFFEDEEF2), // 주행 요약의 InfoBg
    surfaceHigh = Color(0xFFE1E3EA),
    outline = Color(0xFFDCDFE6),
    outlineStrong = Color(0xFFA9AEBB),
    ink = Color(0xFF171A22), // Ink
    inkMuted = Color(0xFF626776), // MutedInk
    inkSubtle = Color(0xFF6E7381),
    inkOnAccent = Color(0xFFFFFFFF), // 라이트의 상태색·강조색은 진하므로 위 글자는 흰색
    primary = Color(0xFF365CF5), // Blue
    onPrimary = Color(0xFFFFFFFF),
    accent = Color(0xFF365CF5), // Blue
    accentContainer = Color(0xFFE1E7FF), // BlueContainer
    heroGlow = Color(0xFFE1E7FF), // BlueContainer
    good = Color(0xFF177A55), // Green(주행 요약 GoodColor)
    caution = Color(0xFF8A5800),
    serious = Color(0xFFAD4A0C),
    danger = Color(0xFFB3261E), // Red(주행 요약 WarnColor)
    unknown = Color(0xFF626776), // MutedInk(주행 요약 InfoColor)
)

/**
 * 화면 밝기(다크/라이트) 설정. 앱 전체가 이 값 하나를 본다.
 *
 * [isLight]는 Compose 상태라서 바꾸면 그 값을 읽은 모든 화면이 다시 그려진다. 설정은 SharedPreferences 에 저장해 재시작해도 유지한다.
 * 2번 :departure-ui 는 앱 패키지를 볼 수 없어 [DepartureTheme]에 같은 값을 함께 넣어 준다.
 */
object KdThemeMode {
    private const val PREFS = "kd_ui"
    private const val KEY_LIGHT = "light_theme"

    var isLight by mutableStateOf(false)
        private set

    /** 앱 시작 시 한 번(Activity onCreate, setContent 전) 저장된 값을 읽는다. */
    fun load(context: Context) {
        apply(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIGHT, false))
    }

    fun setLight(context: Context, light: Boolean) {
        apply(light)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LIGHT, light).apply()
    }

    fun toggle(context: Context) = setLight(context, !isLight)

    private fun apply(light: Boolean) {
        isLight = light
        DepartureTheme.isLight = light
    }
}

/**
 * 색·글자 값. 모두 '읽을 때마다 현재 모드의 값'을 돌려주는 getter 라서, 화면 코드는 예전처럼 Kd.Ink 등을 그대로 쓰면 된다.
 * 주의: 파일 맨 위 `private val X = Kd.Surface` 처럼 값을 한 번 복사해 두면 모드를 바꿔도 안 바뀐다 → `get() = Kd.Surface`로 쓴다.
 */
object Kd {
    private val p: KdPalette get() = if (KdThemeMode.isLight) LightPalette else DarkPalette

    val isLight: Boolean get() = KdThemeMode.isLight

    // 바탕과 면
    val Background: Color get() = p.background
    val Surface: Color get() = p.surface
    val SurfaceRaised: Color get() = p.surfaceRaised
    val SurfaceHigh: Color get() = p.surfaceHigh
    val Outline: Color get() = p.outline // 꼭 필요한 곳(구분선·표 눈금)에만
    val OutlineStrong: Color get() = p.outlineStrong

    // 글자
    val Ink: Color get() = p.ink
    val InkMuted: Color get() = p.inkMuted
    val InkSubtle: Color get() = p.inkSubtle // 눈금 숫자 등 부수 정보만
    val InkOnAccent: Color get() = p.inkOnAccent // 상태색·강조색 위 글자

    // 강조: 현대 '액티브 블루' 계열. 주 버튼은 Primary + OnPrimary 글자
    val Primary: Color get() = p.primary
    val OnPrimary: Color get() = p.onPrimary
    val Accent: Color get() = p.accent
    val AccentContainer: Color get() = p.accentContainer
    /** 홈 대표 카드 한 장에만 쓰는 은은한 빛(위 → 아래) */
    val HeroGlow: Color get() = p.heroGlow

    // 상태(예약 색 — 다른 용도로 쓰지 않는다)
    val Good: Color get() = p.good
    val Caution: Color get() = p.caution
    val Serious: Color get() = p.serious
    val Danger: Color get() = p.danger
    val Unknown: Color get() = p.unknown

    // 글자: 크기는 고령 운전자 기준 그대로, '전부 굵게' 대신 굵기로 위계를 만든다
    // 큰 숫자는 보통 굵기(차량 계기판처럼 가볍게), 제목은 SemiBold, 본문은 Regular
    val Display: TextStyle get() = TextStyle(fontSize = 64.sp, lineHeight = 74.sp, fontWeight = FontWeight.SemiBold, color = Ink, letterSpacing = (-1).sp)
    val Headline: TextStyle get() = TextStyle(fontSize = 38.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, color = Ink, letterSpacing = (-0.5).sp)
    val Title: TextStyle get() = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, color = Ink, letterSpacing = (-0.3).sp)
    val Body: TextStyle get() = TextStyle(fontSize = 26.sp, lineHeight = 38.sp, color = Ink)
    val BodyMuted: TextStyle get() = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, color = InkMuted)
    val Label: TextStyle get() = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    /** 카드가 무엇을 말하는지 알려 주는 작은 제목("오늘 컨디션" 등). 카드 주제가 한눈에 안 보일 때만 쓴다 */
    val Eyebrow: TextStyle get() = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = InkMuted)
    /** 큰 숫자(수치 카드) */
    val Numeral: TextStyle get() = TextStyle(fontSize = 52.sp, lineHeight = 60.sp, fontWeight = FontWeight.Normal, color = Ink, letterSpacing = (-1).sp)

    val CardShape = RoundedCornerShape(32.dp)
    val ButtonShape = RoundedCornerShape(20.dp)
    val ScreenPadding = 40.dp
    val Gap = 24.dp
    val MinTouch = 96.dp
}

/** 상태 하나 = 색 + 기호 + 글자. 화면 어디서든 이 세트로만 상태를 보여 준다. */
enum class KdTone(val symbol: String) {
    GOOD("✓"),
    CAUTION("!"),
    SERIOUS("!!"),
    DANGER("!!!"),
    UNKNOWN("?"),
    INFO("i");

    /** 현재 모드(다크/라이트)의 상태색. enum 생성 때 색을 고정하면 모드 전환이 안 되므로 getter 로 둔다 */
    val color: Color
        get() = when (this) {
            GOOD -> Kd.Good
            CAUTION -> Kd.Caution
            SERIOUS -> Kd.Serious
            DANGER -> Kd.Danger
            UNKNOWN -> Kd.Unknown
            INFO -> Kd.Accent
        }
}

private fun kdColorScheme(light: Boolean): ColorScheme {
    val base = if (light) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = Kd.Primary,
        onPrimary = Kd.OnPrimary,
        primaryContainer = Kd.AccentContainer,
        onPrimaryContainer = Kd.Ink,
        secondary = Kd.Accent,
        background = Kd.Background,
        onBackground = Kd.Ink,
        surface = Kd.Surface,
        onSurface = Kd.Ink,
        surfaceVariant = Kd.SurfaceRaised,
        onSurfaceVariant = Kd.InkMuted,
        outline = Kd.OutlineStrong,
        outlineVariant = Kd.Outline,
        tertiary = Kd.Good,
        error = Kd.Danger,
    )
}

@Composable
fun KdTheme(content: @Composable () -> Unit) {
    val light = KdThemeMode.isLight
    val view = LocalView.current
    if (!view.isInEditMode) {
        // 상태 표시줄 아이콘 색도 바탕에 맞춘다(라이트 바탕이면 어두운 아이콘)
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = light
        }
    }
    MaterialTheme(colorScheme = remember(light) { kdColorScheme(light) }, content = content)
}

/** 다크/라이트 전환 버튼. 지금 모드의 반대를 글자로 보여 준다("밝은 화면" / "어두운 화면"). */
@Composable
fun KdThemeToggleButton(modifier: Modifier = Modifier, minHeight: Dp = 64.dp) {
    val context = LocalContext.current
    KdButton(
        if (KdThemeMode.isLight) "어두운 화면" else "밝은 화면",
        onClick = { KdThemeMode.toggle(context) },
        style = KdButtonStyle.QUIET,
        minHeight = minHeight,
        modifier = modifier.testTag("kd-theme-toggle"),
    )
}

/**
 * 면 카드. 기본은 테두리 없이 면 색만. [borderColor]를 주면 '상태 카드'로 보고 옅은 색 테두리 + 아주 옅은 색 바탕을 쓴다.
 * onClick 이 있으면 카드 전체가 눌린다.
 */
@Composable
fun KdCard(
    modifier: Modifier = Modifier,
    borderColor: Color = Color.Unspecified,
    background: Color = Kd.Surface,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 32.dp,
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    val accented = borderColor.isSpecified
    val outline = if (accented) Modifier.border(BorderStroke(2.dp, borderColor.copy(alpha = 0.55f)), Kd.CardShape) else Modifier
    val tint = if (accented) Modifier.background(borderColor.copy(alpha = 0.06f)) else Modifier
    Column(
        modifier = modifier
            .clip(Kd.CardShape)
            .background(background)
            .then(tint)
            .then(outline)
            .then(clickable)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** 상태 기호 동그라미: 옅은 상태색 바탕 + 진한 상태색 기호 */
@Composable
fun KdToneDot(tone: KdTone, size: Dp = 48.dp, modifier: Modifier = Modifier, symbol: String = tone.symbol) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(tone.color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            color = tone.color,
            fontSize = (size.value * 0.48f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 상태 배지: 동그라미 기호 + 글자 */
@Composable
fun KdStatusBadge(tone: KdTone, text: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        KdToneDot(tone, size = if (large) 68.dp else 48.dp)
        Spacer(Modifier.width(if (large) 20.dp else 16.dp))
        Text(text, style = if (large) Kd.Headline else Kd.Title)
    }
}

/** 작은 꼬리표(예: "시연 데이터", "시연 모드"): 옅은 색 바탕 + 같은 색 글자 */
@Composable
fun KdTag(text: String, color: Color = Kd.Caution, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = color,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

enum class KdButtonStyle { PRIMARY, SECONDARY, QUIET }

@Composable
fun KdButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KdButtonStyle = KdButtonStyle.PRIMARY,
    enabled: Boolean = true,
    minHeight: Dp = Kd.MinTouch,
    subText: String? = null,
    icon: ImageVector? = null,
) {
    val (bg, fg) = when (style) {
        KdButtonStyle.PRIMARY -> Kd.Primary to Kd.OnPrimary
        KdButtonStyle.SECONDARY -> Kd.SurfaceRaised to Kd.Ink
        KdButtonStyle.QUIET -> Color.Transparent to Kd.InkMuted
    }
    val alpha = if (enabled) 1f else 0.4f
    val quietBorder = if (style == KdButtonStyle.QUIET) Modifier.border(2.dp, Kd.Outline, Kd.ButtonShape) else Modifier
    Column(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(Kd.ButtonShape)
            .background(bg.copy(alpha = bg.alpha * alpha))
            .then(quietBorder)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg.copy(alpha = alpha), modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text,
                color = fg.copy(alpha = alpha),
                fontSize = 28.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        if (subText != null) {
            Text(
                subText,
                color = fg.copy(alpha = 0.75f * alpha),
                fontSize = 22.sp,
                lineHeight = 30.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 큰 메뉴 타일: 왼쪽 아이콘, 제목·설명, 오른쪽 화살표. 주어진 높이를 모두 채운다.
 * [highlighted]면 주 버튼 색(지금 권하는 행동). [enabled]가 false면 흐리게 보이고 눌리지 않는다(주행 중 홈).
 */
@Composable
fun KdNavTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    enabled: Boolean = true,
) {
    val bg = if (highlighted) Kd.Primary else Kd.Surface
    val fg = if (highlighted) Kd.OnPrimary else Kd.Ink
    val sub = if (highlighted) Kd.OnPrimary.copy(alpha = 0.75f) else Kd.InkMuted
    val iconBg = if (highlighted) Kd.OnPrimary.copy(alpha = 0.14f) else Kd.AccentContainer
    val iconFg = if (highlighted) Kd.OnPrimary else Kd.Accent
    Row(
        modifier = modifier
            .heightIn(min = Kd.MinTouch)
            .clip(Kd.CardShape)
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconFg, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = fg, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = sub, fontSize = 22.sp, lineHeight = 30.sp)
        }
        Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = null, tint = sub, modifier = Modifier.size(40.dp))
    }
}

/** 화면 머리: [← 뒤로] + 제목 + 오른쪽 꼬리표 자리 */
@Composable
fun KdTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backLabel: String = "← 홈",
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (onBack != null) {
            // 화살표는 아이콘으로 그리고 글자에서는 뺀다(글꼴마다 '←' 모양이 달라 어색했다)
            KdButton(
                backLabel.removePrefix("←").trim(),
                onClick = onBack,
                style = KdButtonStyle.SECONDARY,
                minHeight = 80.dp,
                icon = Icons.Rounded.ArrowBack,
            )
        }
        Text(title, style = Kd.Headline, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** 수치 하나(설명 + 큰 숫자) */
@Composable
fun KdStat(value: String, label: String, modifier: Modifier = Modifier, tone: KdTone? = null) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Kd.Eyebrow)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tone != null) {
                Text(tone.symbol + " ", color = tone.color, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(value, style = Kd.Numeral)
        }
    }
}

/** 화면 바탕: 어두운 바탕 + 시스템 바 피하기 + 바깥 여백 */
@Composable
fun KdScreen(
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    testTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
    val tag = if (testTag != null) Modifier.testTag(testTag) else Modifier
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Kd.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .then(scroll)
            .padding(horizontal = Kd.ScreenPadding, vertical = 32.dp)
            .then(tag),
        verticalArrangement = Arrangement.spacedBy(Kd.Gap),
        content = content,
    )
}

@Composable
fun KdSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Kd.Title, modifier = modifier.padding(top = 8.dp))
}

@Composable
fun KdDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Kd.Outline))
}
