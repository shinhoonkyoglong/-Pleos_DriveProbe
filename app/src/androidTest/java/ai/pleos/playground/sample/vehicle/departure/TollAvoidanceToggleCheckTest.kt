package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.data.model.NaviMode
import ai.pleos.playground.sample.vehicle.data.repository.NaviHelperRepositoryImpl
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.RouteConditionCheck
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.RouteUserAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 사람이 내비 "경로 설정"의 "유료도로 우회" 토글을 켜고/끈 결과가 1번 Repository 와 2번 어댑터에 반영되는지 확인하는 계측 테스트(작업 #19).
 *
 * 기대 동작 근거: MyApplication/docs/pleos/api-notes/navi-runtime-check.md §10.4
 *  - 토글 켬 + 적용 → getRouteStateInfo().routeOption == FREE, onRouteStarted.tollFare == 0
 *  - 토글 꺼짐 → routeOption == RECOMMENDED
 *
 * **이 테스트는 내비 상태를 바꾸지 않는다**(requestRoute·cancelRoute 호출 없음). 목적지 설정·토글 조작·안내 시작은 바깥(사람 또는 adb)에서 한다.
 * 그래서 평소 `connectedDebugAndroidTest`에서는 건너뛴다(인자 `expectedRouteOption`이 없으면 assume 으로 skip).
 *
 * 계측 인자:
 *  - `expectedRouteOption` (필수, RECOMMENDED | FREE): 지금 내비 경로에 기대하는 옵션
 *  - `waitForGuidingSec` (선택, 기본 0): 0보다 크면 테스트가 먼저 떠서 그 시간 동안 "안내 중 + 기대 옵션"이 될 때까지 기다린다.
 *    이 동안 내비에서 "적용"/안내 시작을 하면 우리 Repository 리스너가 onRouteStarted 를 받아 tollFare 까지 관측할 수 있다.
 *    0이면 지금 상태만 한 번 읽는다(이미 시작된 경로라 tollFare 는 보통 null — 재조회 API 없음, §10.4).
 *
 * 실행 예:
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=ai.pleos.playground.sample.vehicle.departure.TollAvoidanceToggleCheckTest \
 *   -Pandroid.testInstrumentationRunnerArguments.expectedRouteOption=FREE
 * ```
 * 판정(무료도로 선호 조건, ConditionRouteUseCase):
 *  - FREE: tollFare 를 관측해 0 이면 Satisfied, 못 했으면 TollAvoidanceUnverified(2번 작업 #27 — FREE 는 어린이 보호구역 우회만 켜도 옴)
 *  - RECOMMENDED: NeedsUserAction[TURN_ON_TOLL_AVOIDANCE]
 * 모든 관측값은 logcat 태그 [TAG] 로 남기고, 실패는 모아서 마지막에 한 번에 실패시킨다.
 */
@RunWith(AndroidJUnit4::class)
class TollAvoidanceToggleCheckTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    private val failures = mutableListOf<String>()

    private fun log(msg: String) = Log.i(TAG, msg)

    private fun check(name: String, ok: Boolean, observed: String) {
        log("${if (ok) "PASS" else "FAIL"} [$name] $observed")
        if (!ok) failures += "[$name] $observed"
    }

    @Test
    fun currentRoute_reflectsTollAvoidanceToggle() = runBlocking {
        val expected = arguments.getString(ARG_EXPECTED)?.trim()?.uppercase()
        assumeTrue("인자 $ARG_EXPECTED 가 없어 건너뜀(내비 상태를 바깥에서 준비해야 하는 수동 확인 테스트)", expected != null)
        assumeTrue("$ARG_EXPECTED 는 RECOMMENDED 또는 FREE: $expected", expected == "RECOMMENDED" || expected == "FREE")
        val waitSec = arguments.getString(ARG_WAIT)?.toLongOrNull() ?: 0L
        log("=== 시작 expectedRouteOption=$expected waitForGuidingSec=$waitSec")

        val repo = NaviHelperRepositoryImpl(context, enableMockFallback = false)
        try {
            // 0) (선택) 안내 중 + 기대 옵션이 될 때까지 대기. 이 사이에 바깥에서 "적용"/안내 시작을 한다.
            if (waitSec > 0) {
                val until = System.currentTimeMillis() + waitSec * 1_000
                var last: String? = null
                while (System.currentTimeMillis() < until) {
                    val st = repo.getRouteStateInfo().getOrNull()
                    val now = "${st?.naviMode}/${st?.routeOption}/toll=${st?.tollFare}"
                    if (now != last) { log("wait state=$now"); last = now }
                    if (st != null && st.naviMode.isGuidingMode() && st.routeOption == expected) break
                    delay(1_000)
                }
                // onRouteStarted 직후 목적지 갱신까지 들어와야 tollFare 가 현재 경로에 묶인다.
                delay(2_000)
            }

            // 1) 1번 Repository 직접 조회
            val state = repo.getRouteStateInfo()
            log("1 getRouteStateInfo=$state")
            val dest = repo.getDestinationInfo()
            log("1 getDestinationInfo=$dest")
            val s = state.getOrNull()
            check(
                "1-routeOption",
                s != null && !s.isMock && s.naviMode.isGuidingMode() && s.routeOption == expected,
                "result=$state (기대 안내 중, routeOption=$expected, isMock=false)",
            )
            when {
                s?.tollFare == null -> log("INFO [1-tollFare] 관측 못 함(null) — 이 Repository 가 뜬 뒤 onRouteStarted 를 받지 않음")
                expected == "FREE" -> check("1-tollFare", s.tollFare == 0, "tollFare=${s.tollFare} (기대 0)")
                else -> log("INFO [1-tollFare] RECOMMENDED tollFare=${s.tollFare}")
            }

            // 2) 2번 어댑터 + ConditionRouteUseCase (조건: 무료도로 선호)
            val feature = DriveProbeDeparture.createFeature(
                vehicleRepository = DriveProbeApplication.instance.vehicleRepository,
                naviHelperRepository = repo,
                driverConditionRepository = InMemoryDriverConditionRepository(
                    DriverCondition(routePreference = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE)),
                ),
            )
            val result = feature.conditionRoute.check(RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE))
            log("2 conditionRoute=$result")
            if (expected == "FREE") {
                // 2번 작업 #27: 통행료 0원이 확인돼야 충족. 못 받았으면 '확인 필요'(TollAvoidanceUnverified)
                val ok = when (result) {
                    is RouteConditionCheck.Satisfied -> result.routeState.tollFare == 0
                    is RouteConditionCheck.TollAvoidanceUnverified -> result.routeState.tollFare == null
                    else -> false
                }
                check("2-free", ok, "result=$result (기대 tollFare 0 → Satisfied, 모름 → TollAvoidanceUnverified)")
            } else {
                val need = result as? RouteConditionCheck.NeedsUserAction
                check(
                    "2-needsUserAction",
                    need != null && need.actions == listOf(RouteUserAction.TURN_ON_TOLL_AVOIDANCE),
                    "result=$result (기대 NeedsUserAction[TURN_ON_TOLL_AVOIDANCE])",
                )
            }
        } finally {
            instrumentation.runOnMainSync { repo.releaseNaviHelper() }
        }
        log("=== 결과: 실패 ${failures.size}건")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun NaviMode.isGuidingMode() = this == NaviMode.ROUTE_GUIDING || this == NaviMode.SIMULATION_GUIDING

    private companion object {
        const val TAG = "TollToggleCheck"
        const val ARG_EXPECTED = "expectedRouteOption"
        const val ARG_WAIT = "waitForGuidingSec"
    }
}
