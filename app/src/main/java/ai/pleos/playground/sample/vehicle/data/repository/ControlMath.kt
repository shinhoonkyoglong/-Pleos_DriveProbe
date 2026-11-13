package ai.pleos.playground.sample.vehicle.data.repository

import kotlin.math.roundToInt

/** Percentages belong to the app; raw positions belong to the vehicle capability. */
internal object ControlMath {
    fun windowPosition(percent: Int, min: Int, max: Int): Int {
        require(percent in 0..100) { "Window percentage must be 0..100" }
        require(min <= max) { "Invalid window capability range: $min..$max" }
        return (min.toDouble() + (max.toDouble() - min) * percent / 100.0).roundToInt()
    }

    fun requireRange(value: Float, min: Float, max: Float) {
        require(value.isFinite() && min.isFinite() && max.isFinite() && min <= max && value in min..max) {
            "Value $value outside supported range $min..$max"
        }
    }
}
