package ai.pleos.playground.sample.vehicle.intervention

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTestControllerTest {
    @Test fun `recognizer startup failure is not saved as user silence`() = runTest {
        val voice = FakeVoice().apply { failure = VoiceFailure(VoiceFailureCode.RECOGNIZER, "not ready") }
        val records = mutableListOf<VoiceTestRecord>()
        val c = VoiceTestController(this, voice, { records += it; 1L })
        c.setAllowed(true); c.start(VoiceTestMode.RESPONSE); runCurrent()
        assertEquals(VoiceTestOutcome.ERROR, records.single().result.outcome)
        assertEquals(VoiceFailureCode.RECOGNIZER, records.single().result.error)
    }

    @Test fun `SDK transcript is accepted without inventing response milliseconds`() = runTest {
        val voice = FakeVoice().apply {
            answer = VoiceAnswer("괜찮아요", null, responseTiming = "UNMEASURED_PLEOS_EPD_BYTE_OFFSETS")
        }
        val records = mutableListOf<VoiceTestRecord>()
        val c = VoiceTestController(this, voice, { records += it; 1L })
        c.setAllowed(true); c.start(VoiceTestMode.RESPONSE); runCurrent()
        assertEquals(VoiceTestOutcome.ANSWERED, records.single().result.outcome)
        assertNull(records.single().result.answer!!.responseMs)
        assertEquals("UNMEASURED_PLEOS_EPD_BYTE_OFFSETS", records.single().result.answer!!.responseTiming)
    }

    private class FakeVoice : VoiceConversation {
        var playCalls = 0
        var askCalls = 0
        var failure: VoiceFailure? = null
        var wait: CompletableDeferred<Unit>? = null
        var answer = VoiceAnswer("괜찮아요", 800, readyLatencyMs = 200)
        override fun capabilities() = VoiceCapabilities(false, true, listOf("test.tts"))
        override suspend fun play(prompt: String, onPhase: (VoicePhase) -> Unit): Long {
            playCalls++; onPhase(VoicePhase.SPEAKING); wait?.await(); failure?.let { throw it }; return 1_000
        }
        override suspend fun ask(prompt: String, onPhase: (VoicePhase) -> Unit): VoiceAnswer {
            askCalls++; onPhase(VoicePhase.WAITING); wait?.await(); failure?.let { throw it }
            onPhase(VoicePhase.LISTENING); return answer
        }
    }

    @Test fun `parked gate must be satisfied before any audio request`() = runTest {
        val voice = FakeVoice()
        val c = VoiceTestController(this, voice, { error("must not save") })
        assertFalse(c.start(VoiceTestMode.RESPONSE))
        assertEquals(0, voice.askCalls)
    }

    @Test fun `playback only never asks recognizer and does not require microphone`() = runTest {
        val voice = FakeVoice()
        val saved = mutableListOf<VoiceTestRecord>()
        val c = VoiceTestController(this, voice, { saved += it; 7L })
        c.setAllowed(true); c.start(VoiceTestMode.PLAYBACK); runCurrent()
        assertEquals(1, voice.playCalls); assertEquals(0, voice.askCalls)
        assertEquals(VoiceTestOutcome.PLAYBACK_COMPLETED, saved.single().result.outcome)
        assertEquals(7L, c.state.value.savedId)
        assertNull(saved.single().result.answer)
    }

    @Test fun `recognized transcript and real timing are saved with unique test ID`() = runTest {
        val voice = FakeVoice()
        val records = mutableListOf<VoiceTestRecord>()
        var starts = 0; var finishes = 0
        val c = VoiceTestController(this, voice, { records += it; 1L }, { starts++ }, { finishes++ })
        c.setAllowed(true); c.start(VoiceTestMode.RESPONSE); runCurrent()
        assertEquals("괜찮아요", records.single().result.answer!!.text)
        assertEquals(800L, records.single().result.answer!!.responseMs)
        assertTrue(records.single().id.startsWith("voice-test-"))
        assertFalse(c.state.value.running)
        assertEquals(1, starts); assertEquals(1, finishes)
    }

    @Test fun `double tap cannot create overlapping listeners`() = runTest {
        val voice = FakeVoice().apply { wait = CompletableDeferred() }
        val c = VoiceTestController(this, voice, { 1L })
        c.setAllowed(true); assertTrue(c.start(VoiceTestMode.RESPONSE)); runCurrent()
        assertFalse(c.start(VoiceTestMode.CONSENT)); assertEquals(1, voice.askCalls)
        c.cancel(); runCurrent()
    }

    @Test fun `leaving parked state cancels audio and saves exactly one cancelled result`() = runTest {
        val voice = FakeVoice().apply { wait = CompletableDeferred() }
        val records = mutableListOf<VoiceTestRecord>()
        var restored = false
        val c = VoiceTestController(this, voice, { records += it; 1L }, onFinished = { restored = true })
        c.setAllowed(true); c.start(VoiceTestMode.RESPONSE); runCurrent()
        c.setAllowed(false); runCurrent()
        assertEquals(VoiceTestOutcome.CANCELLED, records.single().result.outcome)
        assertFalse(c.state.value.running); assertTrue(restored)
        assertFalse(c.start(VoiceTestMode.RESPONSE))
    }

    @Test fun `permission and service failures are saved distinctly without fake response time`() = runTest {
        for ((code, expected) in listOf(
            VoiceFailureCode.PERMISSION to VoiceTestOutcome.PERMISSION_REQUIRED,
            VoiceFailureCode.NO_RECOGNIZER to VoiceTestOutcome.UNAVAILABLE,
            VoiceFailureCode.TTS_LANGUAGE to VoiceTestOutcome.UNAVAILABLE,
            VoiceFailureCode.TTS_TIMEOUT to VoiceTestOutcome.ERROR,
            VoiceFailureCode.RECOGNIZER to VoiceTestOutcome.ERROR,
        )) {
            val voice = FakeVoice().apply { failure = VoiceFailure(code, "test failure", 9) }
            val saved = mutableListOf<VoiceTestRecord>()
            val c = VoiceTestController(this, voice, { saved += it; 1L })
            c.setAllowed(true); c.start(VoiceTestMode.RESPONSE); runCurrent()
            assertEquals(expected, saved.single().result.outcome)
            assertEquals(code, saved.single().result.error)
            assertEquals(9, saved.single().result.platformCode)
            assertNull(saved.single().result.answer)
        }
    }

    @Test fun `timeout and no match cannot be reported as answered`() {
        assertEquals(VoiceTestOutcome.NO_RESPONSE, VoiceTestController.evaluate(VoiceTestMode.RESPONSE, VoiceAnswer(null, null, VoiceEnd.TIMEOUT)).outcome)
        assertEquals(VoiceTestOutcome.NO_MATCH, VoiceTestController.evaluate(VoiceTestMode.RESPONSE, VoiceAnswer(null, 500, VoiceEnd.NO_MATCH)).outcome)
        assertEquals(VoiceTestOutcome.NO_MATCH, VoiceTestController.evaluate(VoiceTestMode.RESPONSE, VoiceAnswer("", null)).outcome)
    }

    @Test fun `only explicit consent yields acceptance including provider punctuation`() {
        for (text in listOf("네", "안내해 줘.", "안내해 주세요！")) {
            assertEquals(VoiceTestOutcome.ACCEPTED, VoiceTestController.evaluate(VoiceTestMode.CONSENT, VoiceAnswer(text, 500)).outcome)
        }
        assertEquals(VoiceTestOutcome.DECLINED, VoiceTestController.evaluate(VoiceTestMode.CONSENT, VoiceAnswer("아니요", 600)).outcome)
        assertEquals(VoiceTestOutcome.UNKNOWN_ANSWER, VoiceTestController.evaluate(VoiceTestMode.CONSENT, VoiceAnswer("모르겠어요", null)).outcome)
    }

    @Test fun `database failure preserves recognition result and unblocks next trial`() = runTest {
        val voice = FakeVoice()
        val c = VoiceTestController(this, voice, { error("disk full") })
        c.setAllowed(true); c.start(VoiceTestMode.RESPONSE); runCurrent()
        assertEquals(VoiceTestOutcome.ANSWERED, c.state.value.result!!.outcome)
        assertTrue(c.state.value.saveError!!.contains("disk full"))
        assertFalse(c.state.value.running)
        voice.answer = VoiceAnswer(null, null, VoiceEnd.TIMEOUT)
        c.start(VoiceTestMode.RESPONSE); runCurrent()
        assertEquals(VoiceTestOutcome.NO_RESPONSE, c.state.value.result!!.outcome)
        assertNull(c.state.value.result!!.answer!!.responseMs)
    }

    @Test fun `cancellation during saving cannot create duplicate history rows`() = runTest {
        var saves = 0
        val saving = CompletableDeferred<Unit>()
        val c = VoiceTestController(this, FakeVoice(), { saves++; saving.await(); 1L })
        c.setAllowed(true); c.start(VoiceTestMode.RESPONSE); runCurrent()
        assertEquals(VoicePhase.SAVING, c.state.value.phase)
        c.cancel(); saving.complete(Unit); runCurrent()
        assertEquals(1, saves)
        assertEquals(VoiceTestOutcome.ANSWERED, c.state.value.result!!.outcome)
        assertFalse(c.state.value.running)
    }
}
