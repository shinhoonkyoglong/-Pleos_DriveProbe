package ai.pleos.playground.sample.vehicle.navigation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class NavigationProbeState(
    val runId: String = "", val running: Boolean = false, val message: String = "",
    val applicationStatus: RestNavigationStatus? = null, val cleanup: ProbeCleanup? = null,
    val interrupted: Boolean = false,
    val verifierRevision: String = WAYPOINT_VERIFICATION_REVISION,
)
interface NavigationProbeStateStore {
    fun load(): NavigationProbeState?
    fun save(state: NavigationProbeState)
}

/** Application-owned; Activity/Compose observers can detach without cancelling the transaction. */
class NavigationProbeController(
    private val scope: CoroutineScope,
    private val execute: suspend ((String) -> Unit) -> ServiceAreaProbeReport,
    private val store: NavigationProbeStateStore,
) {
    private val _state=MutableStateFlow(restore())
    val state=_state.asStateFlow()
    private var job: Job?=null
    val isBusy: Boolean get()=job?.isCompleted==false

    private fun restore(): NavigationProbeState {
        val saved=try{store.load()}catch(_:Exception){
            return NavigationProbeState(message="이전 시험 결과를 읽지 못했어요. 내비의 경유지 상태를 확인해 주세요",interrupted=true)
        } ?: return NavigationProbeState()
        if(!saved.running) return saved
        return saved.copy(running=false,interrupted=true,
            message="이전 시험이 끝나기 전에 프로세스가 종료됐어요. 적용·삭제 여부가 미확인이므로 내비 화면을 확인해 주세요. 자동 재시도는 하지 않았어요")
    }

    private fun publish(value: NavigationProbeState) {
        _state.value=value
        try{store.save(value)}catch(_:Exception){
            _state.value=value.copy(message=value.message+" · 결과 저장 실패: 이 프로세스의 화면/로그에서 확인해 주세요")
        }
    }

    fun start(finished: () -> Unit = {}): Boolean {
        if(isBusy) return false
        if(!scope.isActive){failToStart("앱의 시험 실행 범위가 종료돼 시작하지 못했어요");return false}
        val runId=UUID.randomUUID().toString()
        publish(NavigationProbeState(runId,true,"휴게소 실제 추가·복원 시험을 시작하고 있어요"))
        val work=scope.launch(start=CoroutineStart.LAZY) {
            var report: ServiceAreaProbeReport?=null
            try {
                withTimeout(90_000L) {
                    try {
                        report=execute { progress -> publish(_state.value.copy(running=true,message=progress)) }
                    } catch(e:ServiceAreaProbeCancelled) {report=e.report;throw e}
                }
                val result=checkNotNull(report)
                publish(NavigationProbeState(runId,false,result.message,result.application?.status,result.cleanup))
            } catch(e:CancellationException) {
                val result=report
                publish(NavigationProbeState(runId,false,
                    result?.message ?: "시험이 취소되거나 제한 시간이 지났어요. 내비의 경유지·삭제 상태를 확인해 주세요",
                    result?.application?.status,result?.cleanup,interrupted=result==null))
            } catch(e:Exception) {
                publish(NavigationProbeState(runId,false,e.message ?: "시험을 끝내지 못했어요. 내비 화면을 확인해 주세요",interrupted=true))
            } finally {finished()}
        }
        job=work
        work.start()
        return true
    }

    fun cancel() {job?.cancel()}

    fun failToStart(message: String) {
        if(!isBusy) publish(NavigationProbeState(message=message,interrupted=true))
    }

    fun markServiceInterrupted(message: String) {
        if(isBusy && _state.value.running) {
            publish(_state.value.copy(message=message,interrupted=true))
            cancel()
        }
    }
}
