package ai.pleos.playground.sample.vehicle.data.repository

/** Read AUTO after power is verified: enabling HVAC may itself change automatic mode. */
internal suspend fun prepareManualHvac(
    readPower: suspend () -> Boolean,
    enablePower: suspend () -> Unit,
    readAuto: suspend () -> Boolean,
    disableAuto: suspend () -> Unit,
) {
    if (!readPower()) enablePower()
    if (readAuto()) disableAuto()
}
