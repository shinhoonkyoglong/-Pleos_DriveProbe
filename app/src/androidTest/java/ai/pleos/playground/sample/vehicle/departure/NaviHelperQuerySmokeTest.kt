package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.data.model.NaviDestinationInfo
import ai.pleos.playground.sample.vehicle.data.model.NaviMode
import ai.pleos.playground.sample.vehicle.data.repository.NaviHelperRepositoryImpl
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DepartureDeadlineResult
import com.pleos.departure.model.DepartureSnapshot
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.RadiusCheckResult
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.TimeConstraint
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 1번 NaviHelperRepositoryImpl 에 2번 연동으로 추가한 조회 계열(getDestinationInfo·getRouteStateInfo·requestTrip·getCurrentLocation)이
 * 실제 내비(에뮬레이터 Pleos Connect v2.1.0)에서 기대대로 동작하는지 확인하는 스모크 테스트(작업 #13).
 *
 * 기대 동작 근거: MyApplication/docs/pleos/api-notes/navi-runtime-check.md §3·§10·§11 (프로브 실행 확인).
 *
 * 순서가 중요한 하나의 시나리오라 @Test 하나로 묶었다:
 *  1) 목적지 없음 → getDestinationInfo = success(null)(0 값 → null), 경로 상태 = 안내 아님
 *  2) 테스트 전용 NaviHelper 로 서울역 requestRoute → 안내 시작 대기(최대 25초) → 목적지·경로 상태·ETA·위치 조회
 *  3) 1번 Repository 위 2번 어댑터(DriveProbeDeparture.createFeature)로 출발 전 확인 1회
 *  4) 정리: cancelRoute 로 안내 종료 → 목적지 없음 확인
 * 각 항목 결과는 태그 [TAG] 로 logcat 에 남기고, 실패는 모아 두었다가 마지막에 한 번에 실패시킨다(모든 관측값을 남기기 위해).
 *
 * 주의:
 * - 실제 내비 상태를 바꾼다(목적지 설정 후 취소). 에뮬레이터·개발 기기에서만 돌린다.
 * - requestRoute/cancelRoute 는 앱 코드에 없는 기능이라 테스트에서 SDK 를 직접 부른다(권한 NAVI_ROUTE_SEARCH 는 debug 매니페스트에만 선언).
 * - 테스트 전용 NaviHelper 는 경로 설정·취소만 하고 조회는 하지 않는다(콜백에 요청 ID 가 없어 조회 응답이 섞이지 않게).
 * - Application 의 departureFeature 는 쓰지 않는다: 그 안의 NaviHelperRepositoryImpl 이 두 번째 NaviHelper 연결을 만들고,
 *   운전자 조건이 미설정(ConditionNotSet)이라 마감 계산까지 가지 않는다. 같은 조립 함수(createFeature)에 이 테스트의 Repository 와
 *   운전자 조건을 넘겨 만든다.
 */
