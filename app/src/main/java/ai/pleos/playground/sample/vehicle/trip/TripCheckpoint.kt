package ai.pleos.playground.sample.vehicle.trip

import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import ai.pleos.playground.sample.vehicle.condition.core.ReactionVerdict
import ai.pleos.playground.sample.vehicle.condition.core.ReasonCode
import android.content.Context

/*
 * 주행 중간 저장 — 주행 중에 앱 프로세스가 종료돼도(백그라운드 정리·오류) 그 주행을 잃지 않게 한다.
 *
 * - 주행 중 [TripRecorder]가 집계 상태([TripSnapshot])를 일정 간격으로 저장한다
 * - 앱이 다시 켜지면 [TripRecovery.decide]로 "같은 주행 이어가기" 또는 "마지막 저장 시점에 끝난 주행으로 저장"을 고른다
 * - 저장소는 trip_local.db와 따로(SharedPreferences) — DB 구조를 바꾸지 않아 기존 기록이 지워지지 않는다
 */

/**
 * 진행 중인 주행의 집계 상태(순수 데이터). [TripAccumulator.snapshot] / [TripAccumulator.restore]로 주고받는다.
 * 엔진 시계(timeMs)는 앱을 다시 켜면 이어지지 않아 저장하지 않는다(마지막 저장 ~ 재시작 사이 시간은 집계에서 빠짐).
 *
 * @param savedAt 저장한 벽시계 시각(ms). 복구할 때 "마지막으로 확인된 주행 시각"으로 쓴다
 */
data class TripSnapshot(
    val tripId: String,
    val startedAt: Long,
    val savedAt: Long,
    val isDemo: Boolean,
    val lastLevel: FatigueLevel,
    val maxLevel: FatigueLevel,
    val mildEntries: Int,
    val cautionEntries: Int,
    val dangerEntries: Int,
    val reasonCodes: Set<ReasonCode>,
    val observedMs: Long,
    val judgedMs: Long,
    val scoreMsSum: Double,
    val maxScore: Float,
    val maxDrivingMinutes: Float,
    val preDrive: ReactionVerdict?,
    val isCalibrating: Boolean,
) {
    /** 한 줄에 하나씩 "키=값". 앞으로 필드가 늘어도 [decode]가 모르는 키는 무시한다 */
    fun encode(): String = listOf(
        "v" to VERSION,
        "tripId" to tripId,
        "startedAt" to startedAt,
        "savedAt" to savedAt,
        "isDemo" to isDemo,
        "lastLevel" to lastLevel.name,
        "maxLevel" to maxLevel.name,
        "mild" to mildEntries,
        "caution" to cautionEntries,
        "danger" to dangerEntries,
        "reasons" to reasonCodes.joinToString(",") { it.name },
        "observedMs" to observedMs,
        "judgedMs" to judgedMs,
        "scoreMsSum" to scoreMsSum,
        "maxScore" to maxScore,
        "maxDrivingMinutes" to maxDrivingMinutes,
        "preDrive" to (preDrive?.name ?: ""),
        "isCalibrating" to isCalibrating,
    ).joinToString("\n") { (k, v) -> "$k=$v" }

    companion object {
        private const val VERSION = 1

        /** 읽을 수 없으면(형식이 다르거나 값이 깨짐) null — 복구하지 않고 버린다 */
        fun decode(text: String): TripSnapshot? = runCatching {
            val m = text.lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            if (m["v"]?.toInt() != VERSION) return null
            TripSnapshot(
                tripId = m.getValue("tripId").also { require(it.isNotBlank()) },
                startedAt = m.getValue("startedAt").toLong(),
                savedAt = m.getValue("savedAt").toLong(),
                isDemo = m.getValue("isDemo").toBooleanStrict(),
                lastLevel = FatigueLevel.valueOf(m.getValue("lastLevel")),
                maxLevel = FatigueLevel.valueOf(m.getValue("maxLevel")),
                mildEntries = m.getValue("mild").toInt(),
                cautionEntries = m.getValue("caution").toInt(),
                dangerEntries = m.getValue("danger").toInt(),
                reasonCodes = m.getValue("reasons").split(',').filter { it.isNotBlank() }
                    .mapNotNull { name -> ReasonCode.entries.firstOrNull { it.name == name } }.toSet(),
                observedMs = m.getValue("observedMs").toLong(),
                judgedMs = m.getValue("judgedMs").toLong(),
                scoreMsSum = m.getValue("scoreMsSum").toDouble(),
                maxScore = m.getValue("maxScore").toFloat(),
                maxDrivingMinutes = m.getValue("maxDrivingMinutes").toFloat(),
                preDrive = m.getValue("preDrive").takeIf { it.isNotBlank() }?.let { ReactionVerdict.valueOf(it) },
                isCalibrating = m.getValue("isCalibrating").toBooleanStrict(),
            )
        }.getOrNull()
    }
}

/** 중간 저장소(진행 중인 주행 1건만) */
interface TripCheckpointStore {
    fun load(): TripSnapshot?
    fun save(snapshot: TripSnapshot)
    fun clear()

    companion object {
        /** 저장하지 않음(테스트·중간 저장을 끌 때) */
        val NONE = object : TripCheckpointStore {
            override fun load(): TripSnapshot? = null
            override fun save(snapshot: TripSnapshot) = Unit
            override fun clear() = Unit
        }
    }
}

/** 앱 전용 SharedPreferences("trip_checkpoint")에 저장. 위치 정보는 없다 */
class PrefsCheckpointStore(context: Context) : TripCheckpointStore {
    private val prefs = context.applicationContext.getSharedPreferences("trip_checkpoint", Context.MODE_PRIVATE)

    override fun load(): TripSnapshot? = prefs.getString(KEY, null)?.let(TripSnapshot::decode)
    override fun save(snapshot: TripSnapshot) = prefs.edit().putString(KEY, snapshot.encode()).apply()
    override fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "snapshot"
    }
}

enum class RecoveryAction {
    /** 같은 주행으로 이어서 기록 */
    RESUME,

    /** 마지막 저장 시점에 끝난 주행으로 저장하고, 지금 주행 중이면 새 주행을 시작 */
    FINALIZE,
}

/** 앱이 다시 켜졌을 때 남은 중간 저장을 어떻게 할지(순수 로직) */
object TripRecovery {

    /** 이 시간 안에 다시 켜졌고 아직 주행 중이면 같은 주행으로 본다(3번 연속 운전 시간도 10분 쉬면 0으로 돌아간다) */
    const val RESUME_WITHIN_MS = 10 * 60_000L

    /**
     * @param driving       앱이 다시 켜진 뒤 엔진이 처음 알려준 주행 여부(기어 D/R/N)
     * @param engineIsDemo  지금 엔진이 시연 재생인지
     */
    fun decide(snapshot: TripSnapshot, now: Long, driving: Boolean, engineIsDemo: Boolean): RecoveryAction {
        val gap = now - snapshot.savedAt
        val resumable = driving &&
            !snapshot.isDemo && !engineIsDemo && // 시연 재생은 다시 켜면 처음부터 재생되므로 이어 붙이지 않는다
            gap in 0..RESUME_WITHIN_MS
        return if (resumable) RecoveryAction.RESUME else RecoveryAction.FINALIZE
    }
}
