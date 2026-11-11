package com.pleos.departure

import com.pleos.departure.model.DataResult
import com.pleos.departure.model.DriverCondition
import com.pleos.departure.model.DrivingRestriction
import com.pleos.departure.model.GeoPoint
import com.pleos.departure.model.NaviDestination
import com.pleos.departure.model.Place
import com.pleos.departure.model.RemainingTrip
import com.pleos.departure.model.RouteState
import com.pleos.departure.model.SpeakResult
import com.pleos.departure.model.TripEstimate
import com.pleos.departure.model.UnavailableReason
import com.pleos.departure.port.DriverConditionRepository
import com.pleos.departure.port.NaviRepository
import com.pleos.departure.port.SttRepository
import com.pleos.departure.port.TtsRepository
import com.pleos.departure.port.VehicleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DepartureFeatureTest {

    /** core 테스트 전용 최소 스텁. 모든 외부 기능이 "사용 불가"인 상태. (Fake 모듈은 core를 의존하므로 여기서 쓰지 않는다) */
    private object UnavailableDependencies : DepartureDependencies {
        private val na = DataResult.Unavailable(UnavailableReason.NOT_SUPPORTED)
        override val vehicleRepository = object : VehicleRepository {
            override fun observeDrivingRestriction(): Flow<DrivingRestriction> = flowOf(DrivingRestriction.UNKNOWN)
        }
        override val naviRepository = object : NaviRepository {
            override suspend fun getCurrentLocation(): DataResult<GeoPoint> = na
            override suspend fun getDestination(): DataResult<NaviDestination> = na
            override suspend fun requestTrip(destination: Place): DataResult<TripEstimate> = na
            override suspend fun getRouteState(): DataResult<RouteState> = na
            override fun observeRemainingTrip(): Flow<RemainingTrip> = emptyFlow()
        }
        override val driverConditionRepository = object : DriverConditionRepository {
            override suspend fun getCondition(): DataResult<DriverCondition> = na
            override suspend fun saveCondition(condition: DriverCondition): DataResult<DriverCondition> = na
            override fun observeCondition(): Flow<DataResult<DriverCondition?>> = flowOf(na)
        }
        override val ttsRepository = object : TtsRepository {
            override suspend fun speak(text: String): SpeakResult = SpeakResult.Unavailable(UnavailableReason.NOT_SUPPORTED)
        }
        override val sttRepository = object : SttRepository {
            override suspend fun recognizeOnce(): DataResult<String> = na
        }
        override val clock: Clock = Clock.fixed(Instant.parse("2026-11-09T04:00:00Z"), ZoneOffset.UTC)
        override val config = DepartureConfig()
    }

    @Test
    fun createsFeatureAndExposesConfigAndClock() {
        val feature = DepartureFeature(UnavailableDependencies)

        assertTrue(feature.config === UnavailableDependencies.config)
        assertTrue(feature.clock === UnavailableDependencies.clock)
        assertTrue(DepartureFeature.VERSION.isNotBlank())
    }
}
