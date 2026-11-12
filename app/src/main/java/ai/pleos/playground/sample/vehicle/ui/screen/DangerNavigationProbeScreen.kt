package ai.pleos.playground.sample.vehicle.ui.screen

import ai.pleos.playground.sample.vehicle.intervention.DANGER_PROBE_REVISION
import ai.pleos.playground.sample.vehicle.intervention.DangerNavigationProbeState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DangerNavigationProbeScreen(
    state: DangerNavigationProbeState,
    parked: Boolean,
    voiceReady: Boolean,
    voiceError: String?,
    onPrepareVoice: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DANGER 통합 시험 · 실제 효과", style = MaterialTheme.typography.headlineSmall)
        Text("실행 코드: $DANGER_PROBE_REVISION · 결과 코드: ${state.revision}")
        Text("1. 내비 조회에서 현재 경로의 휴게소 시설 확인을 저장하고 이름·ETA 미리보기를 확인하세요.")
        Text("2. P·속도 0을 유지하고 음성을 준비하세요. 권한을 허용한 뒤에는 준비 버튼을 다시 눌러 주세요.")
        OutlinedButton(enabled = parked && !state.running, onClick = onPrepareVoice) {
            Text(if (voiceReady) "주행 음성 준비됨" else "주행 음성 준비·마이크 권한")
        }
        voiceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("3. 시험 시작 후 질문이 끝나고 응답 대기 표시가 나오면 ‘추가해 줘’ 또는 ‘아니요’라고 말씀해 주세요.")
        Text("동의하면 실제 경유지가 추가되고 유지됩니다. 이 시험은 추가·복원 시험과 달리 자동 삭제하지 않아요. 원래 목적지·경유지 상태는 내비 화면에서 확인해 주세요.")
        Row {
            Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it }, enabled = !state.running)
            Text("음성 동의 시 실제 경유지를 추가하고 유지하는 시험임을 확인했어요")
        }
        Button(enabled = acknowledged && parked && voiceReady && !state.running, onClick = {
            acknowledged = false
            onStart()
        }) { Text("실제 DANGER 음성·내비 통합 시험 시작") }
        OutlinedButton(enabled = state.running, onClick = onCancel) { Text("통합 시험 취소") }
        Text("상태: ${state.phase} · 시험: ${state.runId.takeLast(8).ifBlank { "아직 없음" }}")
        Text(state.message)
        Text("음성 결과: ${state.outcome ?: "미확인"} · 동의: ${state.consent ?: "미확인"}")
        Text("내비 적용: ${state.navigationStatus ?: "미확인"} · 실제 요청: ${state.requestIssued}")
        state.savedId?.let { Text("시험 이력 #$it · 실제 피로 판단·주행 성과와 구분해 저장했어요") }
        state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.interrupted) Text("완료 미확인 — 내비 화면의 경유지를 직접 확인해 주세요", color = MaterialTheme.colorScheme.error)
        if (!parked) Text("P·속도 0·차량 화면 사용 가능 상태에서만 시작할 수 있어요")
        Text("내비가 전면으로 전환되거나 탭을 이동해도 시험은 이어집니다. 음성 준비 해제·정차 조건 이탈 시 취소하며, 이미 보낸 경로 요청은 적용됐을 수 있어요. 시험 종료 후 ‘개입 상태’에서 음성 준비를 해제할 수 있어요.")
    }
}
