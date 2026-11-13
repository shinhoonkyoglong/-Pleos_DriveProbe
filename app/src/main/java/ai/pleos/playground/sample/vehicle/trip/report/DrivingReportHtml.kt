package ai.pleos.playground.sample.vehicle.trip.report

import ai.pleos.playground.sample.vehicle.trip.SummaryTone
import ai.pleos.playground.sample.vehicle.trip.TripSummaryContent.Companion.durationText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * 운전 리포트를 **인쇄용 HTML 한 장**으로 만든다(순수 로직, 외부 파일·스크립트 없음, 그래프는 인라인 SVG).
 * 브라우저에서 열어 인쇄 → "PDF로 저장". A4 세로 기준. 마우스를 막대·점에 올리면 값이 보인다(SVG title).
 *
 * 서버가 생기면 서버에서 같은 내용을 만들어 가족 웹에서 내려받게 한다(차량에서는 인쇄할 수 없다).
 * 공모전 익명성: 이름 칸은 비워 두고 손으로 쓰게 한다(앱은 이름을 저장하지 않는다).
 */
object DrivingReportHtml {

    /** 계열 색(검증된 범주형 팔레트 1·2번, 밝은 배경용) */
    private val SERIES = listOf("#2a78d6", "#eb6834")

    fun render(report: DrivingReport, zone: TimeZone = TimeZone.getDefault()): String {
        val date = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREAN).apply { timeZone = zone }
        return buildString {
            append("<!doctype html>\n<html lang=\"ko\"><head><meta charset=\"utf-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            append("<title>운전 리포트</title>\n<style>\n").append(CSS).append("\n</style></head><body><main>\n")

            // 머리
            append("<header><div><h1>운전 리포트</h1><p class=\"sub\">최근 ${report.weeks}주 운전 기록 · 계속운전</p></div>")
            append("<table class=\"meta\"><tr><th>기간</th><td>${esc(report.periodText(zone))}</td></tr>")
            append("<tr><th>만든 날</th><td>${esc(date.format(Date(report.generatedAt)))}</td></tr>")
            append("<tr><th>운전자</th><td class=\"blank\"></td></tr></table></header>")
            if (report.isDemo) append("<p class=\"banner\">시연용 가상 기록으로 만든 리포트입니다. 실제 운전 기록이 아닙니다.</p>")

            // 한 줄 + 변화 문장
            append("<div class=\"headline ${toneClass(report.headlineTone)}\">${esc(report.headline)}</div>")
            if (report.findings.isNotEmpty()) {
                append("<section><h2>주요 변화</h2><ul class=\"findings\">")
                report.findings.forEach { append("<li><span class=\"tag ${toneClass(it.tone)}\">${toneLabel(it.tone)}</span>${esc(it.text)}</li>") }
                append("</ul></section>")
            }

            // 그래프
            val labels = report.weekly.map { it.label(zone) }
            val ranges = report.weekly.map { it.rangeLabel(zone) }
            append("<section><h2>주별 그래프</h2>")
            report.charts().forEach { append(chart(it, labels, ranges)) }
            append("</section>")

            // 운전 조건
            append("<section class=\"keep\"><h2>운전 조건</h2><table class=\"grid\"><thead><tr><th>조건</th><th>판정한 운전</th><th>못 지킴</th></tr></thead><tbody>")
            report.conditionRows.forEach { row ->
                val name = row.condition.label +
                    if (row.condition == ReportCondition.RADIUS && report.conditions?.radiusKm != null) " (${formatKm(report.conditions.radiusKm)})" else ""
                append("<tr><td>${esc(name)}${mock(row.isMock)}</td>")
                if (row.status == ConditionStatus.NOT_JUDGED) append("<td colspan=\"2\" class=\"muted\">판정 안 함</td></tr>")
                else append("<td>${row.judgedTrips}번</td><td>${row.violations}번</td></tr>")
            }
            append("</tbody></table></section>")

            // 표(그래프의 숫자)
            append("<section class=\"keep\"><h2>주별 기록 표</h2><table class=\"grid\"><thead><tr>")
            append("<th>기간</th><th>운전</th><th>운전 시간</th><th>평균 피로 점수</th><th>피로 신호</th><th>조건 못 지킴</th><th>출발 전 반응 느림${mock(report.total.preDriveMock)}</th></tr></thead><tbody>")
            report.weekly.forEachIndexed { i, w ->
                val s = w.stats
                append("<tr><td>${ranges[i]}</td>")
                if (s.tripCount == 0) {
                    append("<td>0번</td><td colspan=\"5\" class=\"muted\">운전 기록 없음</td></tr>")
                } else {
                    append("<td>${s.tripCount}번</td><td>${esc(durationText(s.totalDurationMs))}</td>")
                    append("<td>${s.avgFatigueScore?.let { "${it.roundToInt()}점" } ?: "<span class=\"muted\">판정 없음</span>"}</td>")
                    append("<td>${s.fatigueSignalTrips}번</td><td>${s.violatedTrips}번</td><td>${s.preDriveSlower}번</td></tr>")
                }
            }
            append("</tbody></table></section>")

            // 안내
            append("<section class=\"keep\"><h2>이 리포트에 대해</h2><ul class=\"notes\">")
            report.dataNotes.forEach { append("<li>${esc(it)}</li>") }
            append("</ul><p class=\"disclaimer\">${esc(DrivingReport.DISCLAIMER)}</p>")
            append("<div class=\"memo\"><span>메모</span></div></section>")
            append("</main></body></html>")
        }
    }

