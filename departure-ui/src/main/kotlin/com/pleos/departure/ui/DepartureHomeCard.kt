package com.pleos.departure.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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

private val CardBackground = Color(0xFF1C1C1C)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFE0E0E0)

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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .background(CardBackground, RoundedCornerShape(20.dp))
            .border(3.dp, accent, RoundedCornerShape(20.dp))
            .clickable(enabled = state.openEnabled, role = Role.Button, onClickLabel = "출발 전 확인 자세히 보기", onClick = onOpen)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("오늘 운전해도 되나요?", color = TextSecondary, fontSize = 26.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).background(accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(iconGlyph(state.icon), color = Color.Black, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                state.verdict,
                color = TextPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        state.headline?.let { Text(it, color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.SemiBold) }
        state.lines.forEach { HomeLine(it) }
        if (state.openEnabled) {
            Text(
                "자세히 보기 ›",
                color = accent,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.End).heightIn(min = 72.dp).padding(top = 20.dp),
            )
        }
    }
}

@Composable
private fun HomeLine(line: HomeCardLine) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(iconGlyph(line.icon), color = toneColor(line.tone), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(line.text, color = TextSecondary, fontSize = 26.sp, modifier = Modifier.padding(start = 12.dp))
    }
}
