package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.DriveProbeApplication
import ai.pleos.playground.sample.vehicle.MainActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Regular app entry, real FGS preparation and read-only setup. No recording or route writes. */
class DrivingAssistAppTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeCanPrepareAndStopRealVoiceHelpAndOpenRestSettings() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DriveProbeApplication
        val module = app.interventions
        assumeTrue(ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        compose.waitUntil(15_000) {
            app.vehicleRepository.let { parkedSettingsBlockReason(it.gear.value, it.vehicleSpeed.value, it.hasSpeedSignal.value, it.uxRestriction.value).isEmpty() }
        }
        assumeTrue(!module.controller.isBusy && !module.voiceTests.state.value.running && !module.dangerNavigationProbe.isBusy && !module.navigationProbeController.isBusy)
        val wasEnabled = module.controller.status.value.enabled
        val voiceWasReady = DrivingVoiceService.ready.value
        val prefs = app.getSharedPreferences("driving_assist_settings", Context.MODE_PRIVATE)
        val hadSetting = prefs.contains("automaticEnabled")
        val settingBefore = prefs.getBoolean("automaticEnabled", true)
        try {
            File(app.cacheDir, "driving-assist-home.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("kd-home-assist").performClick()
            compose.onNodeWithTag("kd-driving-assist").assertIsDisplayed()
            compose.onNodeWithText("DANGER 통합 시험 · 실제 효과").assertDoesNotExist()
            if (!wasEnabled || !voiceWasReady) {
                compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("assist-start").assertIsEnabled() }.isSuccess }
                compose.onNodeWithTag("assist-start").performClick()
            }
            compose.waitUntil(10_000) { DrivingVoiceService.ready.value && module.controller.status.value.enabled }
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("공조·음성 도움 켜짐").assertIsDisplayed() }.isSuccess }
            assertTrue(prefs.getBoolean("automaticEnabled", false))
            File(app.cacheDir, "driving-assist-screen.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("assist-rest-settings").performScrollTo().performClick()
            compose.onNodeWithTag("kd-rest-stop-settings").assertIsDisplayed()
            compose.onNodeWithTag("rest-settings-load").assertIsEnabled()
            compose.onNodeWithTag("rest-settings-load").performClick()
            compose.waitUntil(35_000) { runCatching { compose.onNodeWithTag("rest-settings-load").assertIsEnabled() }.isSuccess }
            compose.onNodeWithText("현재 경로의 휴게소를 확인해 주세요").assertDoesNotExist()
            compose.onNodeWithTag("rest-settings-save").assertDoesNotExist()
            compose.onNodeWithText("실제 추가·복원 시험").assertDoesNotExist()
            File(app.cacheDir, "rest-stop-settings-screen.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithText("← 주행 도움").performClick()
            compose.onNodeWithTag("assist-stop").performScrollTo().performClick()
            compose.waitUntil(10_000) { !DrivingVoiceService.ready.value && !module.controller.status.value.enabled }
            assertFalse(prefs.getBoolean("automaticEnabled", true))
            compose.onNodeWithText("← 홈").performScrollTo().performClick()
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("kd-home-assist").assertTextContains("주행 도움 꺼짐") }.isSuccess }
        } finally {
            compose.runOnUiThread {
                module.setAutomaticEnabled(wasEnabled)
                if (voiceWasReady) DrivingVoiceService.prepare(compose.activity) else DrivingVoiceService.stop(compose.activity)
                if (hadSetting) prefs.edit().putBoolean("automaticEnabled", settingBefore).commit()
                else prefs.edit().remove("automaticEnabled").commit()
            }
        }
    }
}
