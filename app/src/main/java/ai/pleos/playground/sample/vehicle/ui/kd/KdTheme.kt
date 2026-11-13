package ai.pleos.playground.sample.vehicle.ui.kd

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
 * - :departure-ui(2번)는 이 파일을 쓸 수 없어 같은 값을 departure-ui/.../DepartureColors.kt 에 따로 둔다. 바꾸면 둘 다 바꾼다
 */
object Kd {
    // 바탕과 면: 현대차 브랜드 '현대 블루(#002C5F)' 계열로 살짝 물든 남색 검정. 순수 회색보다 차 안 화면과 어울린다
    val Background = Color(0xFF080C13)
    val Surface = Color(0xFF121A25)
    val SurfaceRaised = Color(0xFF1B2532)
    val SurfaceHigh = Color(0xFF263242)
    val Outline = Color(0xFF243142) // 꼭 필요한 곳(구분선·표 눈금)에만
    val OutlineStrong = Color(0xFF3A4A5E)

    // 글자
    val Ink = Color(0xFFEEF3F8)
    val InkMuted = Color(0xFF9CAABB) // Surface 위 대비 약 7.5:1
    val InkSubtle = Color(0xFF7F8EA1) // 눈금 숫자 등 부수 정보만
    val InkOnAccent = Color(0xFF071526) // 상태색·밝은 강조색 위 글자

    // 강조: 현대 '액티브 블루'(#00AAD2). 주 버튼은 이 색 + 남색 글자, 글자·아이콘 강조는 조금 밝힌 값
    val Primary = Color(0xFF00AAD2)
    val OnPrimary = Color(0xFF04213A)
    val Accent = Color(0xFF45C6E8)
    val AccentContainer = Color(0xFF0E2A3B)
    /** 홈 대표 카드 한 장에만 쓰는 은은한 남색 빛(위 → 아래) */
    val HeroGlow = Color(0xFF0F3256)

    // 상태(예약 색 — 다른 용도로 쓰지 않는다)
    val Good = Color(0xFF46D293)
    val Caution = Color(0xFFF5C04E)
    val Serious = Color(0xFFF7944F)
    val Danger = Color(0xFFF46A6A)
    val Unknown = Color(0xFF95A3B5)

    // 글자: 크기는 고령 운전자 기준 그대로, '전부 굵게' 대신 굵기로 위계를 만든다
    // 큰 숫자는 보통 굵기(차량 계기판처럼 가볍게), 제목은 SemiBold, 본문은 Regular
    val Display = TextStyle(fontSize = 64.sp, lineHeight = 74.sp, fontWeight = FontWeight.SemiBold, color = Ink, letterSpacing = (-1).sp)
    val Headline = TextStyle(fontSize = 38.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, color = Ink, letterSpacing = (-0.5).sp)
    val Title = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, color = Ink, letterSpacing = (-0.3).sp)
    val Body = TextStyle(fontSize = 26.sp, lineHeight = 38.sp, color = Ink)
    val BodyMuted = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, color = InkMuted)
    val Label = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    /** 카드가 무엇을 말하는지 알려 주는 작은 제목("오늘 컨디션" 등). 카드 주제가 한눈에 안 보일 때만 쓴다 */
    val Eyebrow = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = InkMuted)
    /** 큰 숫자(수치 카드) */
    val Numeral = TextStyle(fontSize = 52.sp, lineHeight = 60.sp, fontWeight = FontWeight.Normal, color = Ink, letterSpacing = (-1).sp)

    val CardShape = RoundedCornerShape(32.dp)
    val ButtonShape = RoundedCornerShape(20.dp)
    val ScreenPadding = 40.dp
    val Gap = 24.dp
    val MinTouch = 96.dp
}

/** 상태 하나 = 색 + 기호 + 글자. 화면 어디서든 이 세트로만 상태를 보여 준다. */
enum class KdTone(val color: Color, val symbol: String) {
    GOOD(Kd.Good, "✓"),
    CAUTION(Kd.Caution, "!"),
    SERIOUS(Kd.Serious, "!!"),
    DANGER(Kd.Danger, "!!!"),
    UNKNOWN(Kd.Unknown, "?"),
    INFO(Kd.Accent, "i"),
}

private val KdColorScheme = darkColorScheme(
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

@Composable
fun KdTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KdColorScheme, content = content)
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
 * [highlighted]면 주 버튼 색(지금 권하는 행동).
 */
@Composable
fun KdNavTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
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
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconFg, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.width(24.dp))
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
