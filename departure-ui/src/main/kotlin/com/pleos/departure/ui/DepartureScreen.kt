package com.pleos.departure.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.pleos.departure.DepartureFeature
import com.pleos.departure.ui.state.DepartureContent
import com.pleos.departure.ui.state.DepartureDisplayMapper
import com.pleos.departure.ui.state.DepartureScreenModel
import com.pleos.departure.ui.state.DepartureUiState
import com.pleos.departure.ui.state.DisplayLine
import com.pleos.departure.ui.state.RouteCard
import com.pleos.departure.ui.state.StatusCard
import com.pleos.departure.ui.state.StatusIcon
import com.pleos.departure.ui.state.Tone

/*
 * 2번 '출발 전' 화면(작업 #7). 고령 운전자 기준:
 * - 큰 글자(본문 30sp 이상 — [BodyTextSize]), 어두운 바탕 + 흰 글자(고대비)
 * - 출발 자제 단계의 반경 카드처럼 참고용 카드(StatusCard.secondary)는 작게·얇은 테두리 + "참고" 글자로 그려 주 메시지와 경쟁하지 않게 한다
 * - 상태는 색 + 기호 아이콘 + 제목 문구를 함께 쓴다(색만으로 구분하지 않음)
 * - 버튼은 높이 96dp 이상, 주행 중에는 숨긴다
 * - 가장 중요한 출발 마감 카드를 왼쪽에 크게, 반경·경로는 오른쪽에
 * 문구는 state/DepartureDisplayMapper 에서 만든다. 이 파일은 그리기만 한다.
 */

private val Background = Color(0xFF000000)
private val CardBackground = Color(0xFF1C1C1C)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFE0E0E0)
private val ButtonColor = Color(0xFF90CAF9)

/** 본문 글자 크기. 화면 머리 주석의 "본문 30sp 이상"과 맞춘다(작업 #9, 리뷰 task-07 MINOR-4 — 이전 28sp). */
private val BodyTextSize = 30.sp
private val BodyLineHeight = 40.sp

internal fun toneColor(tone: Tone): Color = when (tone) {
    Tone.OK -> Color(0xFF66BB6A)
    Tone.INFO -> Color(0xFF64B5F6)
    Tone.CAUTION -> Color(0xFFFFCA28)
    Tone.DANGER -> Color(0xFFFF5252)
    Tone.UNKNOWN -> Color(0xFFBDBDBD)
}

/** 아이콘 기호. 색을 구분하기 어려워도 모양으로 의미가 전달되게 한다. */
internal fun iconGlyph(icon: StatusIcon): String = when (icon) {
    StatusIcon.CHECK -> "✓"
    StatusIcon.INFO -> "i"
    StatusIcon.WARNING -> "!"
    StatusIcon.STOP -> "✕"
    StatusIcon.NIGHT -> "☾"
    StatusIcon.QUESTION -> "?"
    StatusIcon.CLOCK -> "◷"
    StatusIcon.WAIT -> "…"
}

/** 화면 수명에 맞춘 상태 홀더를 만든다(DI 없이 생성자 주입). :app 이 시연 제어와 공유하려면 이것으로 만든 객체를 넘긴다. */
@Composable
fun rememberDepartureScreenModel(feature: DepartureFeature): DepartureScreenModel {
    val scope = rememberCoroutineScope()
    return remember(feature) { DepartureScreenModel(feature, scope) }
}

/**
 * '출발 전' 화면. 화면이 보이면(진입·백그라운드 복귀) 확인을 시작하고, 가려지면 멈춘다.
 */
@Composable
fun DepartureScreen(
    model: DepartureScreenModel,
    modifier: Modifier = Modifier,
) {
    LifecycleStartEffect(model) {
        model.onVisible()
        onStopOrDispose { model.onHidden() }
    }
    val state by model.state.collectAsState()
    val naviSettingsFailed by model.naviSettingsFailed.collectAsState()
    DepartureScreenContent(
        state = state,
        onRefresh = model::refresh,
        onConfirmToggle = model::confirmTollAvoidanceTurnedOn,
        modifier = modifier,
        onOpenNaviSettings = model::openNaviSettings,
        naviSettingsFailed = naviSettingsFailed,
    )
}

@Composable
fun DepartureScreenContent(
    state: DepartureUiState,
    onRefresh: () -> Unit,
    onConfirmToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenNaviSettings: () -> Unit = {},
    naviSettingsFailed: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        state.drivingNotice?.let { Banner(text = it, color = toneColor(Tone.CAUTION)) }
        if (state.showLoadingIndicator && state.content != null) LoadingBanner()
        if (naviSettingsFailed && state.controlsEnabled) Banner(text = DepartureDisplayMapper.NAVI_SETTINGS_FALLBACK, color = toneColor(Tone.CAUTION))

        val content = state.content
        if (content == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (state.showLoadingIndicator) LoadingBanner() else Text("", color = TextPrimary)
            }
        } else {
            ResultContent(content, state.controlsEnabled, onConfirmToggle, onOpenNaviSettings, Modifier.weight(1f))
        }

        if (state.controlsEnabled) {
            BigButton(
                text = if (state.loading) "확인 중이에요" else "다시 확인",
                enabled = !state.loading,
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ResultContent(
    content: DepartureContent,
    controlsEnabled: Boolean,
    onConfirmToggle: () -> Unit,
    onOpenNaviSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        content.conditionNotice?.let { CardView(it) }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                CardView(content.main, size = CardSize.LARGE)
            }
            if (content.radius != null || content.route != null) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    content.radius?.let { CardView(it, size = if (it.secondary) CardSize.SECONDARY else CardSize.NORMAL) }
                    content.route?.let { RouteCardView(it, controlsEnabled, onConfirmToggle, onOpenNaviSettings) }
                }
            }
        }
    }
}

