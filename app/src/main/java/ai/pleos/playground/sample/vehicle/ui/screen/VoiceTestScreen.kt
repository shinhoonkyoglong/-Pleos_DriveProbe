package ai.pleos.playground.sample.vehicle.ui.screen

import ai.pleos.playground.sample.vehicle.data.local.entity.InterventionHistoryEntity
import ai.pleos.playground.sample.vehicle.intervention.VoicePhase
import ai.pleos.playground.sample.vehicle.intervention.SpeechBackend
import ai.pleos.playground.sample.vehicle.intervention.VoiceTestMode
import ai.pleos.playground.sample.vehicle.intervention.VoiceTestOutcome
import ai.pleos.playground.sample.vehicle.intervention.VoiceTestState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun VoiceTestScreen(
    state: VoiceTestState,
    latest: InterventionHistoryEntity?,
    onMicrophone: () -> Unit,
    onRefresh: () -> Unit,
    onStart: (VoiceTestMode) -> Unit,
    onStop: () -> Unit,
) {
    val idle = !state.running
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("정차 중 음성 시험", style = MaterialTheme.typography.headlineSmall)
        Text("1. 질문 소리 확인 → 2. ‘괜찮아요’ 응답 → 3. 안내 동의·거절 확인")
        if (!state.allowed) Text(state.blockedReason, color = MaterialTheme.colorScheme.error)
        state.capabilities?.let { c ->
            Text("인식 경로: ${if (c.recognitionBackend == SpeechBackend.PLEOS_ON_DEVICE) "Pleos STT SDK · 기기 내 인식" else "Android SpeechRecognizer"}")
            Text("재생 경로: ${when (c.ttsBackend) {
                SpeechBackend.PLEOS_ON_DEVICE -> "Pleos SDK · 기기 내 음성"
                SpeechBackend.ANDROID -> "Android TTS 엔진"
                SpeechBackend.NONE -> "사용 가능한 엔진 없음"
            }}")
            Text("재생 서비스 ${if (c.ttsServices.isNotEmpty()) "등록됨" else "없음"} · 인식 서비스 ${if (c.recognitionAvailable) "등록됨" else "없음"} · 마이크 ${if (c.microphoneGranted) "허용됨" else "권한 필요"}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onMicrophone, enabled = idle && state.allowed) { Text("마이크 권한 허용") }
            OutlinedButton(onClick = onRefresh, enabled = idle) { Text("서비스 다시 확인") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onStart(VoiceTestMode.PLAYBACK) }, enabled = idle && state.allowed) { Text("1. 질문만 재생") }
            Button(onClick = { onStart(VoiceTestMode.RESPONSE) }, enabled = idle && state.allowed) { Text("2. 응답 시험") }
            Button(onClick = { onStart(VoiceTestMode.CONSENT) }, enabled = idle && state.allowed) { Text("3. 동의·거절 시험") }
            OutlinedButton(onClick = onStop, enabled = state.running && state.phase != VoicePhase.SAVING) { Text("시험 중지") }
        }
        OutlinedButton(onClick = { onStart(VoiceTestMode.TIMING) }, enabled = idle && state.allowed) { Text("4. 응답 시간 시험") }
        Text("정차 단계 연결 시험 · 실제 개입의 질문·응답 처리를 확인해요. 피로 점수와 경로는 변경하지 않아요.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onStart(VoiceTestMode.CAUTION_FLOW) }, enabled = idle && state.allowed) { Text("5. CAUTION 연결 시험") }
            OutlinedButton(onClick = { onStart(VoiceTestMode.DANGER_FLOW) }, enabled = idle && state.allowed) { Text("6. DANGER 연결 시험") }
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.message, style = MaterialTheme.typography.titleMedium)
                state.mode?.let { Text("질문: ${it.prompt}") }
                state.result?.let { result ->
                    Text("결과: ${voiceTestOutcomeLabel(result.outcome.name)}")
                    Text("인식 문장: ${result.answer?.text ?: "없음"}")
                    val estimated = result.answer?.responseTiming?.endsWith("_ESTIMATE") == true
                    Text("응답 시간${if (estimated) " (발화 추정)" else ""}: ${result.answer?.responseMs?.let { "${it}ms" } ?: "측정 없음"}")
                    if (state.mode in setOf(VoiceTestMode.TIMING, VoiceTestMode.CAUTION_FLOW, VoiceTestMode.DANGER_FLOW)) {
                        Text("질문 재생 종료 → 녹음 시작 지연과 발화 시작 위치를 합산해요. 발화 검출·오디오 버퍼 지연에 따른 오차가 있어요.")
                        Text("시간 기준: ${when (result.answer?.responseTiming) {
                            "PCM_AUDIO_TIMESTAMP_EPD_ESTIMATE" -> "오디오 프레임 시각 + 발화 위치"
                            "PCM_READ_RECEIPT_EPD_ESTIMATE" -> "버퍼 수신 시각 + 발화 위치 (추정)"
                            else -> "발화 위치 또는 시간 기준 수신 없음"
                        }}")
                        result.answer?.captureLatencyMs?.let { Text("녹음 시작 지연 추정: ${it}ms") }
                    }
                    if (result.answer?.responseTiming == "UNMEASURED_PLEOS_EPD_BYTE_OFFSETS") {
                        Text("Pleos SDK는 발화 위치를 오디오 바이트 단위로 제공해요. 현재 응답 시간(ms)은 계산하지 않아요.")
                        Text("입력 레벨 수신: ${result.answer.inputLevelCallbacks ?: 0}회 · 최대 RMS: ${result.answer.maxInputRms ?: "수신 없음"}")
                    }
                    result.error?.let { Text("진단 코드: $it / ${result.platformCode ?: "없음"}") }
                    result.intervention?.let { stage ->
                        Text("단계 처리 결과: ${stage.outcome}")
                        stage.navigationStatus?.let { Text("내비 연동: ${if (it == "NOT_CONNECTED") "준비 중 · 경로 변경 없음" else "요청하지 않음"}") }
                    }
                    result.answer?.platformCode?.let { Text("인식 서비스 코드: $it") }
                }
                state.savedId?.let { Text("시험 이력 저장 완료 (#$it)") }
                state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        Text("‘응답 대기 중’이 나오면 말씀해 주세요. 무응답 시험은 질문 후 10초 동안 말하지 않으면 돼요.")
        Text("응답 시간은 질문 재생 완료부터 발화 감지까지이며 인식 엔진 준비 지연이 포함될 수 있어요. 감지 콜백이 없으면 측정 없음으로 표시해요.")
        Text("마이크 입력은 응답·동의·시간 시험에서 질문 재생 후 시작돼요. 화면을 나가거나 차량 상태가 바뀌면 중지돼요. 음성 원본은 앱에 저장하지 않고 인식 문장·시간·결과만 저장해요.")
        Text("이 시험은 차량·경로를 변경하지 않아요. 실제 운전 기록과 구분해 저장해요.")
        latest?.let {
            val details = remember(it.actionDetails) { runCatching { JSONObject(it.actionDetails) }.getOrNull() }
            Text("최근 저장된 음성 시험", style = MaterialTheme.typography.titleMedium)
            Text("${voiceTestOutcomeLabel(details?.optString("outcome"))} · ${it.userResponse ?: "인식 문장 없음"}")
            Text("저장된 응답 시간${if (details?.optString("responseTiming")?.endsWith("_ESTIMATE") == true) " (발화 추정)" else ""}: ${if (details == null || details.isNull("responseMs")) "측정 없음" else "${details.optLong("responseMs")}ms"}")
        }
    }
}

internal fun voiceTestOutcomeLabel(name: String?): String = when (name) {
    "PLAYBACK_COMPLETED" -> "재생 완료 콜백 수신"
    "ANSWERED" -> "응답 인식"
    "ACCEPTED" -> "안내 동의"
    "DECLINED" -> "안내 거절"
    "UNKNOWN_ANSWER" -> "동의 여부 불명확"
    "NO_RESPONSE" -> "응답 시간 초과"
    "NO_MATCH" -> "문장 인식 실패"
    "PERMISSION_REQUIRED" -> "음성 서비스 권한 필요"
    "UNAVAILABLE" -> "음성 서비스 사용 불가"
    "CANCELLED" -> "시험 중지"
    "ERROR" -> "서비스 오류"
    else -> name ?: "기록 없음"
}
