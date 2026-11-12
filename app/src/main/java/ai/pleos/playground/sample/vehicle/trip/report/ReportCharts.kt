package ai.pleos.playground.sample.vehicle.trip.report

import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * 운전 리포트 그래프의 **내용**(무엇을 그릴지). 차량 화면(Compose)과 인쇄용 HTML(SVG)이 같은 값을 쓴다.
 *
 * 그래프 3개, 모두 x축 = 주(오래된 주 → 최근 주), y축은 하나(두 축을 겹치지 않는다):
 * 1. 주별 운전 횟수          막대(계열 1개)
 * 2. 주별 평균 피로 점수     선(계열 1개, 판정 없는 주는 끊김)
 * 3. 주별 확인할 일          묶음 막대(피로 신호 / 조건 위반, 계열 2개 → 범례 필수)
 */

enum class ChartKind { BAR, LINE }

/**
 * @param slot   색 순서(0 = 첫째 색, 1 = 둘째 색). 순서로만 정하고 돌려 쓰지 않는다
 * @param values 주별 값. null = 그 주에는 값이 없음(선이 끊기고 막대가 없음)
 */
data class ChartSeries(val name: String, val slot: Int, val values: List<Float?>)

/**
 * @param unit 값 뒤에 붙는 단위(예: "번", "점")
 * @param note 그래프 아래 한 줄 설명
 */
data class WeeklyChart(
    val title: String,
    val kind: ChartKind,
    val series: List<ChartSeries>,
    val yMax: Float,
    val unit: String,
    val note: String?,
) {
    /** y축 눈금: 0, 가운데, 맨 위 */
    val ticks: List<Float> get() = listOf(0f, yMax / 2, yMax)

    fun format(v: Float): String = "${v.roundToInt()}$unit"
}

/** 리포트의 그래프 3개 */
fun DrivingReport.charts(): List<WeeklyChart> {
    val counts = weekly.map { it.stats.tripCount.toFloat() }
    val fatigue = weekly.map { it.stats.avgFatigueScore }
    val signals = weekly.map { it.stats.fatigueSignalTrips.toFloat() }
    val violations = weekly.map { it.stats.violatedTrips.toFloat() }
    return listOf(
        WeeklyChart(
            title = "주별 운전 횟수",
            kind = ChartKind.BAR,
            series = listOf(ChartSeries("운전 횟수", 0, counts)),
            yMax = niceMax(counts.maxOrNull() ?: 0f, step = 2f, min = 4f),
            unit = "번",
            note = null,
        ),
        WeeklyChart(
            title = "주별 평균 피로 점수",
            kind = ChartKind.LINE,
            series = listOf(ChartSeries("평균 피로 점수", 0, fatigue)),
            yMax = niceMax((fatigue.filterNotNull().maxOrNull() ?: 0f) + 5f, step = 10f, min = 40f),
            unit = "점",
            note = "0~100점, 낮을수록 좋아요. 점이 없는 주는 피로를 판정할 구간이 없었던 주예요.",
        ),
        WeeklyChart(
            title = "주별 확인할 일",
            kind = ChartKind.BAR,
            series = listOf(
                ChartSeries("피로 신호", 0, signals),
                ChartSeries("조건 못 지킴", 1, violations),
            ),
            yMax = niceMax(maxOf(signals.maxOrNull() ?: 0f, violations.maxOrNull() ?: 0f), step = 1f, min = 2f),
            unit = "번",
            note = "피로 신호 = 피로 단계가 \"주의\" 이상으로 올라간 운전.",
        ),
    )
}

/** [value]보다 크거나 같은 [step]의 배수(짝수 칸이 되도록), 최소 [min] */
internal fun niceMax(value: Float, step: Float, min: Float): Float {
    var m = maxOf(min, ceil(value / step) * step)
    if ((m / step).roundToInt() % 2 == 1) m += step // 가운데 눈금이 깔끔하게 떨어지도록
    return m
}