    /** 주별 그래프 하나(SVG). 축은 하나, 눈금 3개, 맨 마지막 주에만 값 글자 */
    private fun chart(c: WeeklyChart, labels: List<String>, ranges: List<String>): String = buildString {
        val w = 720.0
        val h = 200.0
        val left = 44.0
        val right = 12.0
        val top = 18.0
        val bottom = 28.0
        val plotW = w - left - right
        val plotH = h - top - bottom
        val n = labels.size
        val slot = plotW / n
        fun x(i: Int) = left + slot * (i + 0.5)
        fun y(v: Float) = top + plotH * (1 - (v / c.yMax).coerceIn(0f, 1f))

        append("<figure class=\"chart\"><figcaption>${esc(c.title)}</figcaption>")
        if (c.series.size >= 2) {
            append("<div class=\"legend\">")
            c.series.forEach { append("<span><i style=\"background:${SERIES[it.slot]}\"></i>${esc(it.name)}</span>") }
            append("</div>")
        }
        append("<svg viewBox=\"0 0 ${w.toInt()} ${h.toInt()}\" role=\"img\" aria-label=\"${esc(c.title)}\">")
        // 눈금선·눈금 글자
        c.ticks.forEach { t ->
            val ty = y(t)
            append("<line x1=\"$left\" x2=\"${w - right}\" y1=\"${f(ty)}\" y2=\"${f(ty)}\" class=\"${if (t == 0f) "base" else "gl"}\"/>")
            append("<text x=\"${left - 8}\" y=\"${f(ty + 4)}\" class=\"tick\" text-anchor=\"end\">${t.roundToInt()}</text>")
        }
        // x축 글자
        labels.forEachIndexed { i, l -> append("<text x=\"${f(x(i))}\" y=\"${f(h - 8)}\" class=\"tick\" text-anchor=\"middle\">${esc(l)}</text>") }

        when (c.kind) {
            ChartKind.BAR -> {
                val k = c.series.size
                val barW = minOf(24.0, slot * 0.6 / k)
                val gap = 2.0
                val groupW = k * barW + (k - 1) * gap
                c.series.forEachIndexed { si, s ->
                    s.values.forEachIndexed { i, v ->
                        if (v == null || v <= 0f) return@forEachIndexed
                        val bx = x(i) - groupW / 2 + si * (barW + gap)
                        val by = y(v)
                        append("<path d=\"${roundTopRect(bx, by, barW, top + plotH - by, 4.0)}\" fill=\"${SERIES[s.slot]}\">")
                        append("<title>${esc(ranges[i])} · ${esc(s.name)} ${c.format(v)}</title></path>")
                    }
                }
            }
            ChartKind.LINE -> c.series.forEach { s ->
                // 값이 없는 주에서 끊는다
                var run = mutableListOf<Pair<Double, Double>>()
                val runs = mutableListOf<List<Pair<Double, Double>>>()
                s.values.forEachIndexed { i, v ->
                    if (v == null) { if (run.isNotEmpty()) runs += run; run = mutableListOf() } else run += x(i) to y(v).toDouble()
                }
                if (run.isNotEmpty()) runs += run
                runs.filter { it.size >= 2 }.forEach { r ->
                    append("<polyline points=\"${r.joinToString(" ") { "${f(it.first)},${f(it.second)}" }}\" fill=\"none\" stroke=\"${SERIES[s.slot]}\" stroke-width=\"2\" stroke-linejoin=\"round\"/>")
                }
                s.values.forEachIndexed { i, v ->
                    if (v == null) return@forEachIndexed
                    append("<circle cx=\"${f(x(i))}\" cy=\"${f(y(v).toDouble())}\" r=\"4\" fill=\"${SERIES[s.slot]}\" stroke=\"#fff\" stroke-width=\"2\">")
                    append("<title>${esc(ranges[i])} · ${esc(s.name)} ${c.format(v)}</title></circle>")
                }
            }
        }
        // 직접 글자: 계열마다 마지막 값 하나
        c.series.forEachIndexed { si, s ->
            val i = s.values.indexOfLast { it != null } .takeIf { it >= 0 } ?: return@forEachIndexed
            val v = s.values[i]!!
            val k = c.series.size
            val dx = if (c.kind == ChartKind.BAR && k > 1) (si - (k - 1) / 2.0) * (minOf(24.0, slot * 0.6 / k) + 2) else 0.0
            append("<text x=\"${f(x(i) + dx)}\" y=\"${f(y(v) - 8.0)}\" class=\"val\" text-anchor=\"middle\">${c.format(v)}</text>")
        }
        append("</svg>")
        c.note?.let { append("<p class=\"note\">${esc(it)}</p>") }
        append("</figure>")
    }

