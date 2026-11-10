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
        repository.initialize()

        // 리스너 초기 콜백이 없으므로 Impl 이 getCurrentCarUxRestrictions() 로 첫 값을 채워야 한다
        val state = withTimeout(TIMEOUT_MS) { repository.uxRestriction.filterNotNull().first() }
        Log.i(TAG, "uxRestriction=$state")
        assertEquals(UxRestrictionSource.CAR_SERVICE, state.source)
        assertFalse(state.requiresDistractionOptimization)
        assertEquals(0x0, state.activeRestrictions)

        val restriction = withTimeout(TIMEOUT_MS) {
            DriveProbeVehicleAdapter(repository).observeDrivingRestriction().first()
        }
        Log.i(TAG, "adapter restriction=$restriction")
        assertEquals(DrivingRestriction.UNRESTRICTED, restriction)
    }

    private companion object {
        const val TAG = "UxRestrictionSmoke"
        const val TIMEOUT_MS = 10_000L
    }
}