/** 카드 크기. LARGE = 출발 마감(주 메시지), SECONDARY = 참고용(StatusCard.secondary). */
private enum class CardSize { LARGE, NORMAL, SECONDARY }

@Composable
private fun CardView(card: StatusCard, size: CardSize = CardSize.NORMAL, extra: @Composable () -> Unit = {}) {
    val color = toneColor(card.tone)
    val border = when {
        size == CardSize.SECONDARY -> 2.dp
        card.tone == Tone.UNKNOWN -> 4.dp
        else -> 6.dp
    }
    val iconBox = when (size) { CardSize.LARGE -> 80.dp; CardSize.NORMAL -> 64.dp; CardSize.SECONDARY -> 48.dp }
    val iconText = when (size) { CardSize.LARGE -> 48.sp; CardSize.NORMAL -> 38.sp; CardSize.SECONDARY -> 30.sp }
    val titleSize = when (size) { CardSize.LARGE -> 40.sp; CardSize.NORMAL -> 34.sp; CardSize.SECONDARY -> 30.sp }
    val titleLine = when (size) { CardSize.LARGE -> 50.sp; CardSize.NORMAL -> 42.sp; CardSize.SECONDARY -> 40.sp }
    val headlineSize = when (size) { CardSize.LARGE -> 52.sp; CardSize.NORMAL -> 34.sp; CardSize.SECONDARY -> 30.sp }
    val headlineLine = when (size) { CardSize.LARGE -> 62.sp; CardSize.NORMAL -> 42.sp; CardSize.SECONDARY -> 40.sp }
    Column(
        Modifier
            .fillMaxWidth()
            .border(BorderStroke(border, color), RoundedCornerShape(20.dp))
            .background(CardBackground, RoundedCornerShape(20.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (size == CardSize.SECONDARY) {
            // 색이 아니라 글자로도 "참고"임을 알린다.
            Text("참고", color = TextSecondary, fontSize = BodyTextSize, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(iconBox).background(color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(iconGlyph(card.icon), color = Color.Black, fontSize = iconText, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(24.dp))
            Text(card.title, color = TextPrimary, fontSize = titleSize, fontWeight = FontWeight.Bold, lineHeight = titleLine)
        }
        card.headline?.let {
            val headlineColor = if (size == CardSize.SECONDARY) TextPrimary else color
            Text(it, color = headlineColor, fontSize = headlineSize, fontWeight = FontWeight.Black, lineHeight = headlineLine)
        }
        card.lines.forEach { LineText(it) }
        extra()
    }
}

@Composable
private fun LineText(line: DisplayLine) {
    Text(
        text = line.text,
        color = if (line.emphasis) TextPrimary else TextSecondary,
        fontSize = BodyTextSize,
        lineHeight = BodyLineHeight,
        fontWeight = if (line.emphasis) FontWeight.Bold else FontWeight.Normal,
    )
}

@Composable
private fun RouteCardView(route: RouteCard, controlsEnabled: Boolean, onConfirmToggle: () -> Unit, onOpenNaviSettings: () -> Unit) {
    CardView(route.card) {
        if (route.showOpenNaviSettings && controlsEnabled) {
            BigButton(text = "내비 설정 열기", enabled = true, onClick = onOpenNaviSettings, modifier = Modifier.fillMaxWidth())
        }
        route.stepsIntro?.let { LineText(DisplayLine(it, emphasis = true)) }
        route.steps.forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(60.dp).background(TextPrimary, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = Color.Black, fontSize = 34.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(20.dp))
                Text(step, color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
            }
        }
        if (route.showConfirmToggle && controlsEnabled) {
            BigButton(text = "켰어요", enabled = true, onClick = onConfirmToggle, modifier = Modifier.fillMaxWidth())
        }
        route.notes.forEach { LineText(it) }
    }
}

@Composable
private fun BigButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 96.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ButtonColor,
            contentColor = Color.Black,
            disabledContainerColor = Color(0xFF555555),
            disabledContentColor = TextPrimary,
        ),
    ) {
        Text(text, fontSize = 36.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().border(BorderStroke(4.dp, color), RoundedCornerShape(16.dp)).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text("!", color = Color.Black, fontSize = 36.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(20.dp))
        Text(text, color = TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    }
}

/** 2초 넘게 걸릴 때 띄우는 "확인 중" 표시(심사 가이드 — DepartureConfig.loadingIndicatorThreshold). */
@Composable
private fun LoadingBanner() {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = TextPrimary, strokeWidth = 6.dp, modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(24.dp))
        Text("확인 중이에요…", color = TextPrimary, fontSize = 36.sp, fontWeight = FontWeight.Bold)
    }
}
