package ai.pleos.playground.sample.vehicle.condition.core

import java.util.Locale

/** 재생 결과를 표로 출력(파라미터 조정·결과 보고용) */
object DemoDiagnostics {
    fun table(trip: ReplayTrip, points: List<ReplayPoint>): String = buildString {
        appendLine(String.format(Locale.US, "%-7s %-10s %-6s %6s %6s %5s %5s %5s %5s %5s %-8s %s",
            "t(s)", "구간", "유효", "small", "large", "jerk", "micro", "lrg", "raw", "score", "level", "invalid"))
        for (p in points) {
            val seg = trip.segments.firstOrNull { p.timeMs in it.startMs until it.endMs }?.name ?: "-"
            val f = p.window.features
            val bd = p.breakdown
            appendLine(String.format(Locale.US, "%-7.1f %-10s %-6s %6s %6s %5s %5s %5s %5s %5.2f %-8s %s",
                p.timeMs / 1000f, seg, p.window.isValid,
                f?.let { "%.1f".format(Locale.US, it.reversalsSmallPerMin) } ?: "-",
                f?.let { "%.1f".format(Locale.US, it.reversalsLargePerMin) } ?: "-",
                f?.lullJerkCount?.toString() ?: "-",
                bd?.let { "%.2f".format(Locale.US, it.microDrop) } ?: "-",
                bd?.let { "%.2f".format(Locale.US, it.largeRise) } ?: "-",
                bd?.let { "%.2f".format(Locale.US, it.raw) } ?: "-",
                p.state.score, p.state.level, p.window.invalidReason ?: ""))
        }
    }
}
