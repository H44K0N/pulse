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
import dev.tischbein.pulse.PulseApplication
import dev.tischbein.pulse.coaching.CoachingEngine
import dev.tischbein.pulse.coaching.CoachingRunner
import dev.tischbein.pulse.coaching.CoachingState
import dev.tischbein.pulse.coaching.MS_PER_HOUR
import dev.tischbein.pulse.coaching.MS_PER_SECOND
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.coaching.SessionResult
import dev.tischbein.pulse.coaching.SessionSetup
import dev.tischbein.pulse.data.db.DeviceKind
import dev.tischbein.pulse.data.db.SessionEntity
import dev.tischbein.pulse.data.db.SessionStatus
import dev.tischbein.pulse.data.export.SettingsSnapshot
import dev.tischbein.pulse.sensor.HeartRateSource
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
 * One session at a time. The service stops itself when the session ends.
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
                start(mode)
            }
            ACTION_PAUSE -> active?.pause()
            ACTION_RESUME -> active?.resume()
            ACTION_END -> active?.end()
        }
        // A notification button pressed after the session ended would create an idle service.
        if (sessionJob == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun start(mode: SessionMode) {
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
        sessionJob = scope.launch { runSession(mode) }
    }

    private suspend fun runSession(mode: SessionMode) {
        val settings = container.settings.current()
        val config = settings.toCoachingConfig()
        val setup = SessionSetup(mode, settings.hrMax, settings.easyTargetDurationMs.takeIf { mode == SessionMode.EASY })
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
        val active = ActiveSession(sessionId, mode, sourceLabel, engine.state, runner)
        this.active = active
        Log.i(TAG, "session $sessionId started: $mode, hrMax=${setup.hrMax}, source=$sourceLabel")

        var result: SessionResult? = null
        try {
            container.sessions.startSession(session)
            container.activeSession.value = active
            result = record(engine, runner, source, recorder, active)
            withContext(NonCancellable) {
                container.sessions.finishSession(sessionId, result)
                result.summary.newHrMax?.let { container.settings.applyLearnedHrMax(it) }
            }
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
            finishService()
        }
    }

    /** Runs the engine until the session ends, writing to Room every few seconds. */
    private suspend fun record(
        engine: CoachingEngine,
        runner: CoachingRunner,
        source: HeartRateSource,
        recorder: SessionRecorder,
        active: ActiveSession,
    ): SessionResult =
        coroutineScope {
            // UNDISPATCHED: subscribed before the engine starts, so its first cue is not missed.
            val helpers =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        engine.events.collect { event ->
                            // Milestone 3 speaks these; for now they are logged and stored.
                            Log.i(TAG, "cue ${event.type.wireName}: ${event.text}")
                            recorder.onEvent(event)
                        }
                    }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        engine.state.collect { state ->
                            recorder.onState(state)
                            updateNotificationThrottled(active, state)
                        }
                    }
                    launch {
                        while (true) {
                            delay(FLUSH_INTERVAL_MS)
                            recorder.flush()
                        }
                    }
                }
            // connect() is a no-op if the strap is already connected (it usually is).
            source.connect()
            val result = runner.run(source.samples.onEach { recorder.onSample(it) })
            helpers.cancel()
            result
        }

    private var lastNotifiedAtMs = 0L
    private var lastNotifiedKey: Any? = null

    /**
     * Android silently drops notification updates from apps that post too often, and every
     * update costs battery. So: update immediately when the phase or pause state changes,
     * otherwise at most every [NOTIFICATION_INTERVAL_MS].
     */
    private fun updateNotificationThrottled(active: ActiveSession, state: CoachingState) {
        val key = listOf(state.phaseKind, state.intervalNumber, state.paused, state.sensorLost)
        val now = container.clock.nowMs()
        if (key == lastNotifiedKey && now - lastNotifiedAtMs < NOTIFICATION_INTERVAL_MS) return
        lastNotifiedKey = key
        lastNotifiedAtMs = now
        notifications.update(notifications.build(active.mode, state, active.sourceLabel))
    }

    /**
     * Partial wake lock = CPU stays on, screen may turn off. Without it the phone can doze
     * between Bluetooth packets and the engine's 1 s tick (phase changes, "sensor lost" after
     * 10 s of silence, Milestone 3's voice) would run late. The timeout is a safety net: a bug
     * can never keep the phone awake forever.
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock =
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pulse:session").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun finishService() {
        container.activeSession.value = null
        active = null
        recorder = null
        sessionJob = null
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
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.tischbein.pulse.session.START"
        const val ACTION_PAUSE = "dev.tischbein.pulse.session.PAUSE"
        const val ACTION_RESUME = "dev.tischbein.pulse.session.RESUME"
        const val ACTION_END = "dev.tischbein.pulse.session.END"
        private const val EXTRA_MODE = "mode"
        private const val TAG = "Pulse/Session"

        /** How often recorded data is written to Room: the most a crash can lose. */
        const val FLUSH_INTERVAL_MS = 5 * MS_PER_SECOND
        private const val NOTIFICATION_INTERVAL_MS = 5 * MS_PER_SECOND
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * MS_PER_HOUR

        /** Starts a session. Call from the UI (the app must be in the foreground). */
        fun start(context: Context, mode: SessionMode) {
            val intent = Intent(context, SessionService::class.java).setAction(ACTION_START).putExtra(EXTRA_MODE, mode.name)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
