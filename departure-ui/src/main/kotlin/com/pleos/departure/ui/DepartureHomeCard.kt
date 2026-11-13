package com.pleos.departure.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.pleos.departure.ui.state.DepartureScreenModel
import com.pleos.departure.ui.state.HomeCardLine
import com.pleos.departure.ui.state.HomeCardState
import com.pleos.departure.ui.state.HomeCardSummary

/*
 * 홈 카드(작업 #24, 임시). 앱 홈에 놓는 '오늘 운전해도 되나요?' 요약 1장. 누르면 전체 화면([DepartureScreen])을 연다.
 * - 판정 34sp, 핵심 값 30sp, 요약 26sp(Pleos Body 30/28/26), 카드 전체가 누름 영역(최소 높이 160dp), "자세히 보기" 72dp 이상
 * - 상태는 색 + 기호 + 문구를 함께 쓴다. 문구는 state/HomeCardSummary 에서 만든다
 * - 같은 [DepartureScreenModel]을 전체 화면과 공유한다. 홈 카드와 전체 화면을 동시에 띄우지 않는 전제(한 번에 하나만 보임)
 */

private val CardBackground = DepartureColors.Surface
private val TextPrimary = DepartureColors.Ink
private val TextSecondary = DepartureColors.InkMuted

/** 홈 카드. 보이는 동안 확인을 돌리고, 가려지면 멈춘다(전체 화면과 같은 규칙). */
@Composable
fun DepartureHomeCard(
    model: DepartureScreenModel,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LifecycleStartEffect(model) {
        model.onVisible()
        onStopOrDispose { model.onHidden() }
    }
    val ui by model.state.collectAsState()
    DepartureHomeCardContent(state = HomeCardSummary.from(ui), onOpen = onOpen, modifier = modifier)
}

@Composable
fun DepartureHomeCardContent(
    state: HomeCardState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = toneColor(state.tone)
    val shape = RoundedCornerShape(32.dp)
    // 홈의 대표 카드: 위에서 아래로 은은한 남색 빛(현대 블루). 상태색은 기호와 얇은 테두리에만 쓴다
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(DepartureColors.HeroGlow, CardBackground)))
            .border(1.5.dp, accent.copy(alpha = 0.45f), shape)
            .clickable(enabled = state.openEnabled, role = Role.Button, onClickLabel = "출발 전 확인 자세히 보기", onClick = onOpen)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("오늘 운전해도 되나요?", color = TextSecondary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        // 카드가 높을 때 본문을 세로 가운데로(내용이 길면 이 여백은 0이 된다)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToneDot(glyph = iconGlyph(state.icon), color = accent, size = 68.dp)
            Text(
                state.verdict,
                color = TextPrimary,
                fontSize = 34.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
        state.headline?.let {
            // 핵심 값(예: 출발 마감 시각)은 카드에서 가장 크게
            Text(it, color = TextPrimary, fontSize = 52.sp, lineHeight = 62.sp, fontWeight = FontWeight.Normal, letterSpacing = (-1).sp)
        }
        state.lines.forEach { HomeLine(it) }
        Spacer(Modifier.weight(1f))
        if (state.openEnabled) {
            Text(
                "자세히 보기 ›",
                color = DepartureColors.Info,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun HomeLine(line: HomeCardLine) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(DepartureColors.Background.copy(alpha = 0.45f)).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToneDot(glyph = iconGlyph(line.icon), color = toneColor(line.tone), size = 36.dp)
        Text(line.text, color = TextPrimary, fontSize = 24.sp, lineHeight = 32.sp, modifier = Modifier.padding(start = 14.dp))
    }
}

/** 옅은 상태색 동그라미 + 진한 상태색 기호(앱 KdToneDot 과 같은 모양) */
@Composable
internal fun ToneDot(glyph: String, color: Color, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = color, fontSize = (size.value * 0.48f).sp, fontWeight = FontWeight.Bold)
    }
}
