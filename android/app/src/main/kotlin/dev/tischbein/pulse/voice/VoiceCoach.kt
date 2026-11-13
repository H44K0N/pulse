package dev.tischbein.pulse.voice

import dev.tischbein.pulse.coaching.CoachingEvent
import dev.tischbein.pulse.coaching.EventType
import dev.tischbein.pulse.time.MillisClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Whether the coach can talk. */
sealed interface VoiceStatus {
    data object Starting : VoiceStatus

    data object Ready : VoiceStatus

    /** TTS failed to start or has no English voice. The session runs on silently. */
    data class Unavailable(val reason: String) : VoiceStatus
}

/**
 * Speaks the coaching engine's cues, one at a time, with music ducked while it talks.
 *
 * Rules:
 * - Queue: cues are spoken strictly one after another; the next sentence starts only when
 *   the TTS engine reports the previous one finished. Bursts ("3", "2", "1", or recap +
 *   midway) therefore never overlap or cut each other off.
 * - Audio focus: requested (transient, may duck) before the first sentence of a burst, kept
 *   while more sentences follow, released [VoiceConstants.FOCUS_HOLD_MS] after the last one.
 *   If focus is refused (e.g. during a phone call) the cue is dropped: talking over a call
 *   would be worse than missing a cue (every cue is still stored with the session).
 * - Stale cues: a cue that waited longer than [VoiceConstants.STALE_CUE_MS] is dropped
 *   instead of being said late, except the end-of-session summary and HRmax message.
 * - Cues that arrive while TTS is still starting are queued (the first cue of a session
 *   comes immediately, before TTS is ready). If TTS turns out to be unavailable, the queue is
 *   cleared and further cues are ignored: the session must never depend on the voice.
 *
 * Main thread only (the session service's scope). [scope] runs the focus-hold and watchdog
 * timers; [clock] decides staleness.
 */
class VoiceCoach(
    private val platform: SpeechPlatform,
    private val clock: MillisClock,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : SpeechPlatform.Listener {
    private class Cue(val type: EventType, val text: String, val receivedAtMs: Long)

    private val queue = ArrayDeque<Cue>()
    private var speakingId: String? = null
    private var nextId = 0
    private var hasFocus = false
    private var focusReleaseJob: Job? = null
    private var watchdogJob: Job? = null
    private var shutDown = false

    private val statusFlow = MutableStateFlow<VoiceStatus>(VoiceStatus.Starting)
    val status: StateFlow<VoiceStatus> = statusFlow.asStateFlow()

    /** True when nothing is queued, nothing is being said and audio focus is released. */
    private val idle = MutableStateFlow(true)

    init {
        platform.start(this)
    }

    /** Queue [event] to be spoken. */
    fun say(event: CoachingEvent) {
        if (shutDown || statusFlow.value is VoiceStatus.Unavailable) return
        queue.addLast(Cue(event.type, event.text, clock.nowMs()))
        idle.value = false
        speakNext()
    }

    /** Suspends until everything queued has been said (or dropped) and focus is released. */
    suspend fun awaitIdle() {
        idle.first { it }
    }

    /** Stops talking, releases audio focus and the TTS engine. Safe to call twice. */
    fun shutdown() {
        if (shutDown) return
        shutDown = true
        queue.clear()
        focusReleaseJob?.cancel()
        watchdogJob?.cancel()
        speakingId = null
        releaseFocus()
        platform.shutdown()
        idle.value = true
    }

    override fun onReady(problem: String?) {
        if (shutDown) return
        if (problem != null) {
            log("voice unavailable: $problem")
            statusFlow.value = VoiceStatus.Unavailable(problem)
            queue.clear()
            idle.value = true
            return
        }
        statusFlow.value = VoiceStatus.Ready
        speakNext()
    }

    override fun onUtteranceDone(utteranceId: String) {
        if (shutDown || utteranceId != speakingId) return
        speakingId = null
        watchdogJob?.cancel()
        speakNext()
    }

    private fun speakNext() {
        if (shutDown || statusFlow.value != VoiceStatus.Ready || speakingId != null) return
        while (true) {
            val cue = queue.removeFirstOrNull()
            if (cue == null) {
                scheduleFocusRelease()
                return
            }
            val lateMs = clock.nowMs() - cue.receivedAtMs
            if (isStale(cue.type, lateMs)) {
                log("dropped stale cue (${lateMs} ms late): ${cue.text}")
                continue
            }
            focusReleaseJob?.cancel()
            if (!hasFocus) {
                if (!platform.requestAudioFocus()) {
                    log("audio focus refused, cue dropped: ${cue.text}")
                    continue
                }
                hasFocus = true
            }
            val id = "cue-${nextId++}"
            if (!platform.speak(cue.text, id)) {
                log("TTS refused cue: ${cue.text}")
                continue
            }
            speakingId = id
            watchdogJob =
                scope.launch {
                    delay(VoiceConstants.UTTERANCE_TIMEOUT_MS)
                    log("utterance $id never finished; moving on")
                    onUtteranceDone(id)
                }
            return
        }
    }

    private fun scheduleFocusRelease() {
        if (!hasFocus) {
            idle.value = true
            return
        }
        focusReleaseJob?.cancel()
        focusReleaseJob =
            scope.launch {
                delay(VoiceConstants.FOCUS_HOLD_MS)
                releaseFocus()
                idle.value = true
            }
    }

    private fun releaseFocus() {
        if (hasFocus) {
            hasFocus = false
            platform.abandonAudioFocus()
        }
    }

    companion object {
        /** The stale cue rule (see [VoiceConstants.STALE_CUE_MS]). */
        fun isStale(type: EventType, lateMs: Long): Boolean =
            when (type) {
                in VoiceConstants.NEVER_STALE -> false
                EventType.COUNTDOWN -> lateMs > VoiceConstants.STALE_COUNTDOWN_MS
                else -> lateMs > VoiceConstants.STALE_CUE_MS
            }
    }
}
