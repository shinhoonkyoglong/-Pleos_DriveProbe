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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * - 고령 운전자: 본문 24sp 이상, 제목 32sp 이상, 누르는 곳 높이 88dp 이상
 * - 어두운 바탕 + 고대비 글자(2번 홈 화면의 검은 바탕과 같은 방향, 밤에도 눈부시지 않게)
 * - 상태는 색만으로 구분하지 않는다: 늘 기호(✓ ! !! !!! ?) + 글자를 함께 쓴다
 */
object Kd {
    // 바탕과 면
    val Background = Color(0xFF0B0D12)
    val Surface = Color(0xFF161A22)
    val SurfaceRaised = Color(0xFF1F2430)
    val Outline = Color(0xFF3A4150)

    // 글자
    val Ink = Color(0xFFF5F7FA)
    val InkMuted = Color(0xFFB4BBC8)
    val InkOnAccent = Color(0xFF0B0D12)

    // 강조(2번 화면 버튼색 0xFF90CAF9 와 같은 계열)
    val Accent = Color(0xFF8AB4FF)
    val AccentContainer = Color(0xFF22324F)

    // 상태(예약 색 — 다른 용도로 쓰지 않는다)
    val Good = Color(0xFF5DD39E)
    val Caution = Color(0xFFFFC857)
    val Serious = Color(0xFFFF9F4A)
    val Danger = Color(0xFFFF6B6B)
    val Unknown = Color(0xFF9AA3B2)

    // 글자 크기
    val Display = TextStyle(fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.Bold, color = Ink)
    val Headline = TextStyle(fontSize = 40.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold, color = Ink)
    val Title = TextStyle(fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, color = Ink)
    val Body = TextStyle(fontSize = 26.sp, lineHeight = 36.sp, color = Ink)
    val BodyMuted = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, color = InkMuted)
    val Label = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = Ink)

    val CardShape = RoundedCornerShape(28.dp)
    val ButtonShape = RoundedCornerShape(24.dp)
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
    primary = Kd.Accent,
    onPrimary = Kd.InkOnAccent,
    primaryContainer = Kd.AccentContainer,
    onPrimaryContainer = Kd.Ink,
    background = Kd.Background,
    onBackground = Kd.Ink,
    surface = Kd.Surface,
    onSurface = Kd.Ink,
    onSurfaceVariant = Kd.InkMuted,
    outline = Kd.Outline,
    tertiary = Kd.Good,
    error = Kd.Danger,
)

@Composable
fun KdTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KdColorScheme, content = content)
}

/** 면 카드. onClick 이 있으면 카드 전체가 눌린다. */
@Composable
fun KdCard(
    modifier: Modifier = Modifier,
    borderColor: Color = Kd.Outline,
    background: Color = Kd.Surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        modifier = modifier
            .clip(Kd.CardShape)
            .background(background)
            .border(BorderStroke(2.dp, borderColor), Kd.CardShape)
            .then(clickable)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** 상태 배지: 동그라미 기호 + 글자 */
@Composable
fun KdStatusBadge(tone: KdTone, text: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val circle = if (large) 64.dp else 44.dp
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(circle).clip(CircleShape).background(tone.color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                tone.symbol,
                color = Kd.InkOnAccent,
                fontSize = if (large) 34.sp else 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = if (large) Kd.Headline else Kd.Title)
    }
}

/** 작은 꼬리표(예: "시연 데이터", "시연 모드") */
@Composable
fun KdTag(text: String, color: Color = Kd.Caution, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, color, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp),
        color = color,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
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
) {
    val (bg, fg, border) = when (style) {
        KdButtonStyle.PRIMARY -> Triple(Kd.Accent, Kd.InkOnAccent, Kd.Accent)
        KdButtonStyle.SECONDARY -> Triple(Kd.SurfaceRaised, Kd.Ink, Kd.Outline)
        KdButtonStyle.QUIET -> Triple(Color.Transparent, Kd.InkMuted, Kd.Outline)
    }
    val alpha = if (enabled) 1f else 0.38f
    Column(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(Kd.ButtonShape)
            .background(bg.copy(alpha = bg.alpha * alpha))
            .border(2.dp, border.copy(alpha = alpha), Kd.ButtonShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            color = fg.copy(alpha = alpha),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (subText != null) {
            Text(
                subText,
                color = fg.copy(alpha = 0.8f * alpha),
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
            )
        }
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
            KdButton(backLabel, onClick = onBack, style = KdButtonStyle.SECONDARY, minHeight = 80.dp)
        }
        Text(title, style = Kd.Headline, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** 수치 하나(큰 숫자 + 설명) */
@Composable
fun KdStat(value: String, label: String, modifier: Modifier = Modifier, tone: KdTone? = null) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tone != null) {
                Text(tone.symbol + " ", color = tone.color, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            }
            Text(value, color = Kd.Ink, fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, style = Kd.BodyMuted)
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
            .padding(Kd.ScreenPadding)
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
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(2.dp).background(Kd.Outline))
}
