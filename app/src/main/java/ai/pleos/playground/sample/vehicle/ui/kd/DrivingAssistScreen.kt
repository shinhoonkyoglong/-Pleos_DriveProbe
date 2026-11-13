package ai.pleos.playground.sample.vehicle.ui.kd

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
fun DrivingAssistScreen(state: DrivingAssistState, onBack: () -> Unit, onStart: () -> Unit,
    onStop: () -> Unit, onRestSettings: () -> Unit) {
    BackHandler(onBack = onBack)
    KdScreen(scrollable = true, testTag = "kd-driving-assist") {
        KdTopBar("주행 도움", onBack = onBack)
        KdCard(Modifier.fillMaxWidth(), borderColor = if (state.voiceReady && state.automaticEnabled) Kd.Good else Kd.Accent) {
            KdStatusBadge(if (state.voiceReady && state.automaticEnabled) KdTone.GOOD else KdTone.INFO, state.summary)
            Text(state.message, style = Kd.Body)
            Text("공조 도움 ${if (state.automaticEnabled) "켜짐" else "꺼짐"} · 음성 ${if (state.voiceReady) "준비됨" else "준비 필요"}", style = Kd.BodyMuted)
            state.voiceError?.let { Text(it, style = Kd.BodyMuted) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Kd.Gap)) {
                KdButton(if (state.starting) "음성 준비 중" else "주행 도움 켜기", onStart,
                    enabled = state.canConfigure && !state.loading && !(state.automaticEnabled && state.voiceReady),
                    modifier = Modifier.weight(1f).testTag("assist-start"))
                KdButton("주행 도움 끄기", onStop, style = KdButtonStyle.SECONDARY,
                    enabled = state.blockedReason.isEmpty() && !state.testBusy && (state.automaticEnabled || state.voiceReady || state.starting),
                    modifier = Modifier.weight(1f).testTag("assist-stop"))
            }
            Text("마이크는 질문에 답하는 동안에만 사용해요. 도움을 끄더라도 이미 바꾼 공조 설정과 경유지는 유지해요.", style = Kd.BodyMuted)
        }
        if (state.blockedReason.isNotEmpty()) KdStatusBadge(KdTone.CAUTION, state.blockedReason)
        if (state.busy) Text("도움을 처리하고 있어요. 설정은 처리가 끝난 뒤 바꿀 수 있어요.", style = Kd.BodyMuted)
        KdCard(Modifier.fillMaxWidth()) {
            Text("휴게소 음성 안내", style = Kd.Title)
            Text("주행 중 휴식이 필요하면 현재 경로의 다음 휴게소 또는 졸음쉼터와 예상 시간을 알려드려요. 명확히 동의한 경우에만 그 시설을 경유지로 추가해요.", style = Kd.Body)
            Text(state.candidate?.let { "최근 확인한 휴게소: ${it.place.name}" } ?: "출발 전에 안내할 휴게소의 시설·진행 방향을 확인해 주세요", style = Kd.BodyMuted)
            KdButton("휴게소 설정", onRestSettings, style = KdButtonStyle.SECONDARY,
                enabled = state.canConfigure, modifier = Modifier.fillMaxWidth().testTag("assist-rest-settings"))
        }
        KdCard(Modifier.fillMaxWidth()) {
            Text("주행 중 이렇게 도와드려요", style = Kd.Title)
            Text("가벼운 피로 신호에는 공조를 켜고 수동 모드로 바꾼 뒤, 온도를 1℃ 낮추고 풍량을 한 단계 높여요.", style = Kd.Body)
            Text("피로 신호가 커지면 음성으로 상태를 묻고, 휴식이 필요할 때 휴게소를 제안해요. 실제 주행 판단에 따라 자동으로 실행돼요.", style = Kd.Body)
            Text("음성 준비 없이 출발하면 음성 질문·휴게소 안내를 실행할 수 없어요. 출발 전에 이 화면에서 준비해 주세요.", style = Kd.BodyMuted)
        }
    }
}

