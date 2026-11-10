package ai.pleos.playground.sample.vehicle.data.repository

import android.content.Context
import android.util.Log
import ai.pleos.playground.sample.vehicle.data.model.DrivingState
import ai.pleos.playground.sample.vehicle.data.model.HvacControlCommand
import ai.pleos.playground.sample.vehicle.data.model.VehicleDriveData
import ai.pleos.playground.sample.vehicle.data.model.WindowControlCommand
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class VehicleRepositoryImpl(
    context: Context,
    private val enableMockFallback: Boolean = true
) : VehicleRepository {

    private val tag = "VehicleRepository"
    private val appContext = context.applicationContext
    private var vehicle: Vehicle? = null
    private var isInitialized = false

    private val _steeringAngle = MutableStateFlow(0.0f)
    override val steeringAngle: StateFlow<Float> = _steeringAngle.asStateFlow()

    private val _vehicleSpeed = MutableStateFlow(0.0f)
    override val vehicleSpeed: StateFlow<Float> = _vehicleSpeed.asStateFlow()

    private val _driveData = MutableStateFlow(VehicleDriveData())
    override val driveData: StateFlow<VehicleDriveData> = _driveData.asStateFlow()

    private val _drivingState = MutableStateFlow(DrivingState.IDLE)
    override val drivingState: StateFlow<DrivingState> = _drivingState.asStateFlow()

    private val steeringWheelListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            val currentAngle = angle ?: 0.0f
            _steeringAngle.value = currentAngle
            updateDriveData(steering = currentAngle)
        }

        override fun onFailed(e: Exception) {
            Log.e(tag, "SteeringWheelAngleListener failed: ${e.message}")
        }
    }

    override fun initialize(): Boolean {
        if (isInitialized) return true

        return try {
            val v = Vehicle(appContext)
            v.initialize()
            vehicle = v
            isInitialized = true

            // 조향각 리스너 등록
            try {
                v.getSteeringWheel().registerSteeringWheelAngle(steeringWheelListener)
            } catch (e: Exception) {
                Log.w(tag, "Register steering wheel listener failed: ${e.message}")
            }

            Log.i(tag, "Vehicle SDK successfully initialized.")
            true
        } catch (e: Exception) {
            Log.w(tag, "Vehicle SDK initialization failed. Running in fallback mode: ${e.message}")
            isInitialized = false
            enableMockFallback
        }
    }

    override fun release() {
        try {
            vehicle?.getSteeringWheel()?.unregisterSteeringWheelAngle(steeringWheelListener)
        } catch (e: Exception) {
            Log.w(tag, "Unregister steering wheel listener error: ${e.message}")
        }

        try {
            vehicle?.release()
        } catch (e: Exception) {
            Log.w(tag, "Vehicle release error: ${e.message}")
        } finally {
            vehicle = null
            isInitialized = false
        }
    }

    // =========================================================================
    // 4번 포지션: HVAC 공조 제어
    // =========================================================================

    override suspend fun setHvacPower(enabled: Boolean, area: SeatValue): Result<Boolean> {
        val sdk = vehicle
        if (!isInitialized || sdk == null) {
            Log.d(tag, "[MOCK/FALLBACK] setHvacPower(enabled=$enabled, area=$area)")
            return Result.success(enabled)
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                val seatArea = SeatArea(area)
                sdk.getHVAC().setHvacPower(
                    area = seatArea,
                    value = enabled,
                    onComplete = { _, value ->
                        continuation.resume(Result.success(value ?: enabled))
                    },
                    onFailed = { exception ->
                        Log.e(tag, "setHvacPower failed: ${exception.message}")
                        continuation.resume(Result.failure(exception))
                    }
                )
            } catch (e: Exception) {
                continuation.resume(Result.failure(e))
            }
        }
    }

    override suspend fun setHvacAc(enabled: Boolean, area: SeatValue): Result<Boolean> {
        val sdk = vehicle
        if (!isInitialized || sdk == null) {
            Log.d(tag, "[MOCK/FALLBACK] setHvacAc(enabled=$enabled, area=$area)")
            return Result.success(enabled)
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                val seatArea = SeatArea(area)
                sdk.getHVAC().setHvacAc(
                    area = seatArea,
                    value = enabled,
                    onComplete = { _, value ->
                        continuation.resume(Result.success(value ?: enabled))
                    },
                    onFailed = { exception ->
                        Log.e(tag, "setHvacAc failed: ${exception.message}")
                        continuation.resume(Result.failure(exception))
                    }
                )
            } catch (e: Exception) {
                continuation.resume(Result.failure(e))
            }
        }
    }

    override suspend fun setHvacTemperature(
        temperature: Float,
        unit: TemperatureUnit,
        area: SeatValue
    ): Result<Float> {
        val sdk = vehicle
        if (!isInitialized || sdk == null) {
            Log.d(tag, "[MOCK/FALLBACK] setHvacTemperature(temp=$temperature, unit=$unit, area=$area)")
            return Result.success(temperature)
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                val seatArea = SeatArea(area)
                sdk.getHVAC().setHvacTemperature(
                    area = seatArea,
                    value = temperature,
                    unit = unit,
                    onComplete = { _, value ->
                        continuation.resume(Result.success(value ?: temperature))
                    },
                    onFailed = { exception ->
                        Log.e(tag, "setHvacTemperature failed: ${exception.message}")
                        continuation.resume(Result.failure(exception))
                    }
                )
            } catch (e: Exception) {
                continuation.resume(Result.failure(e))
            }
        }
    }

    override suspend fun setHvacFanSpeed(speed: Int, area: SeatValue): Result<Int> {
        val sdk = vehicle
        if (!isInitialized || sdk == null) {
            Log.d(tag, "[MOCK/FALLBACK] setHvacFanSpeed(speed=$speed, area=$area)")
            return Result.success(speed)
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                val seatArea = SeatArea(area)
                sdk.getHVAC().setHvacFanSpeed(
                    area = seatArea,
                    value = speed,
                    onComplete = { _, value ->
                        continuation.resume(Result.success(value ?: speed))
                    },
                    onFailed = { exception ->
                        Log.e(tag, "setHvacFanSpeed failed: ${exception.message}")
                        continuation.resume(Result.failure(exception))
                    }
                )
            } catch (e: Exception) {
                continuation.resume(Result.failure(e))
            }
        }
    }

    override suspend fun setHvacAuto(enabled: Boolean, area: SeatValue): Result<Boolean> {
        val sdk = vehicle
        if (!isInitialized || sdk == null) {
            Log.d(tag, "[MOCK/FALLBACK] setHvacAuto(enabled=$enabled, area=$area)")
            return Result.success(enabled)
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                val seatArea = SeatArea(area)
                sdk.getHVAC().setHvacAuto(
                    area = seatArea,
                    value = enabled,
                    onComplete = { _, value ->
                        continuation.resume(Result.success(value ?: enabled))
                    },
                    onFailed = { exception ->
                        Log.e(tag, "setHvacAuto failed: ${exception.message}")
                        continuation.resume(Result.failure(exception))
                    }
                )
            } catch (e: Exception) {
                continuation.resume(Result.failure(e))
            }
        }
    }

    override suspend fun controlHvac(command: HvacControlCommand): Result<Unit> {
        val area = command.targetArea
        command.power?.let { setHvacPower(it, area) }
        command.ac?.let { setHvacAc(it, area) }
        command.auto?.let { setHvacAuto(it, area) }
        command.targetTemperature?.let { setHvacTemperature(it, command.temperatureUnit, area) }
        command.fanSpeed?.let { setHvacFanSpeed(it, area) }
        return Result.success(Unit)
    }

    // =========================================================================
    // 4번 포지션: 창문 제어
    // =========================================================================

    override suspend fun setWindowPosition(position: Int, area: WindowValue): Result<Int> {
        val sdk = vehicle
        if (!isInitialized || sdk == null) {
            Log.d(tag, "[MOCK/FALLBACK] setWindowPosition(pos=$position, area=$area)")
            return Result.success(position)
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                val windowArea = WindowArea(area)
                sdk.getWindow().setWindowPosition(
                    area = windowArea,
                    value = position,
                    onComplete = { _, value ->
                        continuation.resume(Result.success(value ?: position))
                    },
                    onFailed = { exception ->
                        Log.e(tag, "setWindowPosition failed: ${exception.message}")
                        continuation.resume(Result.failure(exception))
                    }
                )
            } catch (e: Exception) {
                continuation.resume(Result.failure(e))
            }
        }
    }

    override suspend fun controlWindow(command: WindowControlCommand): Result<Int> {
        return setWindowPosition(command.position, command.targetArea)
    }

    override suspend fun triggerFatigueVentilationIntervention(): Result<Unit> {
        Log.i(tag, "Triggering Level 1 Fatigue Intervention: HVAC Max Vent + Window Crack Open")
        // 1) 에어컨 가동
        setHvacAc(true, SeatValue.ROW_1_LEFT)
        // 2) 풍량 4단계 상향
        setHvacFanSpeed(4, SeatValue.ROW_1_LEFT)
        // 3) 운전석 창문 살짝 열어 환기 (15%)
        openVentilationWindow(15)
        return Result.success(Unit)
    }

    override suspend fun openVentilationWindow(percentage: Int): Result<Unit> {
        setWindowPosition(percentage, WindowValue.ROW_1_LEFT)
        return Result.success(Unit)
    }

    override suspend fun closeAllWindows(): Result<Unit> {
        setWindowPosition(0, WindowValue.ROW_1_LEFT)
        setWindowPosition(0, WindowValue.ROW_1_RIGHT)
        return Result.success(Unit)
    }

    // =========================================================================
    // 가짜 데이터 주입 및 테스트용 (에뮬레이터 지원)
    // =========================================================================

    override fun injectMockSteeringAngle(angleDeg: Float) {
        _steeringAngle.value = angleDeg
        updateDriveData(steering = angleDeg)
    }

    override fun injectMockSpeed(speedKmh: Float) {
        _vehicleSpeed.value = speedKmh
        updateDriveData(speed = speedKmh)
    }

    override fun injectMockDriveData(speedKmh: Float, angleDeg: Float) {
        _vehicleSpeed.value = speedKmh
        _steeringAngle.value = angleDeg
        updateDriveData(speed = speedKmh, steering = angleDeg)
    }

    override fun setDrivingState(state: DrivingState) {
        _drivingState.value = state
    }

    private fun updateDriveData(speed: Float? = null, steering: Float? = null) {
        val current = _driveData.value
        _driveData.value = current.copy(
            speedKmh = speed ?: current.speedKmh,
            steeringAngleDeg = steering ?: current.steeringAngleDeg,
            timestamp = System.currentTimeMillis()
        )
    }
}