    /** 위쪽 모서리만 둥근 막대(바닥은 평평하게 기준선에 붙음) */
    private fun roundTopRect(x: Double, y: Double, w: Double, h: Double, r0: Double): String {
        val r = minOf(r0, w / 2, h)
        return "M${f(x)},${f(y + h)} V${f(y + r)} Q${f(x)},${f(y)} ${f(x + r)},${f(y)} H${f(x + w - r)} " +
            "Q${f(x + w)},${f(y)} ${f(x + w)},${f(y + r)} V${f(y + h)} Z"
    }

    private fun f(v: Double) = "%.1f".format(Locale.US, v)
    private fun f(v: Float) = f(v.toDouble())

    private fun mock(isMock: Boolean) = if (isMock) " <span class=\"tag mock\">임시</span>" else ""

    private fun formatKm(km: Double) = if (km % 1.0 == 0.0) "${km.toInt()}km" else "${"%.1f".format(km)}km"

    private fun toneClass(t: SummaryTone) = when (t) {
        SummaryTone.GOOD -> "good"
        SummaryTone.WARN -> "warn"
        SummaryTone.INFO -> "info"
    }

    private fun toneLabel(t: SummaryTone) = when (t) {
        SummaryTone.GOOD -> "좋음"
        SummaryTone.WARN -> "확인"
        SummaryTone.INFO -> "참고"
    }

    fun esc(s: String): String = buildString(s.length) {
        s.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private val CSS = """
        @page { size: A4; margin: 14mm; }
        * { box-sizing: border-box; }
        body { margin: 0; background: #f3f4f6; color: #0b0b0b; font: 15px/1.6 "Noto Sans KR", "Malgun Gothic", sans-serif; }
        main { max-width: 820px; margin: 24px auto; background: #fcfcfb; padding: 40px 44px; }
        header { display: flex; justify-content: space-between; gap: 24px; border-bottom: 2px solid #0b0b0b; padding-bottom: 16px; }
        h1 { margin: 0; font-size: 28px; }
        .sub { margin: 4px 0 0; color: #52514e; }
        h2 { font-size: 18px; margin: 28px 0 10px; }
        table { border-collapse: collapse; }
        .meta th { text-align: left; color: #52514e; font-weight: normal; padding: 2px 12px 2px 0; }
        .meta td.blank { border-bottom: 1px solid #9aa0ab; min-width: 140px; }
        .banner { background: #edeef2; padding: 10px 14px; border-radius: 8px; margin: 16px 0 0; }
        .headline { margin-top: 20px; padding: 14px 18px; border-radius: 8px; font-size: 19px; font-weight: 700; }
        .headline.good { background: #e3f3ec; color: #177a55; }
        .headline.warn { background: #fbe7e6; color: #b3261e; }
        .headline.info { background: #edeef2; color: #3d424d; }
        ul { padding-left: 0; list-style: none; margin: 8px 0 0; }
        li { margin: 4px 0; }
        .notes { list-style: disc; padding-left: 20px; color: #3d424d; }
        .tag { display: inline-block; font-size: 12px; padding: 1px 8px; border-radius: 10px; margin-right: 8px; white-space: nowrap; }
        .tag.good { background: #e3f3ec; color: #177a55; }
        .tag.warn { background: #fbe7e6; color: #b3261e; }
        .tag.info, .tag.mock { background: #edeef2; color: #52514e; }
        .tag.mock { margin: 0 0 0 4px; }
        .chart { margin: 0 0 22px; break-inside: avoid; }
        .chart figcaption { font-weight: 600; margin-bottom: 4px; }
        .chart svg { width: 100%; height: auto; display: block; }
        .chart .gl { stroke: #e6e5e1; stroke-width: 1; }
        .chart .base { stroke: #b9b8b2; stroke-width: 1; }
        .chart .tick { font-size: 11px; fill: #52514e; }
        .chart .val { font-size: 12px; font-weight: 600; fill: #0b0b0b; }
        .legend { display: flex; gap: 16px; font-size: 13px; color: #0b0b0b; margin-bottom: 2px; }
        .legend i { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 6px; vertical-align: middle; }
        .grid { width: 100%; }
        table.grid th, table.grid td { border-bottom: 1px solid #dfe1e6; padding: 6px 8px; text-align: left; }
        table.grid th { background: #f6f7f9; font-weight: 600; font-size: 13px; }
        .muted { color: #9aa0ab; }
        .note { color: #52514e; font-size: 12px; margin: 2px 0 0; }
        .disclaimer { margin-top: 16px; padding: 12px 14px; border: 1px solid #dfe1e6; border-radius: 6px; color: #3d424d; font-size: 13px; }
        .memo { margin-top: 16px; height: 80px; border: 1px dashed #9aa0ab; border-radius: 6px; padding: 8px 12px; color: #9aa0ab; font-size: 13px; }
        @media print { body { background: #fff; } main { margin: 0; padding: 0; max-width: none; background: #fff; } .keep { break-inside: avoid; } }
    """.trimIndent()
}
