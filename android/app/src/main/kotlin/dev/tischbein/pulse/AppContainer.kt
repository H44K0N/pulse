package dev.tischbein.pulse

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import dev.tischbein.pulse.data.SessionRepository
import dev.tischbein.pulse.data.db.PulseDatabase
import dev.tischbein.pulse.data.settings.SettingsRepository
import dev.tischbein.pulse.sensor.SensorController
import dev.tischbein.pulse.session.ActiveSession
import dev.tischbein.pulse.time.ElapsedRealtimeClock
import dev.tischbein.pulse.time.MillisClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// One DataStore per file per process: must be a top-level singleton (DataStore's rule).
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Manual dependency injection: every long-lived object of the app, created once in
 * [PulseApplication] and reached from activities/services via `(application as
 * PulseApplication).container`. No DI framework: this file IS the object graph.
 */
class AppContainer(context: Context) {
    /** Lives as long as the process. Main thread; switch dispatcher for heavy work. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val clock: MillisClock = ElapsedRealtimeClock

    private val database = PulseDatabase.open(context)
    val sessions = SessionRepository(database.sessionDao())
    val settings = SettingsRepository(context.settingsDataStore)
    val sensors = SensorController(context, clock, settings, appScope)

    /** The session the foreground service is running, or null. Set by SessionService. */
    val activeSession = MutableStateFlow<ActiveSession?>(null)

    /**
     * Id of the session that just ended, for the summary screen (read from Room); cleared when
     * the user leaves it. Only in memory: after a process restart the session is in the history.
     */
    val finishedSessionId = MutableStateFlow<String?>(null)

    /** Housekeeping at process start: see SessionRepository.closeAbandonedSessions. */
    fun onAppStart(appStartEpochMs: Long) {
        appScope.launch {
            val closed = sessions.closeAbandonedSessions(startedBefore = appStartEpochMs)
            if (closed > 0) Log.w("Pulse/App", "$closed session(s) from a previous run marked incomplete")
        }
    }
}
