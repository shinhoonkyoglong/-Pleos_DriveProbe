package ai.pleos.playground.sample.vehicle.ui.screen

import ai.pleos.playground.sample.vehicle.navigation.RestCandidate
import ai.pleos.playground.sample.vehicle.navigation.RestNavigation
import ai.pleos.playground.sample.vehicle.navigation.NaviTbtRoute
import ai.pleos.playground.sample.vehicle.navigation.facilityKind
import ai.pleos.playground.sample.vehicle.navigation.TbtFacilityKind
import ai.pleos.playground.sample.vehicle.navigation.ServiceAreaReview
import ai.pleos.playground.sample.vehicle.navigation.ServiceAreaReviewSession
import ai.pleos.playground.sample.vehicle.navigation.RestPlace
import ai.pleos.playground.sample.vehicle.navigation.NavigationProbeState
import ai.pleos.playground.sample.vehicle.navigation.WAYPOINT_VERIFICATION_REVISION
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Read-only lookup/review plus an explicitly selected parked emulator round-trip probe. */
@Composable
fun RestNavigationScreen(
    navigation: RestNavigation, review: ServiceAreaReview, probeState: NavigationProbeState,
    onStartProbe: () -> Unit, onCancelProbe: () -> Unit, allowed: Boolean,
) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("정차 상태에서 경로상 다음 휴게소를 조회해 주세요") }
    var candidates by remember { mutableStateOf(emptyList<RestCandidate>()) }
    var job by remember { mutableStateOf<Job?>(null) }
    var tbt by remember { mutableStateOf<NaviTbtRoute?>(null) }
    var reviewSession by remember { mutableStateOf<ServiceAreaReviewSession?>(null) }
    var selected by remember { mutableStateOf<RestPlace?>(null) }
    var road by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    DisposableEffect(job) {
        val running=job
        val handle=running?.invokeOnCompletion { if(job===running) job=null }
        onDispose { handle?.dispose() }
    }
    LaunchedEffect(allowed) { if (!allowed) job?.cancel() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("경로상 휴게소 조회", style = MaterialTheme.typography.headlineSmall)
        Text("실행 코드: $WAYPOINT_VERIFICATION_REVISION")
        Text("시설 확인·미리보기는 조회만 해요. 아래 실제 추가·복원 시험은 경유지를 실제 추가한 뒤 삭제해요. 음성은 사용하지 않아요.")
        Text(message)
        if(probeState.runId.isNotBlank() || probeState.message.isNotBlank()) {
            Text(if(probeState.running) "실제 추가·복원 시험 진행 중" else "최근 실제 추가·복원 시험 결과",style=MaterialTheme.typography.titleMedium)
            Text("결과 코드: ${probeState.verifierRevision.ifBlank { "이전 버전·미기록" }} · 시험: ${probeState.runId.take(8)}")
            Text(probeState.message)
            Text("추가: ${probeState.applicationStatus?.name ?: "미확인"} · 삭제·복원: ${probeState.cleanup?.name ?: "미확인"}")
            if(probeState.interrupted) Text("완료가 확인되지 않았어요. 내비의 경유지 상태를 확인해 주세요",color=MaterialTheme.colorScheme.error)
        }
        OutlinedButton(enabled=allowed && job?.isCompleted!=false && !probeState.running,onClick={
            candidates=emptyList();tbt=null
            job=scope.launch {
                message="현재 내비의 안내 목록을 읽고 있어요"
                try {
                    val received=navigation.readTbtRoute()
                    tbt=received
                    val next = received.nextRestStopHint?.value?.description
                    message="TBT ${received.entries.size}개 · 휴게소 후보 ${received.restStopHints.size}개 · 다음 후보 ${next ?: "확인 불가"}"
                } catch(e:CancellationException){message="조회가 취소됐어요";throw e}
                catch(e:Exception){message=e.message ?: "내비 안내 목록을 읽지 못했어요"}
            }
        }){Text("내비 안내 목록 읽기")}
        Text("시연 준비: 먼저 내비에서 현재 진행 방향의 휴게소를 즐겨찾기에 저장하세요. 원래 목적지·경로는 유지해 주세요.")
        OutlinedButton(enabled=allowed && job?.isCompleted!=false && !probeState.running,onClick={
            selected=null;reviewSession=null;confirmed=false;road="";direction=""
            job=scope.launch {
                message="현재 경로와 내비에 저장된 휴게소 시설을 확인하고 있어요"
                try {
                    val session=review.load();reviewSession=session;tbt=session.tbt
                    message=if(session.receivedBookmarkCount==0) "내비가 즐겨찾기 0개를 반환했어요. 휴게소 시설을 즐겨찾기에 저장한 뒤 다시 불러와 주세요"
                        else if(session.places.isEmpty()) "내비 즐겨찾기에서 다음 휴게소 이름과 일치하는 시설을 못 찾았어요. 표시 이름을 확인해 주세요"
                        else "현재 노선·진행 방향의 시설을 선택하고 직접 확인해 주세요. 같은 이름의 반대 방향 시설을 선택하지 않도록 확인하세요"
                } catch(e:CancellationException){message="시설 조회가 취소됐어요";throw e}
                catch(e:Exception){message=e.message ?: "시설을 불러오지 못했어요"}
            }
        }){Text("휴게소 시설 불러오기")}
        reviewSession?.let { session ->
            Text("확인 대상: ${session.tbt.nextRestStopHint?.value?.description ?: "없음"}")
            Text("내비 즐겨찾기 수신: ${session.receivedBookmarkCount?.toString() ?: "개수 미확인"} · 휴게소명 인식: ${session.recognizedServiceAreaCount} · 대상 일치: ${session.places.size}")
            if(session.places.isEmpty()) {
                Text("용인휴게소, 용인휴게소(강릉방향), 용인휴게소 강릉방면 같은 표기를 지원해요. 임의 별칭은 자동 매칭하지 않아요.")
                if(session.recognizedServiceAreaNames.isNotEmpty()) Text("수신한 휴게소 이름: ${session.recognizedServiceAreaNames.take(5).joinToString()}")
            }
            session.places.forEach { place ->
                OutlinedButton(enabled=allowed && job?.isCompleted!=false && !probeState.running,onClick={selected=place;confirmed=false}) {
                    Text("${if(selected==place) "선택됨 · " else ""}${place.name}\n${place.address}\n${place.latitude}, ${place.longitude}")
                }
            }
            if(selected!=null) {
                OutlinedTextField(value=road,onValueChange={road=it;confirmed=false},label={Text("직접 확인한 노선명 (예: 영동고속도로)")},enabled=allowed && job?.isCompleted!=false && !probeState.running)
                OutlinedTextField(value=direction,onValueChange={direction=it;confirmed=false},label={Text("직접 확인한 진행 방향")},enabled=allowed && job?.isCompleted!=false && !probeState.running)
                Row {
                    Checkbox(checked=confirmed,onCheckedChange={confirmed=it},enabled=allowed && job?.isCompleted!=false && !probeState.running)
                    Text("이 시설이 현재 노선·진행 방향의 휴게소임을 직접 확인했어요")
                }
                OutlinedButton(enabled=allowed && job?.isCompleted!=false && !probeState.running && confirmed && road.isNotBlank() && direction.isNotBlank(),onClick={
                    val place=selected!!
                    job=scope.launch {
                        try {
                            review.confirm(session,place,road,direction,confirmed)
                            message="이 경로의 시설 확인을 저장했어요. 이름·ETA 미리보기를 확인해 주세요"
                        } catch(e:CancellationException){throw e}
                        catch(e:Exception){message=e.message ?: "시설 확인을 저장하지 못했어요"}
                    }
                }){Text("이 경로의 시설 확인 저장")}
            }
            OutlinedButton(enabled=allowed && job?.isCompleted!=false && !probeState.running,onClick={
                job=scope.launch { try {review.forget(session);message="이 경로의 시설 확인을 해제했어요"}
                    catch(e:CancellationException){throw e} catch(e:Exception){message=e.message ?: "해제하지 못했어요"} }
            }){Text("이 경로의 확인 해제")}
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = allowed && job?.isCompleted != false && !probeState.running, onClick = {
                candidates = emptyList()
                job = scope.launch {
                    message = "현재 안내 경로·진행 방향·휴게소 도착 시간을 확인 중이에요"
                    try {
                        val proposal=navigation.prepareProposal()
                        try { candidates=listOf(proposal.candidate);message="제안 미리보기: ${proposal.prompt}" }
                        finally {navigation.discard(proposal)}
                    } catch (e: CancellationException) { message = "조회가 취소됐어요"; throw e }
                    catch (e: Exception) { message = e.message ?: "조회하지 못했어요" }
                }
            }) { Text("이름·ETA 미리보기") }
            OutlinedButton(enabled = job?.isActive == true, onClick = { job?.cancel() }) { Text("조회·시험 취소") }
        }
        Text("같은 앱에서 시설 확인을 저장한 뒤 시험하세요. P·속도 0을 유지해 주세요. 내비가 전면으로 나와도 추가·삭제 확인을 이어가며, 앱에 돌아오면 위 결과를 볼 수 있어요.")
        OutlinedButton(enabled=allowed && job?.isCompleted!=false && !probeState.running,onClick=onStartProbe){Text("실제 추가·복원 시험")}
        OutlinedButton(enabled=probeState.running,onClick=onCancelProbe){Text("실제 시험 취소")}
        if (!allowed) Text("P·속도 0·차량 화면 사용 가능 상태에서 조회할 수 있어요")
        tbt?.let { received ->
            Text("내비 원본 목록 — 수신 순서",style=MaterialTheme.typography.titleMedium)
            received.nextRestStopHint?.let { Text("다음 휴게소 후보: ${it.value.description}",style=MaterialTheme.typography.titleMedium) }
            received.entries.forEachIndexed { index,entry ->
                val label=when(entry.facilityKind()) {
                    TbtFacilityKind.SERVICE_AREA -> "휴게소 후보"
                    TbtFacilityKind.NON_REST_FACILITY -> "기타 시설(제외)"
                    TbtFacilityKind.SLEEP_REST_AREA -> "졸음쉼터 후보"
                    TbtFacilityKind.UNKNOWN_SERVICE_FACILITY -> "시설 종류 확인 필요"
                    TbtFacilityKind.MANEUVER -> entry.type
                }
                Text("#${index+1} $label · ${entry.description} · 원본 거리 ${entry.distanceMeters}m")
            }
            Text("원본 거리는 현재 위치부터의 누적 거리가 아니에요. 좌표·POI ID·도착 시간도 없어 이 목록만으로 경유지를 추가하지 않아요.")
        }
        candidates.forEach { candidate ->
            Text(candidate.place.name, style = MaterialTheme.typography.titleMedium)
            Text("${candidate.place.roadName} · ${candidate.place.direction}")
            Text("시설까지 내비 탐색 거리 ${candidate.trip.distanceMeters}m · 예상 ${(candidate.trip.etaSeconds - 1) / 60 + 1}분")
            candidate.distanceAlongRouteMeters?.let { Text("현재 경로를 따라 ${it}m 뒤") }
            Text("${candidate.verification} · 원래 경로의 구간 누적 거리와 별도 내비 탐색 거리는 다를 수 있어요")
        }
        Text("시설 확인은 이 경로·목적지·휴게소 순서에만 적용돼요. 경로가 바뀌거나 확인이 오래되면 다시 확인해야 해요. DANGER 실제 처리에서만 음성 동의 후 추가해요.")
    }
}
