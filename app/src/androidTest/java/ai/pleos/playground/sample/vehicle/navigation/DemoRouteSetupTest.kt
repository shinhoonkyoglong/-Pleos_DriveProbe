package ai.pleos.playground.sample.vehicle.navigation

import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.NaviMode
import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 시연영상 준비용: 내비 목적지를 공식 휴게시설 목록의 시설로 안내 시작한다(에뮬레이터·정차 중만).
 * 명시적 인수(demoRoute=true)가 없으면 건너뛴다. 기존 목적지는 새 목적지로 바뀐다.
 *
 * adb shell am instrument -w --user 10 -e demoRoute true [-e baseName 안성 -e direction 부산] \
 *   -e class ai.pleos.playground.sample.vehicle.navigation.DemoRouteSetupTest \
 *   ai.pleos.playground.sample.vehicle.diagnostic.test/androidx.test.runner.AndroidJUnitRunner
 */
class DemoRouteSetupTest {
    @Test fun setDemoRoute() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("demoRoute=true required", args.getString("demoRoute") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
        val baseName = args.getString("baseName") ?: "안성"
        val direction = args.getString("direction") ?: "부산"
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DriveProbeApplication
        val vehicle = app.vehicleRepository
        val navi = app.realNaviHelperRepository
        withTimeout(20_000) { while (!vehicle.hasSpeedSignal.value) delay(100) }
        check(vehicle.gear.value == GearPosition.PARK && vehicle.vehicleSpeed.value == 0f) { "Park and speed 0 required" }

        val rows = JSONObject(app.assets.open("navigation/public-rest-stops.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }).getJSONArray("facilities")
        val facility = (0 until rows.length()).map { rows.getJSONObject(it) }
            .single { it.getString("kind") == "SERVICE_AREA" && it.getString("baseName") == baseName && it.getString("direction") == direction }
        val name = "${baseName}휴게소(${direction}방향)"

        val helper = NaviHelper(app)
        val connected = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            helper.initialize(onConnected = { connected.complete(Unit) }, onFailed = { connected.completeExceptionally(it) })
        }
        withTimeout(10_000) { connected.await() }
        withContext(Dispatchers.Main) {
            helper.requestRoute(RouteInfo(latitude = facility.getDouble("latitude"), longitude = facility.getDouble("longitude"),
                poiName = name, poiId = "", address = facility.getString("address"), poiSubId = "", routeOption = RouteOption.RECOMMENDED))
        }
        withTimeout(40_000) {
            while (navi.getRouteStateInfo().getOrThrow().naviMode != NaviMode.ROUTE_GUIDING || navi.getDestinationInfo().getOrThrow() == null) delay(500)
        }
        Log.i("DemoRoute", "route guiding to $name destination=${navi.getDestinationInfo().getOrThrow()?.name}")
    }
}
