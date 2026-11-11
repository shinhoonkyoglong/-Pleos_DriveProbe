package ai.pleos.playground.sample.vehicle.data.repository

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs
import android.util.Log

/** Typed control path. No mock success, guessed ranges, or null-as-success callbacks. */
internal class VehicleControlClient(private val vehicle: () -> Vehicle?) {
    private fun sdk() = vehicle() ?: error("Vehicle SDK is not initialized; control was not sent")

    suspend fun power(value: Boolean, area: SeatValue) = booleanControl("power", value, area,
        { ok, fail -> sdk().getHVAC().checkHvacPowerCapabilities(onComplete = { c, a -> ok(c to a) }, onFailed = fail) },
        { a, ok, fail -> sdk().getHVAC().setHvacPower(a, value, { _, v -> ok(v) }, fail) },
        { a, ok, fail -> sdk().getHVAC().getHvacPowerState(a, ok, fail) })

    suspend fun ac(value: Boolean, area: SeatValue) = booleanControl("AC", value, area,
        { ok, fail -> sdk().getHVAC().checkHvacAcCapabilities(onComplete = { c, a -> ok(c to a) }, onFailed = fail) },
        { a, ok, fail -> sdk().getHVAC().setHvacAc(a, value, { _, v -> ok(v) }, fail) },
        { a, ok, fail -> sdk().getHVAC().getHvacAcState(a, ok, fail) })

    suspend fun auto(value: Boolean, area: SeatValue) = booleanControl("auto", value, area,
        { ok, fail -> sdk().getHVAC().checkHvacAutoCapabilities(onComplete = { c, a -> ok(c to a) }, onFailed = fail) },
        { a, ok, fail -> sdk().getHVAC().setHvacAuto(a, value, { _, v -> ok(v) }, fail) },
        { a, ok, fail -> sdk().getHVAC().getHvacAutoState(a, ok, fail) })

    private suspend fun booleanControl(
        name: String, value: Boolean, seat: SeatValue,
        capability: ((Pair<CapabilityConfig, List<SeatArea>>) -> Unit, (Exception) -> Unit) -> Unit,
        set: (SeatArea, (Boolean?) -> Unit, (Exception) -> Unit) -> Unit,
        read: (SeatArea, (Boolean?) -> Unit, (Exception) -> Unit) -> Unit,
    ): Result<Boolean> = controlResult {
        val (config, areas) = request("$name capability", capability)
        writable(config)
        val area = areas.firstOrNull { it.values.contentEquals(arrayOf(seat)) }
            ?: areas.firstOrNull { seat in it.values }
            ?: error("$name does not support $seat")
        request<Boolean>("set $name") { ok, fail -> set(area, ok, fail) }
        verify(name, value, { request(name) { ok, fail -> read(area, ok, fail) } }) { a, b -> a == b }
    }

    private suspend fun temperatureBounds(seat: SeatValue): Pair<SeatArea, AreaBounds<Float>> {
        val (config, areas) = request<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Float>>>>("temperature capability") { ok, fail ->
            sdk().getHVAC().checkHvacTemperatureCapabilities(onComplete = { c, a, _ -> ok(c to a) }, onFailed = fail)
        }
        writable(config)
        Log.i("VehicleControl", "temperature capability areas=${areas.keys.map { it.values.joinToString() }}")
        return selectSeat(areas, seat)
    }

    private suspend fun fanBounds(seat: SeatValue): Pair<SeatArea, AreaBounds<Int>> {
        val (config, areas) = request<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>>("fan capability") { ok, fail ->
            sdk().getHVAC().checkHvacFanSpeedCapabilities(onComplete = { c, a -> ok(c to a) }, onFailed = fail)
        }
        writable(config)
        return selectSeat(areas, seat)
    }

    private fun <T> selectSeat(areas: Map<SeatArea, T>, seat: SeatValue): Pair<SeatArea, T> {
        // Prefer the individual seat, then the actual combined area (e.g. front left + right).
        val key = areas.keys.firstOrNull { it.values.contentEquals(arrayOf(seat)) }
            ?: areas.keys.firstOrNull { seat in it.values }
            ?: error("Capability does not support $seat")
        return key to areas.getValue(key)
    }

