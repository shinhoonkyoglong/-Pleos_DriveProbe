package ai.pleos.playground.sample.vehicle.condition.core

import java.util.Locale

/*
 * 재생(Replay) 데이터 형식과 실행기.
 * assets/condition/trip_demo.json 같은 파일을 읽어 ConditionProcessor에 흘려 넣는다.
 * 안드로이드 쪽 재생 구현(signals/ReplayVehicleSignals)도 이 리더를 쓴다(org.json 불필요).
 *
 * 형식 규칙(리더가 정규식으로 읽으므로 지킬 것): 이벤트·구간은 한 줄에 하나, 문자열 안에 큰따옴표 금지.
 *   {"t":15000,"k":"gear","v":"DRIVE"}      k = steer(°) | speed_kph | gear(Gear) | turn(TurnSignal) | brake(true/false)
 */

enum class SignalKind(val key: String) {
    STEERING("steer"),
    SPEED_KPH("speed_kph"),
    GEAR("gear"),
    TURN("turn"),
    BRAKE("brake");

    val isText: Boolean get() = this == GEAR || this == TURN || this == BRAKE

    companion object {
        fun of(key: String): SignalKind = entries.first { it.key == key }
    }
}

data class ReplayEvent(
    val tMs: Long,
    val kind: SignalKind,
    val num: Float = 0f,
    val text: String? = null,
)

data class ReplaySegment(val name: String, val startMs: Long, val endMs: Long)

data class ReplayTrip(
    val name: String,
    val description: String,
    val durationMs: Long,
    val segments: List<ReplaySegment>,
    /** 시간순 정렬 */
    val events: List<ReplayEvent>,
)

object ReplayFormat {
    const val FORMAT = "condition-replay-v1"

    fun write(trip: ReplayTrip): String = buildString {
        append("{\n")
        append("  \"format\": \"$FORMAT\",\n")
        append("  \"name\": \"${trip.name}\",\n")
        append("  \"description\": \"${trip.description}\",\n")
        append("  \"durationMs\": ${trip.durationMs},\n")
        append("  \"segments\": [\n")
        trip.segments.forEachIndexed { i, s ->
            append("    {\"name\":\"${s.name}\",\"startMs\":${s.startMs},\"endMs\":${s.endMs}}")
            append(if (i < trip.segments.lastIndex) ",\n" else "\n")
        }
        append("  ],\n")
        append("  \"events\": [\n")
        trip.events.forEachIndexed { i, e ->
            val v = if (e.kind.isText) "\"${e.text}\"" else String.format(Locale.US, "%.1f", e.num)
            append("    {\"t\":${e.tMs},\"k\":\"${e.kind.key}\",\"v\":$v}")
            append(if (i < trip.events.lastIndex) ",\n" else "\n")
        }
        append("  ]\n")
        append("}\n")
    }

    private val nameRe = Regex("\"name\":\\s*\"([^\"]*)\",?\\s*$")
    private val descRe = Regex("\"description\":\\s*\"([^\"]*)\"")
    private val durRe = Regex("\"durationMs\":\\s*(\\d+)")
    private val segRe = Regex("\\{\"name\":\"([^\"]*)\",\"startMs\":(\\d+),\"endMs\":(\\d+)\\}")
    private val evRe = Regex("\\{\"t\":(\\d+),\"k\":\"(\\w+)\",\"v\":(\"[^\"]*\"|-?[0-9.]+)\\}")

    fun read(text: String): ReplayTrip {
        var name = ""
        var desc = ""
        var duration = 0L
        val segments = ArrayList<ReplaySegment>()
        val events = ArrayList<ReplayEvent>()
        for (line in text.lineSequence()) {
            val l = line.trim()
            evRe.find(l)?.let { m ->
                val kind = SignalKind.of(m.groupValues[2])
                val raw = m.groupValues[3]
                events += if (kind.isText) ReplayEvent(m.groupValues[1].toLong(), kind, text = raw.trim('"'))
                else ReplayEvent(m.groupValues[1].toLong(), kind, num = raw.toFloat())
                return@let
            } ?: segRe.find(l)?.let { m ->
                segments += ReplaySegment(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[3].toLong())
            } ?: run {
                nameRe.find(l)?.let { name = it.groupValues[1] }
                descRe.find(l)?.let { desc = it.groupValues[1] }
                durRe.find(l)?.let { duration = it.groupValues[1].toLong() }
            }
        }
        require(events.isNotEmpty()) { "replay has no events (format: $FORMAT)" }
        return ReplayTrip(name, desc, duration, segments, events.sortedBy { it.tMs })
    }
}

/** 재생 이벤트 하나를 처리기에 넣는다 */
fun ConditionProcessor.apply(e: ReplayEvent) {
    when (e.kind) {
        SignalKind.STEERING -> onSteering(e.tMs, e.num)
        SignalKind.SPEED_KPH -> onSpeed(e.tMs, e.num / 3.6f)
        SignalKind.GEAR -> onGear(e.tMs, Gear.valueOf(e.text!!))
        SignalKind.TURN -> onTurnSignal(e.tMs, TurnSignal.valueOf(e.text!!))
        SignalKind.BRAKE -> Unit // 피로 점수에는 쓰지 않음(다른 파트용)
    }
}

/** 재생 결과 한 점(창이 갱신될 때마다 하나) */
data class ReplayPoint(
    val timeMs: Long,
    val state: ConditionState,
    val window: WindowResult,
    val breakdown: ScoreBreakdown?,
)

object ReplayRunner {
    /** 재생 데이터를 처음부터 끝까지 처리기에 넣고, 창이 갱신될 때마다의 상태를 모은다 */
    fun run(trip: ReplayTrip, processor: ConditionProcessor, hourOfDay: Int = 11, tickMs: Long = 100L): List<ReplayPoint> {
        val out = ArrayList<ReplayPoint>()
        var idx = 0
        var lastWindow: WindowResult? = null
        var t = 0L
        while (t <= trip.durationMs) {
            while (idx < trip.events.size && trip.events[idx].tMs <= t) processor.apply(trip.events[idx++])
            val state = processor.tick(t, hourOfDay)
            val w = processor.lastWindow
            if (w != null && w !== lastWindow) {
                out += ReplayPoint(w.endTimeMs, state, w, if (w.isValid) processor.lastBreakdown else null)
                lastWindow = w
            }
            t += tickMs
        }
        return out
    }
}

/** 미리 넣는 "평소 기록"(baseline_seed.json) 형식 */
object BaselineSeedFormat {
    const val FORMAT = "condition-baseline-v1"

    fun write(trips: List<TripSummary>): String = buildString {
        append("{\n  \"format\": \"$FORMAT\",\n  \"trips\": [\n")
        trips.forEachIndexed { i, s ->
            append(
                String.format(
                    Locale.US, "    {\"endTimeMs\":%d,\"small\":%.2f,\"large\":%.2f,\"std\":%.3f}",
                    s.endTimeMs, s.reversalsSmallPerMin, s.reversalsLargePerMin, s.stdDeg,
                ),
            )
            append(if (i < trips.lastIndex) ",\n" else "\n")
        }
        append("  ]\n}\n")
    }

    private val re = Regex("\\{\"endTimeMs\":(\\d+),\"small\":([0-9.]+),\"large\":([0-9.]+),\"std\":([0-9.]+)\\}")

    fun read(text: String): List<TripSummary> = re.findAll(text).map {
        TripSummary(it.groupValues[1].toLong(), it.groupValues[2].toFloat(), it.groupValues[3].toFloat(), it.groupValues[4].toFloat())
    }.toList()
}
