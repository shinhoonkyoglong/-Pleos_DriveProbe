package ai.pleos.playground.sample.vehicle.departure

import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.DrivingRestriction
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.RouteConstraint
import com.pleos.departure.model.RoutePreference
import com.pleos.departure.model.TimeConstraint
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.port.DriverConditionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 설정 화면 상태: 기본값 vs 저장값, 기본값 미저장(D-9), 위치 실패·재시도, 검증, 저장, 주행 중 잠금 */
class DriverConditionSettingsControllerTest {

    private class FakeRepository(
        var stored: DriverCondition? = null,
        var readFailure: UnavailableReason? = null,
        var saveFails: Boolean = false,
    ) : DriverConditionRepository {
        val saves = mutableListOf<DriverCondition>()

        override suspend fun getCondition(): DataResult<DriverCondition> {
            readFailure?.let { return DataResult.Unavailable(it) }
            return stored?.let { DataResult.Available(it) } ?: DataResult.Unavailable(UnavailableReason.NO_DATA)
        }

        override suspend fun saveCondition(condition: DriverCondition): DataResult<DriverCondition> {
            if (saveFails) return DataResult.Unavailable(UnavailableReason.SERVICE_ERROR)
            saves += condition
            stored = condition
            return DataResult.Available(condition)
        }

        override fun observeCondition(): Flow<DataResult<DriverCondition?>> = emptyFlow()
    }

    private val here = GeoPoint(latitude = 37.5563, longitude = 126.9236)
    private var locationCalls = 0
    private var location: DataResult<LocatedCenter> = DataResult.Available(LocatedCenter(here, "마포구 서교동"))

    private fun controller(repo: DriverConditionRepository, treatUnknownAsRestricted: Boolean = true) =
        DriverConditionSettingsController(
            repository = repo,
            currentLocation = { locationCalls++; location },
            defaultAllowedRadiusMeters = 10_000.0,
            treatUnknownDrivingAsRestricted = treatUnknownAsRestricted,
        ).also { it.onDrivingRestrictionChanged(DrivingRestriction.UNRESTRICTED) }

    @Test
    fun noSavedCondition_showsDefaultsWithCurrentLocation_andDoesNotPersist() = runBlocking {
        val repo = FakeRepository()
        val c = controller(repo)
        c.load()
        val s = c.state.value
        assertFalse(s.loading)
        assertEquals(DriverConditionForm.defaults().copy(centerLatitude = here.latitude, centerLongitude = here.longitude), s.form)
        assertEquals("마포구 서교동", s.centerAddress)
        assertEquals(SaveStatus.NOT_SAVED, s.saveStatus)
        assertNull(s.savedForm)
        assertTrue(repo.saves.isEmpty()) // 기본값은 저장 버튼 전까지 쓰지 않는다
        assertEquals(1, locationCalls)
    }

    @Test
    fun savedCondition_isShownInsteadOfDefaults_withoutReadingLocation() = runBlocking {
        val saved = DriverCondition(
            timeConstraints = emptySet(),
            routePreference = RoutePreference.of(RouteConstraint.AVOID_HIGHWAY),
            radiusCenter = GeoPoint(latitude = 35.1, longitude = 129.0),
            allowedRadiusMeters = 20_000.0,
        )
        val c = controller(FakeRepository(stored = saved))
        c.load()
        val s = c.state.value
        assertFalse(s.form.noNightDriving)
        assertFalse(s.form.preferTollFree)
        assertTrue(s.form.avoidHighway)
        assertEquals(20.0, s.form.radiusKm!!, 0.0)
        assertEquals(35.1, s.form.centerLatitude!!, 0.0)
        assertEquals(SaveStatus.SAVED, s.saveStatus)
        assertNull(s.centerAddress)
        assertEquals(0, locationCalls)
    }

    @Test
    fun locationUnavailable_leavesCenterEmpty_thenRetryFillsIt() = runBlocking {
        location = DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED, "Mock 거부")
        val c = controller(FakeRepository())
        c.load()
        assertFalse(c.state.value.form.hasCenter)
        assertEquals(LocationStatus.FAILED, c.state.value.locationStatus)