@Composable
fun RestStopSettingsScreen(viewModel: DrivingAssistViewModel, state: DrivingAssistState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    DisposableEffect(viewModel) { onDispose { viewModel.leaveServiceAreaSettings() } }
    val editable = state.canConfigure && !state.loading
    KdScreen(scrollable = true, testTag = "kd-rest-stop-settings") {
        KdTopBar("휴게소 설정", onBack = onBack, backLabel = "← 주행 도움")
        Text("공식 휴게소·졸음쉼터 데이터에서 시설 좌표를 자동으로 찾아요. 내비 안내의 진행 방향이 일치하거나 공식 양방향 시설이면 즐겨찾기 등록 없이 연결해요. 방향이 불명확하면 정차 중 확인이 필요해요.", style = Kd.Body)
        if (state.blockedReason.isNotEmpty()) KdStatusBadge(KdTone.CAUTION, state.blockedReason)
        KdButton(if (state.loading) "휴게소 확인 중" else "현재 경로의 휴게소 불러오기",
            { viewModel.loadServiceAreas() }, enabled = editable, modifier = Modifier.fillMaxWidth().testTag("rest-settings-load"))
        Text(state.routeMessage, style = Kd.Body)
        state.candidate?.let { c ->
            KdCard(Modifier.fillMaxWidth(), borderColor = Kd.Good) {
                KdStatusBadge(KdTone.GOOD, "안내할 시설 확인됨")
                Text(c.place.name, style = Kd.Title)
                Text(listOf(c.place.roadName, "${c.place.direction} 방향").filter { it.isNotBlank() }.joinToString(" · "), style = Kd.Body)
                Text(when(c.verification) {
                    "PUBLIC_CATALOG_DIRECTION_MATCH" -> "공식 시설 데이터 · 기준일 ${c.place.dataDate}"
                    "NATIVE_TBT_EXPLICIT_DIRECTION" -> "내비 방향 표기·시설 자동 일치"
                    else -> "직접 확인한 시설"
                }, style = Kd.BodyMuted)
                Text("최근 조회 예상 소요 시간 약 ${(c.trip.etaSeconds - 1) / 60 + 1}분", style = Kd.BodyMuted)
                Text("실제 음성 제안 직전에 경로와 소요 시간을 다시 확인해요.", style = Kd.BodyMuted)
            }
        }
        state.session?.let { session ->
            Text("다음 휴게소: ${session.tbt.nextServiceAreaHint?.value?.description ?: "확인 필요"}", style = Kd.Title)
            session.places.forEach { place ->
                KdCard(Modifier.fillMaxWidth().testTag("rest-place-${place.id}"),
                    borderColor = if (state.selected == place) Kd.Accent else Kd.Outline,
                    onClick = if (editable) ({ viewModel.select(place) }) else null) {
                    Row {
                        RadioButton(selected = state.selected == place, onClick = { viewModel.select(place) }, enabled = editable)
                        Column {
                            Text(place.name, style = Kd.Title)
                            Text(place.address.ifBlank { "주소 정보 없음 — 내비에서 시설을 직접 확인해 주세요" }, style = Kd.BodyMuted)
                        }
                    }
                }
            }
            if (state.selected != null) {
                OutlinedTextField(value = state.road, onValueChange = viewModel::road, enabled = editable,
                    label = { Text("확인한 노선명", style = Kd.Label) }, placeholder = { Text("예: 영동고속도로", style = Kd.BodyMuted) },
                    textStyle = Kd.Body, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = state.direction, onValueChange = viewModel::direction, enabled = editable,
                    label = { Text("확인한 진행 방향", style = Kd.Label) }, placeholder = { Text("예: 강릉", style = Kd.BodyMuted) },
                    textStyle = Kd.Body, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row {
                    Checkbox(state.confirmed, viewModel::confirmDirection, enabled = editable)
                    Text("이 시설이 현재 노선·진행 방향의 휴게소임을 직접 확인했어요", style = Kd.Body)
                }
                KdButton("이 경로의 휴게소 확인 저장", { viewModel.saveServiceArea() },
                    enabled = editable && state.confirmed && state.road.isNotBlank() && state.direction.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("rest-settings-save"))
            }
            KdButton("이 경로의 확인 해제", { viewModel.forgetServiceArea() }, style = KdButtonStyle.QUIET,
                enabled = editable, modifier = Modifier.fillMaxWidth())
        }
        Text("시설 확인은 24시간 유효해요. 목적지·노선·방향을 유지한 재탐색은 확인을 유지하고, 경로나 다음 시설이 달라지면 다시 확인해 주세요. 음성 제안에 동의한 경유지는 유지해요.", style = Kd.BodyMuted)
    }
}
