package ai.pleos.playground.sample.vehicle.trip

import ai.pleos.playground.sample.vehicle.condition.ConditionModule
import ai.pleos.playground.sample.vehicle.condition.engine.ConditionEngine
import ai.pleos.playground.sample.vehicle.condition.signals.ReplayVehicleSignals
import ai.pleos.playground.sample.vehicle.trip.data.TripRecordDao
import ai.pleos.playground.sample.vehicle.trip.data.TripRecordEntity
import ai.pleos.playground.sample.vehicle.trip.source.ComplianceResult
import ai.pleos.playground.sample.vehicle.trip.source.ComplianceSource
import ai.pleos.playground.sample.vehicle.trip.source.PreDriveCheck
import ai.pleos.playground.sample.vehicle.trip.source.PreDriveSource
import ai.pleos.playground.sample.vehicle.trip.source.RestAdvice
import ai.pleos.playground.sample.vehicle.trip.source.RestAdviceSource
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 3번 컨디션 엔진을 **구독만** 해서 주행 1회를 기록한다.
 *
 * - 주행 시작/종료: [ConditionEngine.isDriving] (기어 D/R/N → 시작, P → 종료)
 * - 시연 재생(DEMO 모드)은 기어 P로 끝나지 않으므로 재생이 끝나면 주행 종료로 본다
 * - 주행 중 엔진 모드가 바뀌면 그 주행은 저장하지 않는다
 * - 주행 중 [CHECKPOINT_INTERVAL_MS]마다 집계 상태를 중간 저장한다([checkpoints]). 앱이 다시 켜지면
 *   [TripRecovery.decide]에 따라 같은 주행을 이어 가거나, 마지막 저장 시각에 끝난 주행으로 저장한다
 */