    suspend fun temperature(value: Float, unit: TemperatureUnit, seat: SeatValue): Result<Float> = controlResult {
        val (area, bounds) = temperatureBounds(seat)
        val celsius = if (unit == TemperatureUnit.FAHRENHEIT) (value - 32f) / 1.8f else value
        ControlMath.requireRange(celsius, bounds.minValue, bounds.maxValue)
        request<Float>("set temperature") { ok, fail ->
            sdk().getHVAC().setHvacTemperature(area, value, unit, { a, v ->
                Log.i("VehicleControl", "temperature SET callback area=$a requested=$value returned=$v unit=$unit")
                ok(v)
            }, fail)
        }
        verify("temperature", value, {
            request<Float>("read temperature") { ok, fail -> sdk().getHVAC().getHvacTemperature(area, unit, ok, fail) }
        }) { a, b -> abs(a - b) < 0.11f }
    }

    suspend fun fan(value: Int, seat: SeatValue): Result<Int> = controlResult {
        val (area, bounds) = fanBounds(seat)
        require(value in bounds.minValue..bounds.maxValue) { "Fan $value outside ${bounds.minValue}..${bounds.maxValue}" }
        request<Int>("set fan") { ok, fail -> sdk().getHVAC().setHvacFanSpeed(area, value, { _, v -> ok(v) }, fail) }
        verify("fan", value, {
            request<Int>("read fan") { ok, fail -> sdk().getHVAC().getHvacFanSpeed(area, ok, fail) }
        }) { a, b -> a == b }
    }

    suspend fun window(value: Int, seat: WindowValue): Result<Int> = controlResult {
        val (area, bounds) = windowBounds(seat)
        require(value in bounds.minValue..bounds.maxValue) { "Window $value outside ${bounds.minValue}..${bounds.maxValue}" }
        request<Int>("set window") { ok, fail -> sdk().getWindow().setWindowPosition(area, value, { _, v -> ok(v) }, fail) }
        verify("window", value, {
            request<Int>("read window") { ok, fail -> sdk().getWindow().getCurrentWindowPosition(area, ok, fail) }
        }) { a, b -> a == b }
    }

    private suspend fun windowBounds(seat: WindowValue): Pair<WindowArea, AreaBounds<Int>> {
        val (config, areas) = request<Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>>("window capability") { ok, fail ->
            sdk().getWindow().checkWindowPositionCapabilities(onComplete = { c, a -> ok(c to a) }, onFailed = fail)
        }
        writable(config)
        val area = WindowArea(seat)
        return area to (areas[area] ?: error("Window does not support $seat"))
    }

    suspend fun windowPercent(percent: Int, seat: WindowValue): Result<Unit> = controlResult {
        val (_, bounds) = windowBounds(seat)
        window(ControlMath.windowPosition(percent, bounds.minValue, bounds.maxValue), seat).getOrThrow()
        Unit
    }

    /** MILD policy: lower setpoint by 1 C and raise fan by one supported step. Windows are opt-in. */
    suspend fun mild(): Result<Unit> = controlResult {
        val seat = SeatValue.ROW_1_LEFT
        val (temperatureArea, t) = temperatureBounds(seat)
        val (fanArea, f) = fanBounds(seat)
        val beforeTemperature = request<Float>("read temperature before") { ok, fail ->
            sdk().getHVAC().getHvacTemperature(temperatureArea, TemperatureUnit.CELSIUS, ok, fail)
        }
        val beforeFan = request<Int>("read fan before") { ok, fail -> sdk().getHVAC().getHvacFanSpeed(fanArea, ok, fail) }
        ControlMath.requireRange(beforeTemperature, t.minValue, t.maxValue)
        require(beforeFan in f.minValue..f.maxValue) { "Invalid current fan value $beforeFan" }
        temperature((beforeTemperature - 1f).coerceIn(t.minValue, t.maxValue), TemperatureUnit.CELSIUS, seat).getOrThrow()
        fan((beforeFan.toLong() + 1).coerceIn(f.minValue.toLong(), f.maxValue.toLong()).toInt(), seat).getOrThrow()
        Unit
    }

