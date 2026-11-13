package ai.pleos.playground.sample.vehicle.data.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.sample.vehicle.data.model.DrivingState
import ai.pleos.playground.sample.vehicle.data.model.GearPosition
import ai.pleos.playground.sample.vehicle.data.model.HvacControlCommand
import ai.pleos.playground.sample.vehicle.data.model.TurnSignalState
import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionSource
import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionState
import ai.pleos.playground.sample.vehicle.data.model.VehicleDriveData
import ai.pleos.playground.sample.vehicle.data.model.VehicleSignalEvent
import ai.pleos.playground.sample.vehicle.data.model.WindowControlCommand
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class VehicleRepositoryImpl(
    context: Context
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

    // ---- 3번 포지션 확장 (컨디션 판단) ----
    private val _gear = MutableStateFlow(GearPosition.UNKNOWN)
    override val gear: StateFlow<GearPosition> = _gear.asStateFlow()

    private val _turnSignal = MutableStateFlow(TurnSignalState.OFF)
    override val turnSignal: StateFlow<TurnSignalState> = _turnSignal.asStateFlow()

    private val _brakePressed = MutableStateFlow(false)
    override val brakePressed: StateFlow<Boolean> = _brakePressed.asStateFlow()

    private val _hasSpeedSignal = MutableStateFlow(false)
    override val hasSpeedSignal: StateFlow<Boolean> = _hasSpeedSignal.asStateFlow()

    // 콜백 스레드에서 tryEmit으로 바로 넣는다. 수신자가 늦으면 가장 오래된 것부터 버린다(10Hz 기준 약 25초분)
    private val _signalEvents = MutableSharedFlow<VehicleSignalEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val signalEvents: SharedFlow<VehicleSignalEvent> = _signalEvents.asSharedFlow()

    private val carProperties = CarPropertySource(
        appContext,
        onSpeedMps = { t, mps -> onSpeed(t, mps * 3.6f) },
        onGearRaw = { t, raw -> onGear(t, GearPosition.fromAaos(raw)) },
    )

    // 주행 중 UX 제한(CarUxRestrictions). 첫 값을 읽기 전엔 null
    private val _uxRestriction = MutableStateFlow<UxRestrictionState?>(null)
    override val uxRestriction: StateFlow<UxRestrictionState?> = _uxRestriction.asStateFlow()
    private var uxRestrictionsMonitor: CarUxRestrictionsMonitor? = null

    private val steeringWheelListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            // null은 "값 없음"이다. 0°(직진)로 바꾸면 신호 끊김이 직진으로 보이므로 무시한다
            if (angle == null) return
            onSteering(SystemClock.elapsedRealtime(), angle)
        }

        override fun onFailed(e: Exception) {
            Log.e(tag, "SteeringWheelAngleListener failed: ${e.message}")
        }
    }

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            val s = when (state) {
                TurnSignalValue.LEFT -> TurnSignalState.LEFT
                TurnSignalValue.RIGHT -> TurnSignalState.RIGHT
                TurnSignalValue.OFF -> TurnSignalState.OFF
                null -> return
            }
            onTurnSignal(SystemClock.elapsedRealtime(), s)
        }

        override fun onFailed(e: Exception) {
            Log.e(tag, "TurnSignalStateListener failed: ${e.message}")
        }
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            if (status == null) return
            onBrake(SystemClock.elapsedRealtime(), status)
        }

        override fun onFailed(e: Exception) {
            Log.e(tag, "BrakePedalStatusListener failed: ${e.message}")
        }
    }

    override fun initialize(): Boolean {
        // CarUxRestrictions 는 Vehicle SDK 와 별개(AAOS car service)라 Vehicle SDK 성공 여부와 무관하게 먼저 연결한다
        startUxRestrictions()

        if (isInitialized) return true

        // AAOS 속도·기어 (Pleos SDK와 별개라 SDK 초기화가 실패해도 시도한다)
        startCarPropertyUpdates()

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
            // 방향지시등·브레이크 리스너 등록 (변경 시에만 콜백)
            try {
                v.getTurnSignal().registerTurnSignalState(turnSignalListener)
            } catch (e: Exception) {
                Log.w(tag, "Register turn signal listener failed: ${e.message}")
            }
            try {
                v.getBrake().registerBrakePedalStatus(brakeListener)
            } catch (e: Exception) {
                Log.w(tag, "Register brake listener failed: ${e.message}")
            }

            Log.i(tag, "Vehicle SDK successfully initialized.")
            true
        } catch (e: Exception) {
            Log.w(tag, "Vehicle SDK initialization failed; control is unavailable: ${e.message}")
            isInitialized = false
            false
        }
    }

    /**
     * CarUxRestrictions 구독 시작. 실패(자동차 아닌 기기·android.car 없음 등)하면 Mock 값 대신
     * UNAVAILABLE(제한 쪽) 상태를 넣는다 — enableMockFallback 이어도 "제한 없음" 을 지어내지 않는다.
     */
    private fun startUxRestrictions() {
        if (uxRestrictionsMonitor != null) return
        try {
            val monitor = CarUxRestrictionsMonitor(appContext) { state -> _uxRestriction.value = state }
            uxRestrictionsMonitor = monitor
            if (!monitor.start()) {
                Log.w(tag, "CarUxRestrictions unavailable. Reporting restricted (fallback).")
                uxRestrictionsMonitor = null // 다음 initialize() 때 다시 시도
            }
        } catch (t: Throwable) {
            Log.w(tag, "CarUxRestrictions init failed. Reporting restricted (fallback): ${t.message}")
            uxRestrictionsMonitor = null
            _uxRestriction.value = UxRestrictionState.unavailable()
        }
    }

    override fun startCarPropertyUpdates(): Boolean = carProperties.start()

    override fun release() {
        try {
            uxRestrictionsMonitor?.stop()
        } catch (t: Throwable) {
            Log.w(tag, "CarUxRestrictions release error: ${t.message}")
        } finally {
            uxRestrictionsMonitor = null
        }

        try {
            vehicle?.getSteeringWheel()?.unregisterSteeringWheelAngle(steeringWheelListener)
        } catch (e: Exception) {
            Log.w(tag, "Unregister steering wheel listener error: ${e.message}")
        }
        try {
            vehicle?.getTurnSignal()?.unregisterTurnSignalState(turnSignalListener)
            vehicle?.getBrake()?.unregisterBrakePedalStatus(brakeListener)
        } catch (e: Exception) {
            Log.w(tag, "Unregister turn signal/brake listener error: ${e.message}")
        }
        carProperties.stop()

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

    private val controls = VehicleControlClient { vehicle.takeIf { isInitialized } }

    internal suspend fun controlRoundTrip(kind: String, seat: SeatValue, guard: () -> Unit): Result<String> = when (kind) {
        "temperature" -> controls.temperatureRoundTrip(seat, guard)
        "fan" -> controls.fanRoundTrip(seat, guard)
        "window" -> controls.windowRoundTrip(WindowValue.valueOf(seat.name), guard)
        else -> Result.failure(IllegalArgumentException("Unknown probe kind: $kind"))
    }

    override suspend fun setHvacPower(enabled: Boolean, area: SeatValue) = controls.power(enabled, area)
    override suspend fun setHvacAc(enabled: Boolean, area: SeatValue) = controls.ac(enabled, area)
    override suspend fun setHvacTemperature(temperature: Float, unit: TemperatureUnit, area: SeatValue) =
        controls.temperature(temperature, unit, area)
    override suspend fun setHvacFanSpeed(speed: Int, area: SeatValue) = controls.fan(speed, area)
    override suspend fun setHvacAuto(enabled: Boolean, area: SeatValue) = controls.auto(enabled, area)

    override suspend fun controlHvac(command: HvacControlCommand): Result<Unit> = controlResult {
        require(command.airCleaner == null) { "airCleaner control is not implemented" }
        val area = command.targetArea
        command.power?.let { setHvacPower(it, area).getOrThrow() }
        command.ac?.let { setHvacAc(it, area).getOrThrow() }
        command.auto?.let { setHvacAuto(it, area).getOrThrow() }
        command.targetTemperature?.let { setHvacTemperature(it, command.temperatureUnit, area).getOrThrow() }
        command.fanSpeed?.let { setHvacFanSpeed(it, area).getOrThrow() }
        Unit
    }

    override suspend fun setWindowPosition(position: Int, area: WindowValue) = controls.window(position, area)
    override suspend fun controlWindow(command: WindowControlCommand) = setWindowPosition(command.position, command.targetArea)
    override suspend fun triggerFatigueVentilationIntervention() = controls.mild()
    override suspend fun openVentilationWindow(percentage: Int) = controls.windowPercent(percentage, WindowValue.ROW_1_LEFT)

    override suspend fun closeAllWindows(): Result<Unit> = controlResult {
        // Driver and front passenger windows; rear support must be discovered separately.
        controls.windowPercent(0, WindowValue.ROW_1_LEFT).getOrThrow()
        controls.windowPercent(0, WindowValue.ROW_1_RIGHT).getOrThrow()
        Unit
    }

    override fun injectMockSteeringAngle(angleDeg: Float) {
        onSteering(SystemClock.elapsedRealtime(), angleDeg)
    }

    override fun injectMockSpeed(speedKmh: Float) {
        onSpeed(SystemClock.elapsedRealtime(), speedKmh)
    }

    override fun injectMockDriveData(speedKmh: Float, angleDeg: Float) {
        val now = SystemClock.elapsedRealtime()
        onSpeed(now, speedKmh)
        onSteering(now, angleDeg)
    }

    override fun setDrivingState(state: DrivingState) {
        _drivingState.value = state
    }

    override fun injectMockUxRestriction(requiresDistractionOptimization: Boolean, activeRestrictions: Int) {
        _uxRestriction.value = UxRestrictionState(
            requiresDistractionOptimization = requiresDistractionOptimization,
            activeRestrictions = activeRestrictions,
            source = UxRestrictionSource.MOCK
        )
    }

    override fun injectMockGear(gear: GearPosition) {
        onGear(SystemClock.elapsedRealtime(), gear)
    }

    override fun injectMockTurnSignal(state: TurnSignalState) {
        onTurnSignal(SystemClock.elapsedRealtime(), state)
    }

    override fun injectMockBrake(pressed: Boolean) {
        onBrake(SystemClock.elapsedRealtime(), pressed)
    }

    // =========================================================================
    // 신호 공통 처리: StateFlow 갱신 + signalEvents 발행 (실제 콜백·mock 공통)
    // =========================================================================

    private fun onSteering(timeMs: Long, angleDeg: Float) {
        _steeringAngle.value = angleDeg
        updateDriveData(steering = angleDeg)
        _signalEvents.tryEmit(VehicleSignalEvent.Steering(timeMs, angleDeg))
    }

    private fun onSpeed(timeMs: Long, speedKmh: Float) {
        _vehicleSpeed.value = speedKmh
        _hasSpeedSignal.value = true
        updateDriveData(speed = speedKmh)
        _signalEvents.tryEmit(VehicleSignalEvent.Speed(timeMs, speedKmh))
    }

    private fun onGear(timeMs: Long, gear: GearPosition) {
        _gear.value = gear
        _signalEvents.tryEmit(VehicleSignalEvent.Gear(timeMs, gear))
    }

    private fun onTurnSignal(timeMs: Long, state: TurnSignalState) {
        _turnSignal.value = state
        _signalEvents.tryEmit(VehicleSignalEvent.TurnSignal(timeMs, state))
    }

    private fun onBrake(timeMs: Long, pressed: Boolean) {
        _brakePressed.value = pressed
        _signalEvents.tryEmit(VehicleSignalEvent.Brake(timeMs, pressed))
    }

    private fun updateDriveData(speed: Float? = null, steering: Float? = null) {
        // 조향(SDK)과 속도(Car API) 콜백이 서로 다른 스레드에서 올 수 있어 원자적으로 갱신한다
        _driveData.update { current ->
            current.copy(
                speedKmh = speed ?: current.speedKmh,
                steeringAngleDeg = steering ?: current.steeringAngleDeg,
                timestamp = System.currentTimeMillis()
            )
        }
    }
}
