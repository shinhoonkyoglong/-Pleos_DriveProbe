package ai.pleos.playground.sample.vehicle.data.repository

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionSource
import ai.pleos.playground.sample.vehicle.data.model.UxRestrictionState

/**
 * AAOS CarUxRestrictions 구독 (VehicleRepositoryImpl 내부용)
 *
 * android.car 클래스를 이 클래스에만 모아 둔다. android.car 가 없는 기기에서는 이 클래스 안에서
 * NoClassDefFoundError 가 나고, start() 가 잡아서 UNAVAILABLE(제한 쪽)로 알린다.
 *
 * 호출 순서 근거(에뮬레이터 Pleos Connect v2.1.0 실측, MyApplication/docs/pleos/api-notes/vehicle-state.md §13):
 * - Car.createCar(context, 메인 Handler, 대기 시간, 수명주기 리스너) → ready=true 에서 getCarManager(CAR_UX_RESTRICTION_SERVICE)
 *   대기 시간은 CAR_WAIT_TIMEOUT_DO_NOT_WAIT: 호출 스레드(Application.onCreate = 메인)를 막지 않는다(ANR 방지, 검토 #14b MAJOR-1).
 *   연결되기 전에는 값을 알리지 않는다 → uxRestriction 은 null(UNKNOWN)로 남는다
 * - 리스너 등록 직후 초기값 콜백이 오지 않는다 → getCurrentCarUxRestrictions() 로 현재 값을 먼저 1회 읽는다
 * - 권한 선언 없이 동작(이 에뮬레이터 이미지 한정, 실차 미확인)
 * - Park = (false, 0x0), Drive = (true, 0x50)
 * - 연결 실패·manager null·예외는 모두 UNAVAILABLE 로 알린다 (2번 어댑터가 RESTRICTED 로 매핑. 제한 없음 값을 지어내지 않는다)
 *
 * @param onChanged 값이 바뀔 때마다 호출된다(메인 스레드 Handler 에서 오는 것으로 보이나 리스너 스레드는 미확인)
 */
internal class CarUxRestrictionsMonitor(
    private val context: Context,
    private val onChanged: (UxRestrictionState) -> Unit
) {

    private val tag = "CarUxRestrictions"
    // start() 는 메인, 수명주기·UX 리스너는 Handler, stop() 은 아무 스레드에서 올 수 있어 @Volatile
    @Volatile private var car: Car? = null
    @Volatile private var manager: CarUxRestrictionsManager? = null
    @Volatile private var stopped = false

    /** car service 연결 시작. 실패하면 UNAVAILABLE 상태를 알리고 false */
    fun start(): Boolean {
        // 재진입 가드는 호출하는 VehicleRepositoryImpl(uxRestrictionsMonitor != null)이 맡는다. 인스턴스당 start() 1회 전제
        if (car != null) return true
        stopped = false
        return try {
            val created = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (stopped) return@createCar // stop() 뒤에 온 늦은 콜백은 무시
                if (!ready) {
                    // car service 연결 끊김(서비스 재시작 등). 실측하지 못한 방어 분기.
                    // 이전 manager 의 리스너는 해제하지 않고 버린다(서비스와 함께 무효). 다시 ready=true 가 오면 새 manager 에 등록한다
                    Log.w(tag, "car service not ready → restricted")
                    manager = null
                    onChanged(UxRestrictionState.unavailable())
                    return@createCar
                }
                car = c
                subscribe(c)
            }
            // createCar 가 null 을 돌려주면(AOSP 상 리스너도 안 불림) 상태는 null(UNKNOWN)로 남고 재시도하지 않는다 [미확인 경로]
            if (created == null) Log.w(tag, "Car.createCar returned null → uxRestriction stays unknown")
            if (car == null) car = created // 수명주기 리스너가 먼저 불렸으면 이미 설정됨
            true
        } catch (t: Throwable) {
            // 자동차가 아닌 기기·android.car 라이브러리 없음(NoClassDefFoundError) 등
            Log.w(tag, "Car.createCar failed → restricted: ${t.javaClass.simpleName}: ${t.message}")
            car = null
            onChanged(UxRestrictionState.unavailable())
            false
        }
    }

    private fun subscribe(c: Car) {
        try {
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            if (m == null) {
                Log.w(tag, "CarUxRestrictionsManager is null → restricted")
                onChanged(UxRestrictionState.unavailable())
                return
            }
            manager = m
            // 등록 직후 초기값 콜백이 없으므로 현재 값을 먼저 읽는다
            val current = m.currentCarUxRestrictions
            if (current != null) publish(current) else onChanged(UxRestrictionState.unavailable())
            m.registerListener { restrictions -> publish(restrictions) }
        } catch (t: Throwable) {
            Log.w(tag, "subscribe failed → restricted: ${t.javaClass.simpleName}: ${t.message}")
            onChanged(UxRestrictionState.unavailable())
        }
    }

    private fun publish(r: CarUxRestrictions) {
        onChanged(
            UxRestrictionState(
                requiresDistractionOptimization = r.isRequiresDistractionOptimization,
                activeRestrictions = r.activeRestrictions,
                source = UxRestrictionSource.CAR_SERVICE
            )
        )
    }

    /** 리스너 해제 + car service 연결 해제 */
    fun stop() {
        stopped = true
        try {
            manager?.unregisterListener()
        } catch (t: Throwable) {
            Log.w(tag, "unregisterListener error: ${t.message}")
        }
        try {
            car?.disconnect()
        } catch (t: Throwable) {
            Log.w(tag, "Car disconnect error: ${t.message}")
        } finally {
            manager = null
            car = null
        }
    }
}