    /** All diagnostic changes use the same verified control path as real interventions. */
    suspend fun temperatureRoundTrip(seat: SeatValue, guard: () -> Unit = {}): Result<String> = controlResult {
        val (area, bounds) = temperatureBounds(seat)
        controlProbe("temperature seat=$seat", {
            request<Float>("temperature probe before") { ok, fail -> sdk().getHVAC().getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, fail) }
        }, { before ->
            ControlMath.requireRange(before, bounds.minValue, bounds.maxValue)
            (if (before + 1f <= bounds.maxValue) before + 1f else before - 1f).also {
                ControlMath.requireRange(it, bounds.minValue, bounds.maxValue)
            }
        }, { temperature(it, TemperatureUnit.CELSIUS, seat) }, guard)
    }

    suspend fun fanRoundTrip(seat: SeatValue, guard: () -> Unit = {}): Result<String> = controlResult {
        val (area, bounds) = fanBounds(seat)
        Log.i("VehicleControl", "fan probe area=${area.values.joinToString()} range=${bounds.minValue}..${bounds.maxValue}")
        controlProbe("fan area=${area.values.joinToString()}", {
            request<Int>("fan probe before") { ok, fail -> sdk().getHVAC().getHvacFanSpeed(area, ok, fail) }
        }, { before ->
            require(before in bounds.minValue..bounds.maxValue) { "Fan before=$before outside capability" }
            if (before < bounds.maxValue) before + 1 else (before - 1).coerceAtLeast(bounds.minValue)
        }, { fan(it, seat) }, guard)
    }

    suspend fun windowRoundTrip(seat: WindowValue, guard: () -> Unit = {}): Result<String> = controlResult {
        val (area, bounds) = windowBounds(seat)
        Log.i("VehicleControl", "window probe area=$seat range=${bounds.minValue}..${bounds.maxValue}")
        controlProbe("window area=$seat", {
            request<Int>("window probe before") { ok, fail -> sdk().getWindow().getCurrentWindowPosition(area, ok, fail) }
        }, { before ->
            require(before in bounds.minValue..bounds.maxValue) { "Window before=$before outside capability" }
            if (before < bounds.maxValue) before + 1 else (before - 1).coerceAtLeast(bounds.minValue)
        }, { window(it, seat) }, guard)
    }

    private fun writable(config: CapabilityConfig) {
        require(config.accessType == AccessType.READ_WRITE) { "Read/write required for verified control: ${config.accessType}" }
    }

    private suspend fun <T> verify(name: String, expected: T, read: suspend () -> T, matches: (T, T) -> Boolean): T {
        var lastRead: Any? = null
        return withTimeoutOrNull(5_000L) {
            var actual = read().also { lastRead = it }
            while (!matches(actual, expected)) {
                delay(250)
                actual = read().also { lastRead = it }
            }
            actual
        } ?: error("$name readback mismatch after 5 seconds: requested=$expected actual=$lastRead; earlier actions may already have applied")
    }
}

/** A timeout or late/duplicate SDK callback must never hang a controller or resume it twice. */
internal suspend fun <T : Any> request(
    name: String,
    register: ((T?) -> Unit, (Exception) -> Unit) -> Unit,
): T = (withTimeoutOrNull(5_000L) {
    suspendCancellableCoroutine<Result<T>> { continuation ->
        val completed = AtomicBoolean(false)
        continuation.invokeOnCancellation { completed.set(true) }
        fun finish(value: Result<T>) {
            if (completed.compareAndSet(false, true)) continuation.resume(value)
        }
        try {
            register({ value -> finish(if (value == null) Result.failure(IllegalStateException("$name returned null")) else Result.success(value)) },
                { finish(Result.failure(it)) })
        } catch (e: Exception) {
            finish(Result.failure(e))
        }
    }
} ?: Result.failure(TimeoutException("$name: no SDK response within 5 seconds"))).getOrThrow()

internal suspend fun <T> controlResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