class TripRecorder(
    private val scope: CoroutineScope,
    private val dao: TripRecordDao,
    private val restAdviceSource: RestAdviceSource,
    private val complianceSource: ComplianceSource,
    private val preDriveSource: PreDriveSource,
    private val engines: Flow<ConditionEngine> = conditionEngines(),
    private val accumulator: TripAccumulator = TripAccumulator(),
    private val checkpoints: TripCheckpointStore = TripCheckpointStore.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var job: Job? = null

    /**
     * 강제 종료(재생 끝·디버그) 뒤에는 엔진이 아직 주행 중이라고 알려도 새 주행을 시작하지 않는다.
     * 한 번 주행이 아님(P)이 들어오면 풀린다.
     */
    private var waitForPark = false

    /** 앱 시작 때 읽은 중간 저장(첫 엔진 상태를 받으면 처리하고 비운다) */
    private var pendingRecovery: TripSnapshot? = null
    private var lastCheckpointAt = 0L

    private val _lastRecord = MutableStateFlow<TripRecordEntity?>(null)
    /** 이번 실행 중 마지막으로 저장된 주행 (주행 종료 요약 표시용) */
    val lastRecord: StateFlow<TripRecordEntity?> = _lastRecord.asStateFlow()

    fun start() {
        if (job != null) return
        job = scope.launch {
            pendingRecovery = runCatching { checkpoints.load() }.getOrNull()
                ?.also { Log.i(TAG, "checkpoint found trip=${it.tripId} savedAt=${it.savedAt}") }
            engines.collectLatest { engine ->
                lock.withLock {
                    if (accumulator.isInTrip) {
                        Log.i(TAG, "engine changed during trip → discard")
                        clearCheckpoint()
                    }
                    accumulator.abort()
                    waitForPark = false
                }
                Log.i(TAG, "observing engine isReplay=${engine.isReplay}")
                observe(engine)
            }
        }
    }

    /** 진행 중인 주행을 지금 끝낸다 (디버그·시연용). 다음 주행은 기어 P를 한 번 거친 뒤 시작된다 */
    suspend fun finishNow(reason: String) {
        lock.withLock {
            if (!accumulator.isInTrip) return
            finishLocked(reason)
            waitForPark = true
        }
    }

    private suspend fun observe(engine: ConditionEngine) = coroutineScope {
        val replay = engine.signals as? ReplayVehicleSignals
        if (replay != null) {
            launch {
                replay.finished.filter { it }.first()
                finishNow("replay finished")
            }
        }
        combine(engine.isDriving, engine.state) { driving, state -> driving to state }
            .collect { (driving, state) ->
                lock.withLock {
                    pendingRecovery?.let { snap ->
                        pendingRecovery = null
                        recoverLocked(snap, driving, engine.isReplay)
                    }
                    if (!driving) waitForPark = false
                    if (driving && !accumulator.isInTrip && !waitForPark) {
                        accumulator.start(isDemo = engine.isReplay, current = state)
                        Log.i(TAG, "trip started demo=${engine.isReplay}")
                        lastCheckpointAt = 0L // 시작하자마자 한 번 저장
                    }
                    accumulator.onState(state)
                    if (!driving && accumulator.isInTrip) finishLocked("gear P")
                    else if (accumulator.isInTrip) checkpointLocked()
                }
            }
    }

    /** 앱이 다시 켜졌을 때 남아 있던 중간 저장 처리 */
    private suspend fun recoverLocked(snap: TripSnapshot, driving: Boolean, engineIsDemo: Boolean) {
        if (runCatching { dao.getById(snap.tripId) }.getOrNull() != null) {
            clearCheckpoint() // 이미 저장된 주행(저장 직후 종료된 경우)
            return
        }
        when (TripRecovery.decide(snap, clock(), driving, engineIsDemo)) {
            RecoveryAction.RESUME -> {
                accumulator.restore(snap)
                lastCheckpointAt = 0L
                Log.i(TAG, "trip resumed from checkpoint trip=${snap.tripId}")
            }
            RecoveryAction.FINALIZE -> {
                accumulator.restore(snap)
                // 마지막으로 확인된 시각에 끝난 주행으로 저장. 앱을 켜자마자 요약 화면이 뜨지 않게 알리지 않는다
                finishLocked("recovered from checkpoint", endedAt = snap.savedAt, announce = false)
            }
        }
    }

    /** 주행 중 일정 간격으로 중간 저장 */
    private fun checkpointLocked() {
        val now = clock()
        if (lastCheckpointAt != 0L && now - lastCheckpointAt < CHECKPOINT_INTERVAL_MS) return
        val snap = accumulator.snapshot() ?: return
        runCatching { checkpoints.save(snap) }.onFailure { Log.w(TAG, "checkpoint save failed", it) }
        lastCheckpointAt = now
    }

    private fun clearCheckpoint() {
        runCatching { checkpoints.clear() }
        lastCheckpointAt = 0L
    }

    /**
     * @param endedAt  종료 시각(기본 지금)
     * @param announce false면 [lastRecord]를 바꾸지 않는다(요약 화면 자동 표시 안 함)
     */
    private suspend fun finishLocked(reason: String, endedAt: Long = clock(), announce: Boolean = true) {
        val trip = accumulator.finish(endedAt) ?: return
        val record = buildRecord(
            trip = trip,
            rest = restAdviceSource.countFor(trip),
            compliance = complianceSource.evaluate(trip),
            preDrive = preDriveSource.checkFor(trip),
        )
        runCatching { dao.insert(record) }
            .onSuccess { clearCheckpoint() } // 저장에 실패하면 중간 저장을 남겨 다음 실행 때 다시 시도
            .onFailure { Log.e(TAG, "trip save failed", it) }
        if (announce) _lastRecord.value = record
        Log.i(TAG, "trip finished ($reason) $record")
        val summary = TripSummaryContent.from(record)
        Log.i(TAG, "summary: ${summary.headline} | " + summary.items.joinToString(" / ") {
            "${it.title}=${it.value}${if (it.isMock) "(MOCK)" else ""}"
        })
    }

    companion object {
        const val TAG = "Trip"

        /** 중간 저장 간격. 앱이 갑자기 종료되면 최대 이만큼의 기록이 빠진다 */
        const val CHECKPOINT_INTERVAL_MS = 15_000L

        /** 집계 결과 + 다른 파트 값(또는 임시값)을 저장할 기록 1건으로 만든다 */
        fun buildRecord(
            trip: CompletedTrip,
            rest: RestAdvice,
            compliance: ComplianceResult,
            preDrive: PreDriveCheck,
        ) = TripRecordEntity(
            tripId = trip.tripId,
            startedAt = trip.startedAt,
            endedAt = trip.endedAt,
            durationMs = trip.durationMs,
            maxLevel = trip.maxLevel.name,
            mildEntries = trip.mildEntries,
            cautionEntries = trip.cautionEntries,
            dangerEntries = trip.dangerEntries,
            reasonCodes = trip.reasonCodes.joinToString(",") { it.name },
            observedMs = trip.observedMs,
            judgedMs = trip.judgedMs,
            maxFatigueScore = trip.maxScore.toScore100(),
            avgFatigueScore = trip.avgScore?.toScore100(),
            maxDrivingMinutes = trip.maxDrivingMinutes,
            isCalibrating = trip.isCalibrating,
            preDriveVerdict = preDrive.verdict?.name,
            preDriveSource = preDrive.source.name,
            restAdviceCount = rest.count,
            restRespondedCount = rest.respondedCount,
            restAdviceSource = rest.source.name,
            compliance = compliance.status.name,
            violations = compliance.violations.joinToString(",") { it.name },
            complianceSource = compliance.source.name,
            isDemo = trip.isDemo,
        )

        /** 3번 엔진 0~1 → 4번 개입 이력과 같은 0~100 */
        private fun Float.toScore100(): Float = coerceIn(0f, 1f) * 100f

        /**
         * 현재 컨디션 엔진. ConditionModule은 모드 전환 때 엔진을 새로 만들지만 알림 Flow가 없어서
         * 1초마다 참조가 바뀌었는지 확인한다.
         * TODO(4번 머지 후): ConditionModule.engines Flow가 생기면 그것으로 교체
         */
        fun conditionEngines(pollMs: Long = 1_000L): Flow<ConditionEngine> = flow {
            while (currentCoroutineContext().isActive) {
                if (ConditionModule.isInitialized) emit(ConditionModule.engine)
                delay(pollMs)
            }
        }.distinctUntilChanged { a, b -> a === b }
    }
}
