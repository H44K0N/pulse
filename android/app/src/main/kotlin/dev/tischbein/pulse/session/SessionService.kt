package dev.tischbein.pulse.session

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.tischbein.pulse.AppConstants
import dev.tischbein.pulse.PulseApplication
import dev.tischbein.pulse.coaching.CoachingEngine
import dev.tischbein.pulse.coaching.CoachingRunner
import dev.tischbein.pulse.coaching.CoachingState
import dev.tischbein.pulse.coaching.MS_PER_MINUTE
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.coaching.SessionResult
import dev.tischbein.pulse.coaching.SessionSetup
import dev.tischbein.pulse.data.db.DeviceKind
import dev.tischbein.pulse.data.db.SessionEntity
import dev.tischbein.pulse.data.db.SessionStatus
import dev.tischbein.pulse.data.export.SettingsSnapshot
import dev.tischbein.pulse.sensor.HeartRateSource
import dev.tischbein.pulse.voice.AndroidSpeechPlatform
import dev.tischbein.pulse.voice.VoiceCoach
import dev.tischbein.pulse.voice.VoiceConstants
import dev.tischbein.pulse.voice.VoiceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Owns a running session: sensor -> coaching engine -> Room, plus the notification.
 *
 * WHY a foreground service: Android stops or freezes background apps within minutes of the
 * screen going off. A foreground service (with its mandatory notification) is the supported
 * way to keep a workout running with the screen off and the app in the background. It is
 * started from the UI (an app in the foreground may start one; since Android 12 a background
 * app mostly may not), with type "connectedDevice" (see AndroidManifest.xml).
 *
 * One session at a time. The service stops itself when the session ends, after the voice
 * coach has finished saying the summary (at most [VoiceConstants.END_DRAIN_TIMEOUT_MS]).
 *
 * Voice: each session gets its own [VoiceCoach] fed by the engine's events. The voice lives
 * here, not in the UI, because the coach must keep talking with the screen off.
 *
 * Process death: the session's rows stay in Room with status "recording" and are marked
 * "incomplete" on the next app start (SessionRepository.closeAbandonedSessions). The service
 * returns START_NOT_STICKY: if Android kills it, it is not restarted, because the coaching
 * engine's in-memory state cannot be restored and a silently restarted half session would
 * confuse more than help.
 */
class SessionService : Service() {
    private val container get() = (application as PulseApplication).container

    // Main thread: the recorder and runner commands are single-threaded by design.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifications: SessionNotifications

    private var sessionJob: Job? = null
    private var active: ActiveSession? = null
    private var recorder: SessionRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var voice: VoiceCoach? = null

