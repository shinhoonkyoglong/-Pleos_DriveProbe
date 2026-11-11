package ai.pleos.playground.sample.vehicle.departure

import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionSource
import ai.pleos.playground.sample.vehicle.data.repository.VehicleRepositoryImpl
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pleos.departure.model.DrivingRestriction
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import android.os.SystemClock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1번 VehicleRepositoryImpl.uxRestriction(AAOS CarUxRestrictions) → 2번 DriveProbeVehicleAdapter 스모크 테스트(작업 #14b).
 *
 * 전제: 에뮬레이터(Pleos Connect v2.1.0) 기어가 **Park**. 기대값 근거: MyApplication/docs/pleos/api-notes/vehicle-state.md §13.3
 * (Park = requiresDistractionOptimization false, activeRestrictions 0x0).
 * Drive 전환은 Virtual Console(브라우저) 조작이 필요해 자동 테스트에서 빼었다(Drive = true/0x50 은 작업 #14a 프로브로 실측).
 *
 * 재작업 1회차: Car.createCar 를 CAR_WAIT_TIMEOUT_DO_NOT_WAIT 로 바꿔 initialize() 가 연결을 기다리지 않는다.
 * 첫 값은 수명주기 콜백(ready=true) 뒤에 채워지므로 withTimeout 안에서 null 이 아닌 값이 올 때까지 기다린다(최대 [TIMEOUT_MS]).
 */
@RunWith(AndroidJUnit4::class)
class UxRestrictionSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = VehicleRepositoryImpl(context)

    @After
    fun tearDown() {
        repository.release()
    }

    @Test
    fun park_readsCarServiceValue_andAdapterReportsUnrestricted() = runBlocking {
        val started = SystemClock.elapsedRealtime()
        repository.initialize()
        Log.i(TAG, "initialize() took ${SystemClock.elapsedRealtime() - started}ms")

        // 리스너 초기 콜백이 없으므로 Impl 이 getCurrentCarUxRestrictions() 로 첫 값을 채워야 한다
        assertParkValue("first")

        val restriction = withTimeout(TIMEOUT_MS) {
            DriveProbeVehicleAdapter(repository).observeDrivingRestriction().first()
        }
        Log.i(TAG, "adapter restriction=$restriction")
        assertEquals(DrivingRestriction.UNRESTRICTED, restriction)
    }

    /** release() 뒤 다시 initialize() 하면 새로 구독해 값을 다시 채우는지 (unregisterListener·disconnect 후 재연결) */
    @Test
    fun releaseThenInitialize_resubscribes() = runBlocking {
        repository.initialize()
        assertParkValue("before release")

        repository.release()
        // release 가 값을 지우지는 않으므로 Mock 으로 덮어 두고, 재구독이 실제 값으로 다시 바꾸는지 본다
        repository.injectMockUxRestriction(requiresDistractionOptimization = true, activeRestrictions = 0x1ff)

        repository.initialize()
        val state = withTimeout(TIMEOUT_MS) {
            repository.uxRestriction.filterNotNull().first { it.source == UxRestrictionSource.CAR_SERVICE }
        }
        Log.i(TAG, "after re-initialize uxRestriction=$state")
        assertFalse(state.requiresDistractionOptimization)
    }

    private suspend fun assertParkValue(label: String) {
        val waitStart = SystemClock.elapsedRealtime()
        val state = withTimeout(TIMEOUT_MS) { repository.uxRestriction.filterNotNull().first() }
        Log.i(TAG, "[$label] uxRestriction=$state (waited ${SystemClock.elapsedRealtime() - waitStart}ms)")
        assertEquals(UxRestrictionSource.CAR_SERVICE, state.source)
        assertFalse(state.requiresDistractionOptimization)
        assertEquals(0x0, state.activeRestrictions)
    }

    private companion object {
        const val TAG = "UxRestrictionSmoke"
        const val TIMEOUT_MS = 10_000L
    }
}