@RunWith(AndroidJUnit4::class)
class NaviHelperQuerySmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** 실패 항목 모음. 마지막에 비어 있지 않으면 테스트 실패. */
    private val failures = mutableListOf<String>()

    private fun log(msg: String) = Log.i(TAG, msg)

    private fun check(name: String, ok: Boolean, observed: String) {
        log("${if (ok) "PASS" else "FAIL"} [$name] $observed")
        if (!ok) failures += "[$name] $observed"
    }

    // ---------------------------------------------------------------------
    // 테스트 전용 NaviHelper (경로 설정·취소용)
    // ---------------------------------------------------------------------

    @Volatile private var routeStarted: CountDownLatch? = null
    @Volatile private var routeCancelled: CountDownLatch? = null
    @Volatile private var lastRouteStart: RouteStartInfo? = null

    private val testListener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            lastRouteStart = routeStartInfo
            log("testHelper CB onRouteStarted $routeStartInfo")
            routeStarted?.countDown()
        }

        override fun onRouteCancelled() {
            log("testHelper CB onRouteCancelled")
            routeCancelled?.countDown()
        }

        override fun onError(errorCode: NaviErrorCode) {
            log("testHelper CB onError $errorCode")
        }
    }

    private fun connectTestHelper(): NaviHelper {
        val helper = NaviHelper(context)
        val latch = CountDownLatch(1)
        var connected = false
        instrumentation.runOnMainSync {
            helper.initialize(
                onConnected = {
                    connected = true
                    helper.addListener(testListener)
                    latch.countDown()
                },
                onFailed = { t ->
                    log("testHelper initialize.onFailed ${t.javaClass.name}: ${t.message}")
                    latch.countDown()
                },
            )
        }
        latch.await(CONNECT_WAIT_SEC, TimeUnit.SECONDS)
        if (!connected) fail("테스트 전용 NaviHelper 연결 실패 — 내비/NaviHelper 서비스가 떠 있는지 확인")
        log("testHelper connected")
        return helper
    }

    /** 안내를 취소하고 onRouteCancelled 를 기다린다. 이미 안내가 없으면 콜백이 안 올 수 있어 타임아웃은 실패로 보지 않는다. */
    private fun cancelRoute(helper: NaviHelper): Boolean {
        val latch = CountDownLatch(1)
        routeCancelled = latch
        var threw: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                helper.cancelRoute()
            } catch (t: Throwable) {
                threw = t
            }
        }
        threw?.let { log("testHelper cancelRoute threw ${it.javaClass.name}: ${it.message}"); return false }
        val ok = latch.await(CANCEL_WAIT_SEC, TimeUnit.SECONDS)
        routeCancelled = null
        log("testHelper cancelRoute → onRouteCancelled ${if (ok) "받음" else "안 옴(${CANCEL_WAIT_SEC}s)"}")
        return ok
    }

    @Test
    fun naviHelperQueries_workAgainstRealNavi() = runBlocking {
        val repo = NaviHelperRepositoryImpl(context, enableMockFallback = false)
        val helper = connectTestHelper()
        try {
            // 사전 정리: 이전 실행·사람 조작으로 남은 안내가 있으면 끈다.
            val pre = repo.getRouteStateInfo()
            log("pre getRouteStateInfo=$pre")
            if (pre.getOrNull()?.naviMode == NaviMode.ROUTE_GUIDING || pre.getOrNull()?.naviMode == NaviMode.SIMULATION_GUIDING) {
                log("pre: 남은 안내가 있어 취소")
                cancelRoute(helper)
                delay(1_000)
            }

            step1NoDestination(repo)
            step2WithDestination(repo, helper)
            step3ThroughAdapter(repo)
        } finally {
            step4Cleanup(repo, helper)
        }
        log("=== 결과: 실패 ${failures.size}건")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // ---------------------------------------------------------------------
    // 1) 목적지 없음
    // ---------------------------------------------------------------------
    private suspend fun step1NoDestination(repo: NaviHelperRepositoryImpl) {
        log("=== 1) 목적지 없음")
        val dest = repo.getDestinationInfo()
        log("1 getDestinationInfo=$dest")
        check("1-dest-null", dest.isSuccess && dest.getOrNull() == null, "result=$dest (기대 success(null): 0 값 객체 → null, Mock 아님)")

        val state = repo.getRouteStateInfo()
        log("1 getRouteStateInfo=$state")
        val s = state.getOrNull()
        check(
            "1-state-not-guiding",
            s != null && !s.isMock && s.naviMode != NaviMode.ROUTE_GUIDING && s.naviMode != NaviMode.SIMULATION_GUIDING,
            "result=$state (기대 SAFE_GUIDING 등 안내 아님, isMock=false)",
        )
    }

    // ---------------------------------------------------------------------
    // 2) 목적지 설정 후 조회
    // ---------------------------------------------------------------------
    private suspend fun step2WithDestination(repo: NaviHelperRepositoryImpl, helper: NaviHelper) {
        log("=== 2) 서울역 requestRoute 후 조회")
        val started = CountDownLatch(1)
        routeStarted = started
        var threw: Throwable? = null
        val requestedAt = System.currentTimeMillis()
        instrumentation.runOnMainSync {
            try {
                helper.requestRoute(
                    RouteInfo(
                        latitude = SEOUL_STN_LAT, longitude = SEOUL_STN_LON,
                        poiName = SEOUL_STN_NAME, poiId = SEOUL_STN_POI_ID, address = "", poiSubId = "",
                        routeOption = RouteOption.RECOMMENDED,
                    )
                )
            } catch (t: Throwable) {
                threw = t
            }
        }
        if (threw != null) {
            check("2-requestRoute", false, "requestRoute threw ${threw!!.javaClass.name}: ${threw!!.message}")
            return
        }

        // 안내 시작 대기: 1초 간격으로 경로 상태 + 목적지를 폴링(최대 25초). onRouteStarted 는 보조 신호로만 기록.
        var destination: NaviDestinationInfo? = null
        var guiding = false
        val deadline = requestedAt + GUIDANCE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(1_000)
            val st = repo.getRouteStateInfo().getOrNull()
            guiding = st?.naviMode == NaviMode.ROUTE_GUIDING || st?.naviMode == NaviMode.SIMULATION_GUIDING
            if (guiding) {
                destination = repo.getDestinationInfo().getOrNull()
                if (destination != null) break
            }
        }
        val waitedMs = System.currentTimeMillis() - requestedAt
        log("2 guidance wait ${waitedMs}ms guiding=$guiding onRouteStarted=${started.count == 0L} routeStart=$lastRouteStart")
        check("2-guidance-started", guiding && destination != null, "guiding=$guiding destination=$destination after ${waitedMs}ms")
        if (destination == null) return

        // 2-a 목적지
        val dest = repo.getDestinationInfo()
        log("2 getDestinationInfo=$dest")
        val d = dest.getOrNull()
        val coordsOk = d != null &&
            kotlin.math.abs(d.latitude - SEOUL_STN_LAT) < COORD_TOLERANCE &&
            kotlin.math.abs(d.longitude - SEOUL_STN_LON) < COORD_TOLERANCE
        check(
            "2-dest",
            d != null && !d.isMock && coordsOk && d.name == SEOUL_STN_NAME,
            "result=$dest (기대 좌표≈($SEOUL_STN_LAT,$SEOUL_STN_LON), name=$SEOUL_STN_NAME, isMock=false)",
        )

        // 2-b 경로 상태
        val state = repo.getRouteStateInfo()
        log("2 getRouteStateInfo=$state")
        val s = state.getOrNull()
        check(
            "2-state-guiding",
            s != null && !s.isMock && s.naviMode == NaviMode.ROUTE_GUIDING && s.routeOption == "RECOMMENDED",
            "result=$state (기대 ROUTE_GUIDING, routeOption=RECOMMENDED)",
        )
        val observedToll = lastRouteStart?.tollFare
        if (observedToll != null) {
            check(
                "2-state-tollFare",
                s?.tollFare == observedToll,
                "repo tollFare=${s?.tollFare}, 테스트 리스너 onRouteStarted.tollFare=$observedToll (기대 같음)",
            )
        } else {
            log("INFO [2-state-tollFare] onRouteStarted 를 테스트 리스너에서 관측 못 함 — repo tollFare=${s?.tollFare}")
        }

        // 2-c requestTrip
        val trip = repo.requestTrip(SEOUL_STN_LAT, SEOUL_STN_LON, poiName = SEOUL_STN_NAME, poiId = SEOUL_STN_POI_ID)
        log("2 requestTrip=$trip")
        val t = trip.getOrNull()
        check(
            "2-trip",
            t != null && !t.isMock && t.etaSeconds > 0 && t.routeOption != null,
            "result=$trip (기대 etaSeconds>0, routeOption 채워짐, isMock=false)",
        )

        // 2-d 현재 위치
        val loc = repo.getCurrentLocation()
        log("2 getCurrentLocation=$loc")
        val l = loc.getOrNull()
        check(
            "2-location",
            l != null && !l.isMock && !(l.latitude == 0.0 && l.longitude == 0.0),
            "result=$loc (기대 isMock=false, (0,0) 아님)",
        )
    }

    // ---------------------------------------------------------------------
    // 3) 1번 Repository 위 2번 어댑터로 출발 전 확인 1회
    // ---------------------------------------------------------------------
    private suspend fun step3ThroughAdapter(repo: NaviHelperRepositoryImpl) {
        log("=== 3) 어댑터 경유 출발 전 확인")
        val condition = DriverCondition(
            timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING),
            routePreference = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE),
            // 에뮬레이터 내비 기본 위치(성남 수정구, navi-runtime-check.md §3.2) 부근을 집으로 둔다.
            radiusCenter = GeoPoint(EMULATOR_HOME_LAT, EMULATOR_HOME_LON),
            allowedRadiusMeters = 50_000.0,
        )
        val feature = DriveProbeDeparture.createFeature(
            vehicleRepository = DriveProbeApplication.instance.vehicleRepository,
            naviHelperRepository = repo,
            driverConditionRepository = InMemoryDriverConditionRepository(condition),
        )
        val snapshot = feature.departureCheck.check()
        log("3 snapshot=$snapshot")
        val checked = snapshot as? DepartureSnapshot.Checked
        if (checked == null) {
            check("3-snapshot-checked", false, "snapshot=$snapshot (기대 Checked)")
            return
        }
        val destination = checked.destination
        check(
            "3-destination",
            destination is DataResult.Available && destination.value is NaviDestination.Present,
            "destination=$destination",
        )
        val deadline = checked.deadline
        check(
            "3-deadline",
            deadline !is DepartureDeadlineResult.DestinationNotSet && deadline !is DepartureDeadlineResult.DestinationUnavailable,
            "deadline=$deadline (기대 Deadline/BeforeSunrise 등 — 내비 사유의 목적지 없음·확인 불가가 아님)",
        )
        if (deadline is DepartureDeadlineResult.Deadline) {
            log("3 deadline stage=${deadline.stage} deadline=${deadline.deadline} sunset=${deadline.sunset.sunset} eta=${deadline.eta.duration} source=${deadline.eta.source}")
        }
        check("3-radius", checked.radius is RadiusCheckResult.Checked, "radius=${checked.radius}")
        log("3 route=${checked.route}")
    }

    // ---------------------------------------------------------------------
    // 4) 정리
    // ---------------------------------------------------------------------
    private suspend fun step4Cleanup(repo: NaviHelperRepositoryImpl, helper: NaviHelper) {
        log("=== 4) 정리: cancelRoute")
        val state = repo.getRouteStateInfo().getOrNull()
        if (state?.naviMode == NaviMode.ROUTE_GUIDING || state?.naviMode == NaviMode.SIMULATION_GUIDING) {
            cancelRoute(helper)
        }
        delay(1_000)
        val dest = repo.getDestinationInfo()
        val after = repo.getRouteStateInfo()
        log("4 after cancel getDestinationInfo=$dest getRouteStateInfo=$after")
        check(
            "4-cleanup",
            dest.isSuccess && dest.getOrNull() == null && after.getOrNull()?.naviMode != NaviMode.ROUTE_GUIDING,
            "dest=$dest state=$after (기대 목적지 없음, 안내 아님)",
        )
        instrumentation.runOnMainSync {
            try {
                helper.removeListener(testListener)
                helper.release()
            } catch (t: Throwable) {
                log("testHelper release threw ${t.message}")
            }
        }
        instrumentation.runOnMainSync { repo.releaseNaviHelper() }
    }

    private companion object {
        const val TAG = "NaviQuerySmoke"

        /** 서울역(프로브와 같은 좌표, navi-runtime-check.md §2) */
        const val SEOUL_STN_LAT = 37.5547
        const val SEOUL_STN_LON = 126.9707
        const val SEOUL_STN_NAME = "서울역"
        const val SEOUL_STN_POI_ID = "smoke-seoul-station"

        /** 에뮬레이터 내비 기본 위치(§3.2: 37.4127212, 127.094681) */
        const val EMULATOR_HOME_LAT = 37.4127
        const val EMULATOR_HOME_LON = 127.0947

        /** 좌표 비교 허용 오차(도). 실측은 요청값과 정확히 같았다(§3.4). */
        const val COORD_TOLERANCE = 0.0005

        const val CONNECT_WAIT_SEC = 10L
        const val CANCEL_WAIT_SEC = 10L

        /** 안내 시작 대기. 실측 8.3~16.3초(§3.3·§11.2) */
        const val GUIDANCE_WAIT_MS = 25_000L
    }
}