        location = DataResult.Available(LocatedCenter(here))
        c.refreshLocation()
        assertTrue(c.state.value.form.hasCenter)
        assertEquals(LocationStatus.IDLE, c.state.value.locationStatus)
        assertNull(c.state.value.centerAddress) // 빈 주소는 null
    }

    @Test
    fun locationFailureKeepsExistingCenter() = runBlocking {
        val c = controller(FakeRepository(stored = DriverCondition(radiusCenter = here, allowedRadiusMeters = 5_000.0)))
        c.load()
        location = DataResult.Unavailable(UnavailableReason.TIMEOUT)
        c.refreshLocation()
        assertEquals(here.latitude, c.state.value.form.centerLatitude!!, 0.0)
        assertEquals(LocationStatus.FAILED, c.state.value.locationStatus)
    }

    @Test
    fun readFailure_showsDefaultsAndFlag() = runBlocking {
        val c = controller(FakeRepository(readFailure = UnavailableReason.SERVICE_ERROR))
        c.load()
        assertTrue(c.state.value.loadFailed)
        assertEquals(SaveStatus.NOT_SAVED, c.state.value.saveStatus)
        assertTrue(c.state.value.form.noNightDriving)
    }

    @Test
    fun save_mapsFormToConditionAndMarksSaved_thenEditMarksModified() = runBlocking {
        val repo = FakeRepository()
        val c = controller(repo)
        c.load()
        c.setAvoidHighway(true)
        c.increaseRadius()
        c.save()
        assertEquals(
            DriverCondition(
                timeConstraints = setOf(TimeConstraint.NO_NIGHT_DRIVING),
                routePreference = RoutePreference.of(RouteConstraint.PREFER_TOLL_FREE, RouteConstraint.AVOID_HIGHWAY),
                radiusCenter = here,
                allowedRadiusMeters = 55_000.0,
            ),
            repo.saves.single()
        )
        assertEquals(SaveStatus.SAVED, c.state.value.saveStatus)
        assertEquals(SaveResult.SAVED, c.state.value.saveResult)

        c.setNoNightDriving(false)
        assertEquals(SaveStatus.MODIFIED, c.state.value.saveStatus)
        assertNull(c.state.value.saveResult)

        // 다시 열면 저장된 값이 보인다
        c.load()
        assertEquals(SaveStatus.SAVED, c.state.value.saveStatus)
        assertTrue(c.state.value.form.noNightDriving)
    }

    @Test
    fun save_invalidForm_isNotPersisted_andReportsErrors() = runBlocking {
        val repo = FakeRepository(stored = DriverCondition(allowedRadiusMeters = 500_000.0))
        val c = controller(repo)
        c.load()
        c.save()
        assertTrue(repo.saves.isEmpty())
        assertEquals(listOf(DriverConditionFormError.RADIUS_TOO_LARGE), c.state.value.errors)
        c.decreaseRadius() // 500 → 300
        assertTrue(c.state.value.errors.isEmpty())
        c.save()
        assertEquals(300_000.0, repo.saves.single().allowedRadiusMeters!!, 0.0)
    }

    @Test
    fun save_failure_keepsUnsavedStatus() = runBlocking {
        val c = controller(FakeRepository(saveFails = true))
        c.load()
        c.save()
        assertEquals(SaveResult.FAILED, c.state.value.saveResult)
        assertEquals(SaveStatus.NOT_SAVED, c.state.value.saveStatus)
    }

    @Test
    fun whileSaving_editsAreIgnored_soStoredValueMatchesScreen() = runBlocking {
        // 저장이 끝나기 전에 입력하면 저장 완료 시 그 입력이 말없이 사라진다 → 저장 중에는 입력을 받지 않는다(#17 리뷰 MINOR)
        val gate = CompletableDeferred<Unit>()
        val repo = object : DriverConditionRepository by FakeRepository() {
            override suspend fun saveCondition(condition: DriverCondition): DataResult<DriverCondition> {
                gate.await()
                return DataResult.Available(condition)
            }
        }
        val c = controller(repo)
        c.load()
        val before = c.state.value.form

        val saving = launch { c.save() }
        yield() // save() 가 saveCondition 에서 멈출 때까지 진행
        assertTrue(c.state.value.saving)
        c.setAvoidHighway(!before.avoidHighway)
        c.increaseRadius()
        assertEquals(before, c.state.value.form)

        gate.complete(Unit)
        saving.join()
        val s = c.state.value
        assertFalse(s.saving)
        assertEquals(before, s.form)
        assertEquals(SaveStatus.SAVED, s.saveStatus)
    }

    @Test
    fun whileDriving_editsSaveAndLocationAreIgnored() = runBlocking {
        val repo = FakeRepository()
        val c = controller(repo)
        c.load()
        val before = c.state.value.form
        c.onDrivingRestrictionChanged(DrivingRestriction.RESTRICTED)
        assertTrue(c.state.value.editingLocked)
        c.setNoNightDriving(false)
        c.increaseRadius()
        c.refreshLocation()
        c.save()
        assertEquals(before, c.state.value.form)
        assertTrue(repo.saves.isEmpty())
        assertEquals(1, locationCalls)

        c.onDrivingRestrictionChanged(DrivingRestriction.UNRESTRICTED)
        c.setNoNightDriving(false)
        assertFalse(c.state.value.form.noNightDriving)
    }

    @Test
    fun unknownRestriction_followsConfig() {
        val locked = controller(FakeRepository())
        locked.onDrivingRestrictionChanged(DrivingRestriction.UNKNOWN)
        assertTrue(locked.state.value.editingLocked)

        val open = controller(FakeRepository(), treatUnknownAsRestricted = false)
        open.onDrivingRestrictionChanged(DrivingRestriction.UNKNOWN)
        assertFalse(open.state.value.editingLocked)
    }

    @Test
    fun restrictionSurvivesReload() = runBlocking {
        val c = controller(FakeRepository())
        c.onDrivingRestrictionChanged(DrivingRestriction.RESTRICTED)
        c.load()
        assertTrue(c.state.value.editingLocked)
        assertEquals(DrivingRestriction.RESTRICTED, c.state.value.drivingRestriction)
    }
}
