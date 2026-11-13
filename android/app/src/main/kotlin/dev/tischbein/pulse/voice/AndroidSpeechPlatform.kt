package dev.tischbein.pulse.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * [SpeechPlatform] on Android: [TextToSpeech] plus [AudioManager] focus.
 *
 * AUDIO ATTRIBUTES (why navigation guidance + speech):
 * - USAGE_ASSISTANCE_NAVIGATION_GUIDANCE is what turn-by-turn navigation uses: short spoken
 *   prompts over music. Android routes it like media (same output, e.g. the Bluetooth
 *   headphones, and on most phones the media volume), and music apps are used to being
 *   ducked for it.
 * - With AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, Android 8+ ducks other players itself
 *   ("automatic ducking"): the music app keeps playing at lower volume and does not even
 *   need to cooperate. Exception: players that declare they would rather pause (spoken-word
 *   apps such as podcasts/audiobooks) are paused for the duration instead. Our own content
 *   type is SPEECH so the system knows this is talk over music, not a second music player.
 * - USAGE_MEDIA would not duck anything (two media players just mix), and USAGE_ALARM /
 *   NOTIFICATION follow other volume sliders and can be silenced by Do Not Disturb.
 *
 * Callbacks of TextToSpeech come on a binder thread; they are posted to the main thread
 * because [VoiceCoach] is main-thread only.
 */
class AndroidSpeechPlatform(context: Context) : SpeechPlatform {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    private val focusRequest =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
    private var tts: TextToSpeech? = null
    private var listener: SpeechPlatform.Listener? = null

    override fun start(listener: SpeechPlatform.Listener) {
        this.listener = listener
        // The init callback may run before the constructor returns; post so `tts` is set.
        tts = TextToSpeech(appContext) { status -> main.post { onInit(status) } }
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            listener?.onReady("Text-to-speech is not available on this phone")
            return
        }
        // US English first, then any English the engine has (e.g. only en-GB installed).
        val languageOk = listOf(Locale.US, Locale.UK, Locale.ENGLISH).any { engine.setLanguage(it) >= TextToSpeech.LANG_AVAILABLE }
        if (!languageOk) {
            listener?.onReady("No English text-to-speech voice installed")
            return
        }
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit

                override fun onDone(utteranceId: String) = done(utteranceId)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = done(utteranceId)

                override fun onError(utteranceId: String, errorCode: Int) = done(utteranceId)

                override fun onStop(utteranceId: String, interrupted: Boolean) = done(utteranceId)
            },
        )
        listener?.onReady(null)
    }

    private fun done(utteranceId: String) {
        main.post { listener?.onUtteranceDone(utteranceId) }
    }

    override fun speak(text: String, utteranceId: String): Boolean =
        tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), utteranceId) == TextToSpeech.SUCCESS

    override fun requestAudioFocus(): Boolean = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    override fun shutdown() {
        listener = null
        main.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
