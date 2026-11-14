package dev.tischbein.pulse.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tischbein.pulse.data.db.PulseDatabase
import dev.tischbein.pulse.data.db.SessionStatus
import dev.tischbein.pulse.sensor.HeartRateSample
import dev.tischbein.pulse.session.SessionRecorder
import dev.tischbein.pulse.ui.sessionDetail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** History reads: digests stored with each session, the list queries and the detail round trip. */
@RunWith(RobolectricTestRunner::class)
class HistoryTest {
    private lateinit var db: PulseDatabase
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java).allowMainThreadQueries().build()
        repo = SessionRepository(db.sessionDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun finished(
        id: String,
        startedAt: Long,
    ) {
        repo.startSession(TestSessions.sessionRow(id).copy(startedAtEpochMs = startedAt))
        repo.finishSession(id, TestSessions.fourByFourResult())
    }

    @Test
    fun `a finished session stores its digest`() =
        runBlocking {
            finished("a", TestSessions.STARTED_AT)
            val digest = repo.observeRecent(8).first().single()
            assertEquals(SessionDigest.of("a", TestSessions.fourByFourResult(), TestSessions.STARTED_AT), digest)
            assertTrue(digest.sparkline.isNotEmpty())
        }

    @Test
    fun `lists are newest first, skip the running session, and the window filters by start`() =
        runBlocking {
            val day = 86_400_000L
            (0 until 10).forEach { finished("s$it", TestSessions.STARTED_AT + it * day) }
            repo.startSession(TestSessions.sessionRow("running").copy(startedAtEpochMs = TestSessions.STARTED_AT + 20 * day))

            assertEquals(listOf("s9", "s8", "s7"), repo.observeRecent(3).first().map { it.id })
            assertEquals(10, repo.observeAll().first().size)
            assertEquals(10, repo.observeCount().first())
            assertEquals(listOf("s9", "s8"), repo.observeSince(TestSessions.STARTED_AT + 8 * day).first().map { it.id })
        }

    @Test
    fun `a stored session reads back as the same detail screen as right after the session`() =
        runBlocking {
            finished("a", TestSessions.STARTED_AT)
            val loaded = assertNotNull(repo.load("a"))
            val original = TestSessions.fourByFourResult()
            assertEquals(original.config, loaded.result.config)
            assertEquals(original.setup, loaded.result.setup)
            val utc = ZoneId.of("UTC")
            assertEquals(sessionDetail(original, TestSessions.STARTED_AT, utc), sessionDetail(loaded.result, loaded.entity.startedAtEpochMs, utc))
            assertTrue(loaded.complete)
            assertFalse(loaded.simulated)
        }

    @Test
    fun `an incomplete session gets a sparkline and opens without interval stats`() =
        runBlocking {
            repo.startSession(TestSessions.sessionRow("x"))
            val recorder = SessionRecorder(repo, "x", 0L) { 0L }
            listOf(120, 130, 140).forEachIndexed { i, hr -> recorder.onSample(HeartRateSample((i + 1) * 1_000L, hr)) }
            recorder.flush()
            repo.closeAbandonedSessions(startedBefore = Long.MAX_VALUE)

            val digest = repo.observeAll().first().single()
            assertFalse(digest.complete)
            assertEquals(listOf(120, 130, 140), digest.sparkline.filterNotNull())
            val loaded = assertNotNull(repo.load("x"))
            assertEquals(SessionStatus.INCOMPLETE, loaded.entity.status)
            assertTrue(loaded.result.intervals.isEmpty())
            assertEquals(3_000L, loaded.result.summary.durationMs)
        }

    @Test
    fun `a deleted session is gone from every list`() =
        runBlocking {
            finished("a", TestSessions.STARTED_AT)
            repo.delete("a")
            assertTrue(repo.observeAll().first().isEmpty())
            assertEquals(0, repo.observeCount().first())
            assertNull(repo.load("a"))
        }
}
