package ai.pleos.playground.sample.vehicle.ui.screen

import ai.pleos.playground.sample.vehicle.BuildConfig
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.data.local.entity.InterventionHistoryEntity
import ai.pleos.playground.sample.vehicle.intervention.InterventionStatus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.json.JSONObject
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun InterventionScreen(
    status: InterventionStatus,
    latest: InterventionHistoryEntity?,
    parked: Boolean,
    voicePrepared: Boolean,
    voicePreparationError: String?,
    onPrepareVoice: () -> Unit,
    onReleaseVoice: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onMicrophone: () -> Unit,
    onSimulate: (FatigueLevel) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("주행 중 개입", style = MaterialTheme.typography.headlineSmall)
        Text("자동 개입: ${if (status.enabled) "켜짐" else "꺼짐"} · 피로 단계: ${status.level}")
        Text(status.message)
        status.result?.let { Text("최근 결과: ${it.outcome} · 음성 반응${if (it.voiceAnswer?.responseTiming?.endsWith("_ESTIMATE") == true) " (발화 추정)" else ""}: ${it.responseMs?.let { ms -> "${ms}ms" } ?: "측정 없음"}") }
        Text("주행 음성: ${if (voicePrepared) "준비됨" else "준비 필요"}")
        voicePreparationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPrepareVoice, enabled = parked && !voicePrepared) { Text("주행 음성 준비") }
            OutlinedButton(onClick = onReleaseVoice, enabled = voicePrepared) { Text("음성 준비 해제") }
        }
        Text("출발 전 마이크 권한을 허용하고 주행 음성을 준비해 주세요. 준비 중에는 녹음하지 않으며, 실제 질문·응답 때만 마이크를 사용해요.")
        status.historyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onEnabled(!status.enabled) }) { Text(if (status.enabled) "개입 중지" else "개입 재개") }
            OutlinedButton(onClick = onMicrophone, enabled = parked) { Text("마이크 권한 허용") }
        }
        Text("현재 경로에서 시설·노선·방향을 확인 등록한 휴게소를 TBT 순서로 선택해요. 실제 ETA와 함께 제안하고 동의하면 경유지를 추가해요. 시설 확인이 없거나 경로가 바뀌면 자동 추가하지 않아요.")
        Text("자동 차량 제어는 실제 주행 모드의 유효한 D 기어 데이터에서 실행돼요. 시연 모드는 차량을 조작하지 않아요.")
        if (BuildConfig.DEBUG) {
            Text("시연 확인 — 차량·음성·내비 호출 없이 이력만 저장")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(FatigueLevel.MILD, FatigueLevel.CAUTION, FatigueLevel.DANGER).forEach { level ->
                    OutlinedButton(onClick = { onSimulate(level) }, enabled = parked && !status.running) { Text(level.name) }
                }
            }
        }
        Text("최근 저장 이력", style = MaterialTheme.typography.titleMedium)
        if (latest == null) Text("아직 저장된 개입이 없어요") else {
            val details = remember(latest.actionDetails) { runCatching { JSONObject(latest.actionDetails) }.getOrNull() }
            if (details?.optString("source") == "VOICE_TEST") {
                Text("음성 시험 · ${voiceTestOutcomeLabel(details.optString("outcome"))}")
            } else {
                Text("단계 ${latest.interventionLevel} · 점수 ${latest.fatigueScore}/100 · ${if (details?.optBoolean("simulation") == true) "시연" else "실행"}")
            }
            Text(details?.optString("details") ?: latest.actionDetails)
            latest.userResponse?.let { Text("응답: $it") }
        }
    }
}
