package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.MainActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** UI access and initial explicit-effect gate only. Does not start audio or change any route. */
class DangerProbeEntryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun developerToolsExposeExplicitRealEffectTestWithStartInitiallyBlocked() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("개발자 도구").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("개발자 도구").performClick()
        compose.onNodeWithText("DANGER 통합").performClick()
        compose.onNodeWithText("DANGER 통합 시험 · 실제 효과").assertIsDisplayed()
        compose.onNodeWithText("실제 DANGER 음성·내비 통합 시험 시작").assertIsNotEnabled()
        compose.onNodeWithText("통합 시험 취소").assertIsNotEnabled()
    }
}
