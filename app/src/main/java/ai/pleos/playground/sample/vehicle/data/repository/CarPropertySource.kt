package ai.pleos.playground.sample.vehicle.data.repository

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log

/**
 * [컨디션 판단] AAOS 표준 Car API로 속도·기어를 받는다. (Pleos Vehicle SDK에는 속도 API가 없고, Gear API는 @hide·폐기 예정)
 *
 * 데이터 흐름: 차량 → CarPropertyManager 콜백 → [onSpeedMps] / [onGearRaw] → VehicleRepositoryImpl
 *
 * 2026-11-10 에뮬레이터 실측:
 * - PERF_VEHICLE_SPEED: m/s, 연속형(값이 같아도 계속 옴), 10Hz 요청 시 실제 약 6.4Hz
 * - GEAR_SELECTION: 변경 시에만 옴. N=1, R=2, P=4, D=8. 앱 시작 직후 초기값이 실제와 다를 수 있음(콘솔 P인데 8)
 * - 권한: android.car.permission.CAR_SPEED(실행 중 허용 필요), CAR_POWERTRAIN(설치 시 허용)
 */
internal class CarPropertySource(
    context: Context,
    private val onSpeedMps: (timeMs: Long, mps: Float) -> Unit,
    private val onGearRaw: (timeMs: Long, value: Int) -> Unit,
) {
    private val tag = "CarPropertySource"
    private val appContext = context.applicationContext
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var speedRegistered = false
    private var gearRegistered = false

    val isSpeedRegistered: Boolean get() = speedRegistered

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
            val now = SystemClock.elapsedRealtime()
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> (value.value as? Float)?.let { onSpeedMps(now, it) }
                VehiclePropertyIds.GEAR_SELECTION -> (value.value as? Int)?.let { onGearRaw(now, it) }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(tag, "onErrorEvent property=$propertyId area=$areaId")
        }
    }

    /**
     * 연결하고 권한이 있는 속성부터 구독한다. 여러 번 불러도 된다(CAR_SPEED 허용 후 다시 부르면 속도가 추가됨).
     * @return 속도 구독 중이면 true
     */
    fun start(): Boolean {
        val cpm = manager ?: connect() ?: return false

        if (!gearRegistered && hasPermission(PERMISSION_POWERTRAIN)) {
            gearRegistered = register(cpm, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            // 변경 시에만 오는 속성이라 현재 값을 한 번 읽어 둔다(초기값이 틀릴 수 있다는 점은 위 주석 참고)
            if (gearRegistered) readInitialGear(cpm)
        }
        if (!speedRegistered && hasPermission(PERMISSION_SPEED)) {
            speedRegistered = register(cpm, VehiclePropertyIds.PERF_VEHICLE_SPEED, SPEED_RATE_HZ)
        }
        if (!speedRegistered) Log.i(tag, "속도 미구독: $PERMISSION_SPEED 권한 허용 후 start()를 다시 불러야 함")
        return speedRegistered
    }

    fun stop() {
        try {
            manager?.unregisterCallback(callback)
        } catch (e: Exception) {
            Log.w(tag, "unregisterCallback error: ${e.message}")
        }
        try {
            car?.disconnect()
        } catch (e: Exception) {
            Log.w(tag, "disconnect error: ${e.message}")
        }
        car = null
        manager = null
        speedRegistered = false
        gearRegistered = false
    }

    private fun connect(): CarPropertyManager? = try {
        val c = Car.createCar(appContext) ?: return null.also { Log.w(tag, "Car.createCar() returned null") }
        car = c
        (c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager).also { manager = it }
    } catch (t: Throwable) {
        // AAOS가 아닌 기기(android.car 라이브러리 없음)에서는 NoClassDefFoundError가 날 수 있다
        Log.w(tag, "Car API 사용 불가: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun register(cpm: CarPropertyManager, propertyId: Int, rate: Float): Boolean = try {
        cpm.registerCallback(callback, propertyId, rate).also { Log.i(tag, "register property=$propertyId rate=$rate -> $it") }
    } catch (e: Exception) {
        Log.w(tag, "register property=$propertyId failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private fun readInitialGear(cpm: CarPropertyManager) {
        try {
            val v = cpm.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)
            if (v != null && v.status == CarPropertyValue.STATUS_AVAILABLE) onGearRaw(SystemClock.elapsedRealtime(), v.value)
        } catch (e: Exception) {
            Log.w(tag, "initial gear read failed: ${e.message}")
        }
    }

    private fun hasPermission(p: String) = appContext.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val PERMISSION_SPEED = "android.car.permission.CAR_SPEED"
        const val PERMISSION_POWERTRAIN = "android.car.permission.CAR_POWERTRAIN"
        private const val SPEED_RATE_HZ = 10f
    }
}
