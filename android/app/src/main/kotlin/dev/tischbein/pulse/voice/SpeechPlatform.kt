package dev.tischbein.pulse.voice

/**
 * The Android bits the [VoiceCoach] needs, behind an interface so the queueing and
 * audio-focus logic can be unit-tested with a fake. The real one is [AndroidSpeechPlatform].
 *
 * All calls are made on the main thread, and the implementation must call the [Listener]
 * on the main thread too.
 */
interface SpeechPlatform {
    interface Listener {
        /** Initialisation finished: [problem] is null when English speech works, else a short reason. */
        fun onReady(problem: String?)

        /** The utterance finished, failed or was stopped. Called exactly once per successful [speak]. */
        fun onUtteranceDone(utteranceId: String)
    }

    /** Starts initialisation; [Listener.onReady] follows. */
    fun start(listener: Listener)

    /** Starts speaking [text]. Returns false if the engine refused it (no [Listener.onUtteranceDone] follows then). */
    fun speak(text: String, utteranceId: String): Boolean

    /** Requests transient audio focus that lets other apps duck. Returns true if granted. */
    fun requestAudioFocus(): Boolean

    fun abandonAudioFocus()

    /** Stops speaking and frees the TTS engine. No callbacks afterwards. */
    fun shutdown()
}
