package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.data.model.*
import org.junit.Assert.*
import org.junit.Test

class ParkedSettingsGateTest {
    private val actual = UxRestrictionState(false, 0, UxRestrictionSource.CAR_SERVICE)
    @Test fun onlyKnownStationaryParkWithActualUnrestrictedStateAllowsSetup() {
        assertEquals("", parkedSettingsBlockReason(GearPosition.PARK, 0f, true, actual))
    }
    @Test fun unknownGearOrSpeedCannotEnableSetup() {
        assertTrue(parkedSettingsBlockReason(GearPosition.UNKNOWN, 0f, true, actual).isNotEmpty())
        assertTrue(parkedSettingsBlockReason(GearPosition.PARK, 0f, false, actual).isNotEmpty())
    }
    @Test fun motionIncludingInvalidSpeedBlocksSetup() {
        for (speed in listOf(1f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertTrue(parkedSettingsBlockReason(GearPosition.PARK, speed, true, actual).isNotEmpty())
        assertTrue(parkedSettingsBlockReason(GearPosition.DRIVE, 0f, true, actual).isNotEmpty())
    }
    @Test fun simulatedOrUnavailableRestrictionsCannotUnlockSettings() {
        for (ux in listOf(null, actual.copy(source = UxRestrictionSource.MOCK), actual.copy(source = UxRestrictionSource.UNAVAILABLE)))
            assertTrue(parkedSettingsBlockReason(GearPosition.PARK, 0f, true, ux).isNotEmpty())
    }
    @Test fun actualUxRestrictionOverridesStationaryPark() {
        assertTrue(parkedSettingsBlockReason(GearPosition.PARK, 0f, true, actual.copy(requiresDistractionOptimization = true)).isNotEmpty())
    }
}
