package ai.pleos.playground.sample.vehicle.trip.ui

import ai.pleos.playground.sample.vehicle.trip.TripModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/*
 * 팀원 화면에 그대로 넣는 섹션 — DB(TripModule)에서 직접 읽어 그린다. 스크롤은 호스트가 맡는다.
 *
 * ```kotlin
 * Column(Modifier.verticalScroll(rememberScrollState())) {
 *     DrivingReportSection()      // 운전 리포트(최근 8주)
 * }
 * ```
 * 화면이 처음 그려진 시각을 기준으로 기간을 자른다(날이 바뀌면 화면을 다시 열면 된다).
 */

/** 운전 리포트 섹션(최근 [weeks]주) */
@Composable
fun DrivingReportSection(modifier: Modifier = Modifier, weeks: Int = 8, showTitle: Boolean = true) {
    val flow = remember(weeks) { TripModule.observeDrivingReport(weeks) }
    val report by flow.collectAsStateWithLifecycle(initialValue = null)
    report?.let { DrivingReportView(it, modifier, showTitle) }
        ?: TripEmptyText("주행 기록을 불러오는 중이에요", modifier)
}