    /** Lets the finished session's voice finish its summary before the service stops. */
    private var drainJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notifications = SessionNotifications(this)
        notifications.ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val mode = intent.getStringExtra(EXTRA_MODE)?.let { SessionMode.valueOf(it) } ?: SessionMode.INTERVAL_4X4
                val easyTargetMin = intent.getIntExtra(EXTRA_EASY_TARGET_MIN, -1).takeIf { it >= 0 }
                start(mode, easyTargetMin)
            }
            ACTION_PAUSE -> active?.pause()
            ACTION_RESUME -> active?.resume()
            ACTION_END -> active?.end()
        }
        // A notification button pressed after the session ended would create an idle service.
        if (sessionJob == null && drainJob?.isActive != true) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun start(
        mode: SessionMode,
        easyTargetMin: Int?,
    ) {
        // startForegroundService() obliges us to call startForeground() within a few seconds,
        // on every start request, or Android crashes the app. So call it first, always.
        try {
            ServiceCompat.startForeground(
                this,
                SessionNotifications.NOTIFICATION_ID,
                notifications.build(mode, active?.state?.value, active?.sourceLabel ?: "sensor"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // SecurityException: BLUETOOTH_CONNECT not granted (the prerequisite of the
            // connectedDevice type on Android 14+). ForegroundServiceStartNotAllowedException:
            // started from the background. The UI prevents both; never crash over it.
            Log.e(TAG, "cannot start foreground service", e)
            stopSelf()
            return
        }
        if (sessionJob != null) return // a session is already running
        acquireWakeLock()
        val previousDrain = drainJob
        sessionJob = scope.launch { runSession(mode, easyTargetMin) }
        // The previous session may still be saying its summary: the new session cuts it
        // short. Cancelled only after sessionJob is set, so the drain does not stop the service.
        previousDrain?.cancel()
    }

    private suspend fun runSession(
        mode: SessionMode,
        easyTargetMin: Int?,
    ) {
        val settings = container.settings.current()
        val config = settings.toCoachingConfig()
        // Easy target: what the start screen chose (0 = open-ended), else the Settings default.
        val easyTargetMs =
            if (easyTargetMin != null) easyTargetMin.takeIf { it > 0 }?.let { it * MS_PER_MINUTE } else settings.easyTargetDurationMs
        val setup = SessionSetup(mode, settings.hrMax, easyTargetMs.takeIf { mode == SessionMode.EASY })
        val engine = CoachingEngine(setup, config)
        val runner = CoachingRunner(engine, container.clock)
        val source = container.sensors.sourceForSession(settings, config, scope)
        val simulated = settings.simulatorEnabled
        val sourceLabel = if (simulated) "Simulator: ${settings.simulatorProfile}" else "Polar H10 ${settings.deviceId ?: "?"}"

        val sessionId = UUID.randomUUID().toString()
        val startClockMs = container.clock.nowMs()
        val session =
            SessionEntity(
                id = sessionId,
                mode = mode.wireName,
                status = SessionStatus.RECORDING,
                startedAtEpochMs = System.currentTimeMillis(),
                endedAtEpochMs = null,
                hrMaxUsed = setup.hrMax,
                deviceKind = if (simulated) DeviceKind.SIMULATOR else DeviceKind.POLAR_H10,
                deviceId = if (simulated) null else settings.deviceId,
                settingsJson = SettingsSnapshot.of(setup, config, settings.age, settings.hrMaxLearned).toJson(),
            )
        val recorder = SessionRecorder(container.sessions, sessionId, startClockMs, container.clock)
        this.recorder = recorder
        val liveHr = LiveHrBuffer(startClockMs)
        // Created first so TTS can initialise while the sensor connects.
        val voice = VoiceCoach(AndroidSpeechPlatform(this), container.clock, scope) { Log.i(TAG, it) }
        this.voice = voice
        val active = ActiveSession(sessionId, mode, setup.easyTargetDurationMs, sourceLabel, config, engine.state, voice.status, liveHr.state, runner)
        this.active = active
        Log.i(TAG, "session $sessionId started: $mode, hrMax=${setup.hrMax}, source=$sourceLabel")

        var result: SessionResult? = null
        try {
            container.sessions.startSession(session)
            container.activeSession.value = active
            result = record(engine, runner, source, recorder, liveHr, active, voice)
            withContext(NonCancellable) {
                container.sessions.finishSession(sessionId, result)
                result.summary.newHrMax?.let { container.settings.applyLearnedHrMax(it) }
            }
            // Summary screen now (read back from Room by id); the voice is meanwhile saying
            // the summary (see finishService).
            container.finishedSessionId.value = sessionId
            notifications.update(notifications.build(mode, engine.state.value, sourceLabel))
            Log.i(TAG, "session $sessionId finished: ${result.summary}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "session $sessionId failed", e)
        } finally {
            withContext(NonCancellable) {
                if (result == null) {
                    // Failed or cancelled: keep what was recorded, marked incomplete.
                    runCatching {
                        recorder.flush()
                        container.sessions.markIncomplete(sessionId)
                    }
                }
            }
            if (simulated) source.disconnect()
            finishService(drainVoice = result != null)
        }
    }

    /** Runs the engine until the session ends, writing to Room every few seconds. */
    private suspend fun record(
        engine: CoachingEngine,
        runner: CoachingRunner,
        source: HeartRateSource,
        recorder: SessionRecorder,
        liveHr: LiveHrBuffer,
        active: ActiveSession,
        voice: VoiceCoach,
    ): SessionResult =
        coroutineScope {
            var eventsHandled = 0
            // UNDISPATCHED: subscribed before the engine starts, so its first cue is not missed.
            val helpers =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        engine.events.collect { event ->
                            Log.i(TAG, "cue ${event.type.wireName}: ${event.text}")
                            recorder.onEvent(event)
                            voice.say(event)
                            eventsHandled++
                        }
                    }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        engine.state.collect { state ->
                            recorder.onState(state)
                            liveHr.onState(state, container.clock.nowMs())
                            updateNotificationThrottled(active, state)
                        }
                    }
                    launch {
                        // "Voice off" must reach the notification even if the state is quiet.
                        voice.status.collect { updateNotificationThrottled(active, engine.state.value) }
                    }
                    launch {
                        while (true) {
                            delay(AppConstants.FLUSH_INTERVAL_MS)
                            recorder.flush()
                        }
                    }
                }
            // connect() is a no-op if the strap is already connected (it usually is).
            source.connect()
            val result =
                runner.run(
                    source.samples.onEach {
                        recorder.onSample(it)
                        liveHr.onSample(it)
                    },
                )
            helpers.cancel()
            // The last cues (summary, HRmax) are emitted in the same step that finishes the
            // engine; if the collector had not got to them yet, speak them from the result so
            // the summary is never lost. (Room gets them from the result anyway.)
            result.events.drop(eventsHandled).forEach { voice.say(it) }
            result
        }

    private var lastNotifiedAtMs = 0L
    private var lastNotifiedKey: Any? = null

    /**
     * Android silently drops notification updates from apps that post too often, and every
     * update costs battery. So: update immediately when the phase or pause state changes,
     * otherwise at most every [AppConstants.NOTIFICATION_INTERVAL_MS].
     */
    private fun updateNotificationThrottled(active: ActiveSession, state: CoachingState) {
        val voiceStatus = active.voice.value
        val key = listOf(state.phaseKind, state.intervalNumber, state.paused, state.sensorLost, state.finished, voiceStatus)
        val now = container.clock.nowMs()
        if (key == lastNotifiedKey && now - lastNotifiedAtMs < AppConstants.NOTIFICATION_INTERVAL_MS) return
        lastNotifiedKey = key
        lastNotifiedAtMs = now
        val voiceProblem = (voiceStatus as? VoiceStatus.Unavailable)?.reason
        notifications.update(notifications.build(active.mode, state, active.sourceLabel, voiceProblem))
    }

    /**
     * Partial wake lock = CPU stays on, screen may turn off. Without it the phone can doze
     * between Bluetooth packets and the engine's 1 s tick (phase changes, "sensor lost" after
     * 10 s of silence, the voice cues) would run late. The timeout is a safety net: a bug
     * can never keep the phone awake forever.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return // still held while the previous session's voice drains
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock =
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pulse:session").apply {
                setReferenceCounted(false)
                acquire(AppConstants.WAKE_LOCK_TIMEOUT_MS)
            }
    }

    /**
     * The session is over: the UI leaves the live screen at once. With [drainVoice] the
     * service (foreground, wake lock) stays up until the voice has said the summary, at most
     * [VoiceConstants.END_DRAIN_TIMEOUT_MS]; stopping right away would cut the summary off,
     * and a backgrounded app without a foreground service may be frozen mid-sentence with
     * the music still ducked.
     */
    private fun finishService(drainVoice: Boolean) {
        container.activeSession.value = null
        active = null
        recorder = null
        sessionJob = null
        val finishedVoice = voice
        voice = null
        drainJob =
            scope.launch {
                try {
                    if (drainVoice && finishedVoice != null) {
                        withTimeoutOrNull(VoiceConstants.END_DRAIN_TIMEOUT_MS) { finishedVoice.awaitIdle() }
                    }
                } finally {
                    finishedVoice?.shutdown()
                    // A new session may have started meanwhile; it owns the service then.
                    if (sessionJob == null) stopService()
                }
            }
    }

    private fun stopService() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Normally the session has finished by now. If Android destroys the service mid-session
        // (rare while in the foreground), save what we have in the app-wide scope, which
        // outlives this service, and mark the session incomplete.
        val running = active
        val pending = recorder
        if (running != null && pending != null) {
            container.appScope.launch {
                runCatching {
                    pending.flush()
                    container.sessions.markIncomplete(running.id)
                }
            }
            container.activeSession.value = null
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        // Frees the TTS engine and audio focus (whatever was still queued is not said).
        voice?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.tischbein.pulse.session.START"
        const val ACTION_PAUSE = "dev.tischbein.pulse.session.PAUSE"
        const val ACTION_RESUME = "dev.tischbein.pulse.session.RESUME"
        const val ACTION_END = "dev.tischbein.pulse.session.END"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_EASY_TARGET_MIN = "easy_target_min"
        private const val TAG = "Pulse/Session"


        /**
         * Starts a session. Call from the UI (the app must be in the foreground).
         * [easyTargetMin]: easy mode target in minutes, 0 = open-ended, null = Settings default.
         */
        fun start(
            context: Context,
            mode: SessionMode,
            easyTargetMin: Int? = null,
        ) {
            val intent = Intent(context, SessionService::class.java).setAction(ACTION_START).putExtra(EXTRA_MODE, mode.name)
            easyTargetMin?.let { intent.putExtra(EXTRA_EASY_TARGET_MIN, it) }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
