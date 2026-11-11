package ai.pleos.playground.sample.vehicle.intervention

import ai.pleos.playground.sample.vehicle.condition.core.ConditionState
import ai.pleos.playground.sample.vehicle.condition.core.FatigueLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterventionSpeechActionTest {
    private class Voice : VoiceConversation {
        var calls = 0
        var prompt = ""
        var answer = VoiceAnswer("괜찮아요", 1_200, responseTiming = "PCM_AUDIO_TIMESTAMP_EPD_ESTIMATE",
            captureLatencyMs = 20, epdOnBytes = 37_760, epdOffBytes = 64_000, audioBytes = 80_000)
        var failure: Exception? = null
        override fun capabilities() = VoiceCapabilities(true, true, listOf("pleos"))
        override suspend fun play(prompt: String, onPhase: (VoicePhase) -> Unit) = error("not playback")
        override suspend fun ask(prompt: String, onPhase: (VoicePhase) -> Unit) = error("must use timed path")
        override suspend fun askTimed(prompt: String, onPhase: (VoicePhase) -> Unit): VoiceAnswer {
            calls++; this.prompt = prompt; failure?.let { throw it }
            onPhase(VoicePhase.WAITING); return answer
        }
    }

    @Test fun `caution uses measured path and preserves all timing provenance`() = runTest {
        val voice = Voice()
        val result = InterventionSpeechAction(voice).execute(FatigueLevel.CAUTION)
        assertEquals(InterventionOutcome.ANSWERED, result.outcome)
        assertEquals(1, voice.calls)
        assertEquals(voice.answer, result.voiceAnswer)
        assertEquals(1_200L, result.responseMs)
        assertNull(result.consent); assertNull(result.navigationStatus)
    }

    @Test fun `danger consent is accepted without claiming navigation success`() = runTest {
        for (text in listOf("안내해", "안내해 줘.", "네")) {
            val voice = Voice().apply { answer = answer.copy(text = text) }
            val r = InterventionSpeechAction(voice).execute(FatigueLevel.DANGER)
            assertEquals(InterventionOutcome.ACCEPTED, r.outcome)
            assertEquals(VoiceConsent.ACCEPT, r.consent)
            assertEquals("NOT_CONNECTED", r.navigationStatus)
            assertEquals(voice.answer, r.voiceAnswer)
        }
    }

    @Test fun `negative and unknown consent never request routes`() = runTest {
        for ((text, outcome) in listOf("아니요" to InterventionOutcome.DECLINED,
            "안내하지 마" to InterventionOutcome.DECLINED, "괜찮아요" to InterventionOutcome.DECLINED,
            "모르겠어요" to InterventionOutcome.UNKNOWN_RESPONSE)) {
            val voice = Voice().apply { answer = answer.copy(text = text) }
            val r = InterventionSpeechAction(voice).execute(FatigueLevel.DANGER)
            assertEquals(outcome, r.outcome)
            assertEquals("NOT_REQUESTED", r.navigationStatus)
        }
    }

    @Test fun `timeout and no match outrank a stale transcript`() = runTest {
        for (level in listOf(FatigueLevel.CAUTION, FatigueLevel.DANGER)) {
            for ((end, expected) in listOf(VoiceEnd.TIMEOUT to InterventionOutcome.NO_RESPONSE,
                VoiceEnd.NO_MATCH to InterventionOutcome.NO_MATCH)) {
                val voice = Voice().apply { answer = VoiceAnswer("네", null, end) }
                val r = InterventionSpeechAction(voice).execute(level)
                assertEquals(expected, r.outcome)
                assertNull(r.consent); assertNull(r.navigationStatus); assertNull(r.responseMs)
            }
        }
    }

    @Test fun `recognized text without EPD remains answered with unknown timing`() = runTest {
        val voice = Voice().apply { answer = VoiceAnswer("괜찮아요", null, responseTiming = "UNMEASURED_PCM_EPD") }
        val r = InterventionSpeechAction(voice).execute(FatigueLevel.CAUTION)
        assertEquals(InterventionOutcome.ANSWERED, r.outcome); assertNull(r.responseMs)
        assertEquals("UNMEASURED_PCM_EPD", r.voiceAnswer!!.responseTiming)
    }

    @Test fun `blank text is not a response or consent`() = runTest {
        val voice = Voice().apply { answer = VoiceAnswer(" ", null) }
        for (level in listOf(FatigueLevel.CAUTION, FatigueLevel.DANGER))
            assertEquals(InterventionOutcome.NO_MATCH, InterventionSpeechAction(voice).execute(level).outcome)
    }

    @Test fun `SDK error remains typed and cancellation propagates`() = runTest {
        val voice = Voice().apply { failure = VoiceFailure(VoiceFailureCode.PERMISSION, "denied", 9) }
        val r = InterventionSpeechAction(voice).execute(FatigueLevel.CAUTION)
        assertEquals(InterventionOutcome.UNAVAILABLE, r.outcome)
        assertEquals(VoiceFailureCode.PERMISSION, r.error); assertEquals(9, r.platformCode)
        voice.failure = CancellationException("trip ended")
        try { InterventionSpeechAction(voice).execute(FatigueLevel.DANGER); fail("must cancel") }
        catch (_: CancellationException) { }
    }

    @Test fun `normal and mild never enter speech action`() = runTest {
        val voice = Voice()
        for (level in listOf(FatigueLevel.NORMAL, FatigueLevel.MILD)) {
            try { InterventionSpeechAction(voice).execute(level); fail("invalid stage") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, voice.calls)
    }

    @Test fun `real controller executes speech once and persists original fatigue score and timing`() = runTest {
        val voice = Voice()
        val records = mutableListOf<InterventionRecord>()
        val action = InterventionSpeechAction(voice)
        val c = InterventionController(this, { 0 }, { action.execute(it) }, { records += it })
        val state = ConditionState(FatigueLevel.CAUTION, .55f, false, lastWindowValid = true)
        repeat(20) { c.update(state, true, true, false) }; runCurrent()
        assertEquals(1, voice.calls); assertEquals(.55f, records.single().condition.score)
        assertFalse(records.single().simulation)
        assertEquals(voice.answer, records.single().result.voiceAnswer)
    }

    @Test fun `replay bypasses real speech runner completely`() = runTest {
        val voice = Voice()
        val records = mutableListOf<InterventionRecord>()
        val c = InterventionController(this, { 0 }, { InterventionSpeechAction(voice).execute(it) }, { records += it })
        c.update(ConditionState(FatigueLevel.DANGER, .75f, false, lastWindowValid = true), true, true, true)
        runCurrent()
        assertEquals(0, voice.calls)
        assertEquals(InterventionOutcome.SIMULATED, records.single().result.outcome)
    }
}
