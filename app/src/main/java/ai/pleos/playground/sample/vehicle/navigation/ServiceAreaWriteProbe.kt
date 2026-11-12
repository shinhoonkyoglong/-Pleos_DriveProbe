package ai.pleos.playground.sample.vehicle.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class ProbeCleanup { NOT_NEEDED, VERIFIED, FAILED }
data class ServiceAreaProbeReport(
    val application: RestNavigationResult?, val cleanup: ProbeCleanup, val message: String,
)
class ServiceAreaProbeCancelled(val report: ServiceAreaProbeReport) : CancellationException(report.message)

/** Explicit same-process parked test. Never rebind a saved review to a different route id. */
class ServiceAreaWriteProbe(
    private val navigation: RestNavigation,
    private val remove: suspend (RestPlace,RestNaviSnapshot,() -> Unit) -> RestNaviSnapshot,
    private val requireParked: () -> Unit,
    private val begin: () -> Boolean,
    private val finish: (Boolean) -> Unit,
) {
    private val mutex=Mutex()

    suspend fun run(progress: (String) -> Unit = {}): ServiceAreaProbeReport {
        if(!mutex.tryLock()) return ServiceAreaProbeReport(null,ProbeCleanup.NOT_NEEDED,"다른 휴게소 시험을 처리 중이에요")
        fun update(message:String) { try { progress(message) } catch(_:Exception) { /* UI reporting cannot interrupt cleanup. */ } }
        var started=false
        var wasEnabled=false
        var proposal:RestProposal?=null
        var application:RestNavigationResult?=null
        var cleanup=ProbeCleanup.NOT_NEEDED
        var error:String?=null
        var cancelled:CancellationException?=null
        try {
            requireParked()
            wasEnabled=begin();started=true
            update("현재 앱의 등록 기록으로 실제 휴게소 추가를 준비하고 있어요")
            val p=navigation.prepareProposal();proposal=p
            requireParked()
            update("${p.candidate.place.name} 경유지를 실제로 추가·확인하고 있어요")
            application=navigation.acceptForProbe(p,requireParked)
        } catch(e:CancellationException) {
            application=(e as? RestNavigationCancelled)?.navigation
            cancelled=e
            error="시험이 취소됐어요"
        } catch(e:Exception) {
            error=(e as? RestNavigationFailure)?.let { "${it.message} · ${it.diagnosticCode ?: it.status.name}" }
                ?: (e.message ?: e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) {
                try {
                    proposal?.let { p ->
                        navigation.discard(p)
                        if(application?.requestIssued==true) {
                            update("시험 경유지를 삭제하고 원래 목적지·옵션·안내를 확인하고 있어요")
                            try {
                                withTimeout(55_000L) {
                                    requireParked()
                                    val restored=remove(p.candidate.place,p.before,requireParked)
                                    check(RestNavigation.sameDestination(p.before.destination,restored.destination) &&
                                        RestNavigation.sameWaypoints(p.before.waypoints,restored.waypoints) &&
                                        restored.state.routeOption==p.before.state.routeOption &&
                                        restored.state.naviMode==p.before.state.naviMode && !restored.state.isMock) { "삭제 후 원래 안내 상태를 확인하지 못했어요" }
                                }
                                cleanup=ProbeCleanup.VERIFIED
                            } catch(e:Exception) {
                                cleanup=ProbeCleanup.FAILED
                                error="시험 경유지 정리를 확인하지 못했어요. 내비 화면을 확인해 주세요: ${e.message ?: e.javaClass.simpleName}"
                            }
                        }
                    }
                } finally {
                    try { if(started) finish(wasEnabled) } finally { mutex.unlock() }
                }
            }
        }
        val verified=application?.status==RestNavigationStatus.VERIFIED_WAYPOINT && cleanup==ProbeCleanup.VERIFIED
        val message=when {
            verified -> "실제 경유지 추가·원래 목적지 보존·시험 경유지 삭제를 확인했어요"
            error!=null -> error!!
            else -> "추가 결과 ${application?.status ?: "확인 없음"} · 정리 결과 $cleanup · ${application?.message ?: "내비 화면을 확인해 주세요"}"
        }
        val report=ServiceAreaProbeReport(application,cleanup,message)
        update("결과: 추가=${application?.status ?: "NOT_DISPATCHED"} · 정리=$cleanup · $message")
        cancelled?.let { throw ServiceAreaProbeCancelled(report).apply { initCause(it) } }
        return report
    }
}
