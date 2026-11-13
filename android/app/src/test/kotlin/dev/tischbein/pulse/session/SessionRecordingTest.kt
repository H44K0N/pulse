package dev.tischbein.pulse.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tischbein.pulse.coaching.CoachingEngine
import dev.tischbein.pulse.coaching.CoachingEvent
import dev.tischbein.pulse.coaching.EventType
import dev.tischbein.pulse.data.SessionRepository
import dev.tischbein.pulse.data.TestSessions
import dev.tischbein.pulse.data.db.PulseDatabase
import dev.tischbein.pulse.data.db.SessionStatus
import dev.tischbein.pulse.data.shapeProblems
import dev.tischbein.pulse.sensor.HeartRateSample
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Room recording against a real (in-memory) SQLite database. Robolectric provides the Android
 * Context and SQLite that Room needs, on the JVM, because there is no device to run on.
 */
@RunWith(RobolectricTestRunner::class)
class SessionRecordingTest {
    private lateinit var db: PulseDatabase
    private lateinit var repo: SessionRepository
    private var clockMs = 10_000L
    private val startClockMs = 10_000L
    private val sessionId = TestSessions.sessionRow().id

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java).allowMainThreadQueries().build()
        repo = SessionRepository(db.sessionDao())
    }

    @After
    fun tearDown() = db.close()

    private fun recorder() = SessionRecorder(repo, sessionId, startClockMs) { clockMs }

    @Test
    fun `incremental flushes store samples, events and phases`() =
        runBlocking {
            repo.startSession(TestSessions.sessionRow())
            val recorder = recorder()
            val engine = CoachingEngine(TestSessions.setup, TestSessions.config)
            engine.start(startClockMs)
            recorder.onState(engine.state.value)
            recorder.onEvent(CoachingEvent(0, EventType.PHASE_START, "Warmup."))
            recorder.onSample(HeartRateSample(startClockMs + 1_000, 100, listOf(600)))
            recorder.onSample(HeartRateSample(startClockMs + 2_000, null))
            recorder.flush()

            // Move into the first work interval: the warmup row gets its end, a work row starts.
            clockMs = startClockMs + 61_000
            engine.onTick(clockMs)
            recorder.onState(engine.state.value)
            recorder.flush()

            val dao = db.sessionDao()
            val samples = dao.samples(sessionId)
            assertEquals(listOf(1_000L, 2_000L), samples.map { it.tMs })
            assertEquals(listOf(100, null), samples.map { it.hr })
            assertEquals("600", samples[0].rrMs)
            assertEquals(listOf("phase_start"), dao.events(sessionId).map { it.type })
            val phases = dao.phases(sessionId)
            assertEquals(listOf("warmup", "work"), phases.map { it.kind })
            assertEquals(61_000L, phases[0].endMs)
            assertNull(phases[1].endMs)
            assertEquals(1, phases[1].intervalNumber)
        }

    @Test
    fun `a session abandoned by process death is kept and marked incomplete on next start`() =
        runBlocking {
            repo.startSession(TestSessions.sessionRow())
            val recorder = recorder()
            val engine = CoachingEngine(TestSessions.setup, TestSessions.config)
            engine.start(startClockMs)
            recorder.onState(engine.state.value)
            listOf(120, 130, 140).forEachIndexed { i, hr -> recorder.onSample(HeartRateSample(startClockMs + (i + 1) * 1_000L, hr)) }
            recorder.flush()
            recorder.onSample(HeartRateSample(startClockMs + 4_000, 150)) // never flushed: lost in the "crash"

            // "Next app start": a new repository on the same database.
            val closed = SessionRepository(db.sessionDao()).closeAbandonedSessions(startedBefore = Long.MAX_VALUE)

            assertEquals(1, closed)
            val session = assertNotNull(repo.session(sessionId))
            assertEquals(SessionStatus.INCOMPLETE, session.status)
            assertEquals(3_000L, session.durationMs)
            assertEquals(130, session.avgHr)
            assertEquals(140, session.maxHr)
            assertEquals(TestSessions.STARTED_AT + 3_000, session.endedAtEpochMs)
            assertEquals(3, db.sessionDao().samples(sessionId).size)
            assertEquals(3_000L, db.sessionDao().phases(sessionId).single().endMs)

            val json = Json.parseToJsonElement(assertNotNull(repo.exportJson(sessionId))).jsonObject
            assertEquals("false", json.getValue("complete").jsonPrimitive.content)
            val problems = shapeProblems(Json.parseToJsonElement(TestSessions.contractExample()), json)
            // Incomplete sessions have no intervals/gaps arrays filled, but every field is there.
            assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        }

    @Test
    fun `sessions started after app start are not touched`() =
        runBlocking {
            repo.startSession(TestSessions.sessionRow())
            assertEquals(0, repo.closeAbandonedSessions(startedBefore = TestSessions.STARTED_AT))
            assertEquals(SessionStatus.RECORDING, repo.session(sessionId)?.status)
        }

    @Test
    fun `finishing replaces incremental rows with the engine's result`() =
        runBlocking {
            repo.startSession(TestSessions.sessionRow())
            val recorder = recorder()
            recorder.onSample(HeartRateSample(startClockMs + 1_000, 999)) // will be replaced
            recorder.flush()

            val result = TestSessions.fourByFourResult()
            repo.finishSession(sessionId, result)

            val dao = db.sessionDao()
            val session = assertNotNull(dao.session(sessionId))
            assertEquals(SessionStatus.COMPLETE, session.status)
            assertEquals(result.summary.durationMs, session.durationMs)
            assertEquals(result.samples.size, dao.samples(sessionId).size)
            assertTrue(dao.samples(sessionId).none { it.hr == 999 })
            assertEquals(result.phases.size, dao.phases(sessionId).size)
            assertEquals(result.intervals.size, dao.intervals(sessionId).size)
            assertEquals(result.gaps.size, dao.gaps(sessionId).size)
            assertEquals(result.events.size, dao.events(sessionId).size)
            assertEquals(result.pauses.size, dao.pauses(sessionId).size)

            val json = Json.parseToJsonElement(assertNotNull(repo.exportJson(sessionId)))
            val problems = shapeProblems(Json.parseToJsonElement(TestSessions.contractExample()), json)
            assertTrue(problems.isEmpty(), problems.joinToString("\n"))

            // Completed sessions are not "abandoned" on the next start.
            assertEquals(0, repo.closeAbandonedSessions(startedBefore = Long.MAX_VALUE))
        }

    @Test
    fun `deleting a session deletes its rows`() =
        runBlocking {
            repo.startSession(TestSessions.sessionRow())
            repo.finishSession(sessionId, TestSessions.fourByFourResult())
            repo.delete(sessionId)
            assertNull(repo.session(sessionId))
            assertTrue(db.sessionDao().samples(sessionId).isEmpty())
            assertTrue(db.sessionDao().events(sessionId).isEmpty())
        }
}
