package dev.tischbein.pulse.voice

import dev.tischbein.pulse.coaching.CoachingEvent
import dev.tischbein.pulse.coaching.EventType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records every platform call in order, and lets the test decide when utterances finish. */
private class FakeSpeech(
    var focusGranted: Boolean = true,
) : SpeechPlatform {
    val calls = mutableListOf<String>()
    val spoken = mutableListOf<String>()
    var listener: SpeechPlatform.Listener? = null
    private var currentId: String? = null
    var speaking = false

    override fun start(listener: SpeechPlatform.Listener) {
        this.listener = listener
    }

    override fun speak(text: String, utteranceId: String): Boolean {
        check(!speaking) { "overlapping speech: $text" }
        calls += "speak:$text"
        spoken += text
        currentId = utteranceId
        speaking = true
        return true
    }

    override fun requestAudioFocus(): Boolean {
        calls += "focus"
        return focusGranted
    }

    override fun abandonAudioFocus() {
        calls += "release"
    }

    override fun shutdown() {
        calls += "shutdown"
    }

    fun ready(problem: String? = null) = listener!!.onReady(problem)

    /** The current utterance finished playing. */
    fun finish() {
        speaking = false
        listener!!.onUtteranceDone(currentId!!)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCoachTest {
    private fun TestScope.coach(fake: FakeSpeech) = VoiceCoach(fake, { testScheduler.currentTime }, backgroundScope)

    private fun cue(text: String, type: EventType = EventType.PHASE_START) = CoachingEvent(0, type, text)

    @Test
    fun `cues queued during TTS start are spoken in order once ready, one at a time`() =
        runTest {
            val fake = FakeSpeech()
            val voice = coach(fake)
            voice.say(cue("Warmup."))
            voice.say(cue("Sensor signal lost.", EventType.SENSOR_LOST))
            assertTrue(fake.calls.isEmpty())

            fake.ready()
            assertEquals(listOf("focus", "speak:Warmup."), fake.calls)
            fake.finish()
            assertEquals(listOf("Warmup.", "Sensor signal lost."), fake.spoken)
            assertEquals(VoiceStatus.Ready, voice.status.value)
        }

    @Test
    fun `a burst holds focus once and releases it after the last utterance`() =
        runTest {
            val fake = FakeSpeech()
            val voice = coach(fake)
            fake.ready()
            voice.say(cue("3", EventType.COUNTDOWN))
            advanceTimeBy(500)
            fake.finish()
            // The next number comes 1 s after the first; focus is still held.
            advanceTimeBy(500)
            voice.say(cue("2", EventType.COUNTDOWN))
            advanceTimeBy(500)
            fake.finish()
            advanceTimeBy(500)
            voice.say(cue("1", EventType.COUNTDOWN))
            fake.finish()
            assertEquals(listOf("focus", "speak:3", "speak:2", "speak:1"), fake.calls)

            advanceTimeBy(VoiceConstants.FOCUS_HOLD_MS + 1)
            assertEquals("release", fake.calls.last())
            assertEquals(1, fake.calls.count { it == "release" })
        }

    @Test
    fun `focus is requested again for a cue after the hold expired`() =
        runTest {
            val fake = FakeSpeech()
            val voice = coach(fake)
            fake.ready()
            voice.say(cue("Recover."))
            fake.finish()
            advanceTimeBy(VoiceConstants.FOCUS_HOLD_MS + 1)
            voice.say(cue("Recovery: down 30 beats.", EventType.HRR60))
            assertEquals(listOf("focus", "speak:Recover.", "release", "focus", "speak:Recovery: down 30 beats."), fake.calls)
        }

    @Test
    fun `a cue that waited too long is dropped, the summary and HRmax message never`() =
        runTest {
            val fake = FakeSpeech()
            val voice = coach(fake)
            fake.ready()
            voice.say(cue("Long sentence."))
            voice.say(cue("Above zone, ease off.", EventType.CORRECTION_HIGH))
            voice.say(cue("2", EventType.COUNTDOWN))
            voice.say(cue("Session complete.", EventType.SESSION_SUMMARY))
            voice.say(cue("New max heart rate recorded: 195.", EventType.HR_MAX_LEARNED))
            advanceTimeBy(VoiceConstants.STALE_CUE_MS + 1_000) // TTS busy for a long time
            fake.finish()
            fake.finish()
            assertEquals(listOf("Long sentence.", "Session complete.", "New max heart rate recorded: 195."), fake.spoken)
        }

    @Test
    fun `countdown numbers go stale sooner than other cues`() =
        runTest {
            val fake = FakeSpeech()
            val voice = coach(fake)
            fake.ready()
            voice.say(cue("Recover."))
            voice.say(cue("1", EventType.COUNTDOWN))
            voice.say(cue("Above zone, ease off.", EventType.CORRECTION_HIGH))
            advanceTimeBy(VoiceConstants.STALE_COUNTDOWN_MS + 500)
            fake.finish()
            assertEquals(listOf("Recover.", "Above zone, ease off."), fake.spoken)
        }

    @Test
    fun `TTS unavailable keeps the session silent and reports why`() =
        runTest {
            val fake = FakeSpeech()
            val voice = coach(fake)
            voice.say(cue("Warmup."))
            fake.ready("No English text-to-speech voice installed")
            voice.say(cue("Interval 1 of 4. Go."))
            assertEquals(VoiceStatus.Unavailable("No English text-to-speech voice installed"), voice.status.value)
            assertTrue(fake.calls.isEmpty())
            voice.awaitIdle() // returns at once: nothing will ever be said
        }

    @Test
    fun `refused audio focus drops the cue instead of talking over a call`() =
        runTest {
            val fake = FakeSpeech(focusGranted = false)
            val voice = coach(fake)
            fake.ready()
            voice.say(cue("Above zone, ease off.", EventType.CORRECTION_HIGH))
            assertTrue(fake.spoken.isEmpty())
            fake.focusGranted = true
            voice.say(cue("Below zone, pick it up.", EventType.CORRECTION_LOW))
            assertEquals(listOf("Below zone, pick it up."), fake.spoken)
        }

    @Test
    fun `an utterance that never finishes does not block the queue forever`() =
        runTest {
            val fake = FakeSpeech()
            val voice = coach(fake)
            fake.ready()
            voice.say(cue("Session complete.", EventType.SESSION_SUMMARY))
            voice.say(cue("New max heart rate recorded: 195.", EventType.HR_MAX_LEARNED))
            fake.speaking = false // the engine went silent without reporting done
            advanceTimeBy(VoiceConstants.UTTERANCE_TIMEOUT_MS + 1)
            assertEquals(listOf("Session complete.", "New max heart rate recorded: 195."), fake.spoken)
        }

    @Test
    fun `awaitIdle waits for the last utterance and the focus release`() =
        runTest {
            val fake = FakeSpeech()
            val voice = coach(fake)
            fake.ready()
            voice.say(cue("Session complete.", EventType.SESSION_SUMMARY))
            var idle = false
            launch {
                voice.awaitIdle()
                idle = true
            }
            runCurrent()
            assertFalse(idle)
            advanceTimeBy(3_000)
            fake.finish()
            runCurrent()
            assertFalse(idle) // focus still held for a possible follow-up
            advanceTimeBy(VoiceConstants.FOCUS_HOLD_MS + 1)
            assertTrue(idle)
            assertEquals("release", fake.calls.last())
        }

    @Test
    fun `shutdown releases focus and the engine and ignores late callbacks`() =
        runTest {
            val fake = FakeSpeech()
            val voice = coach(fake)
            fake.ready()
            voice.say(cue("Warmup."))
            voice.say(cue("Interval 1 of 4. Go."))
            voice.shutdown()
            voice.shutdown()
            fake.finish() // late callback from the engine
            voice.say(cue("Recover."))
            assertEquals(listOf("focus", "speak:Warmup.", "release", "shutdown"), fake.calls)
        }

    @Test
    fun `stale rule`() {
        assertFalse(VoiceCoach.isStale(EventType.CORRECTION_LOW, VoiceConstants.STALE_CUE_MS))
        assertTrue(VoiceCoach.isStale(EventType.CORRECTION_LOW, VoiceConstants.STALE_CUE_MS + 1))
        assertTrue(VoiceCoach.isStale(EventType.COUNTDOWN, VoiceConstants.STALE_COUNTDOWN_MS + 1))
        assertFalse(VoiceCoach.isStale(EventType.SESSION_SUMMARY, Long.MAX_VALUE))
        assertFalse(VoiceCoach.isStale(EventType.HR_MAX_LEARNED, Long.MAX_VALUE))
    }
}
