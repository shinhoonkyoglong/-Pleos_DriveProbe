package ai.pleos.playground.sample.vehicle.trip.ui

import ai.pleos.playground.sample.vehicle.trip.SummaryTone
import ai.pleos.playground.sample.vehicle.trip.share.ShareResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 가족 보내기 버튼 상태 */
sealed interface FamilyShareState {
    data object Idle : FamilyShareState
    data object Sending : FamilyShareState
    data class Done(val result: ShareResult) : FamilyShareState
}

/**
 * [가족에게 보내기] 버튼 + 결과 한 줄 — **값만 받는** 순수 Composable(무엇을 어떻게 보내는지 모름).
 * 누르면 [onClick]. 보내는 일은 호스트가 한다(보통 `TripModule.shareToFamily(tripId)`).
 * 호스트 화면 어디에든 둘 수 있다(요약 아래, 상단 바 등).
 */
@Composable
fun FamilyShareButton(state: FamilyShareState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onClick,
            enabled = state !is FamilyShareState.Sending,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("share-to-family"),
            shape = RoundedCornerShape(16.dp),
        ) {
            val label = when {
                state is FamilyShareState.Sending -> "보내는 중…"
                state is FamilyShareState.Done && state.result is ShareResult.Sent -> "가족에게 다시 보내기"
                else -> "가족에게 보내기"
            }
            Text(label, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        val (text, tone) = when (state) {
            FamilyShareState.Idle -> "이 요약을 가족에게 보내요. 위치와 경로는 보내지 않아요." to InfoColor
            FamilyShareState.Sending -> "" to InfoColor
            is FamilyShareState.Done -> when (val r = state.result) {
                is ShareResult.Sent ->
                    if (r.isMock) "보냈어요. (임시: 가족 연결 전이라 실제로는 전송되지 않아요)" to InfoColor
                    else "가족에게 보냈어요." to SummaryTone.GOOD.color()
                is ShareResult.Failed -> r.reason to SummaryTone.WARN.color()
            }
        }
        if (text.isNotEmpty()) Text(text, fontSize = 18.sp, lineHeight = 26.sp, color = tone)
    }
}
