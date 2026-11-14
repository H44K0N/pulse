package dev.tischbein.pulse.data.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tischbein.pulse.data.SessionRepository
import dev.tischbein.pulse.data.TestSessions
import dev.tischbein.pulse.data.db.PulseDatabase
import dev.tischbein.pulse.data.db.SampleEntity
import dev.tischbein.pulse.data.db.SessionEntity
import dev.tischbein.pulse.data.db.SyncState
import dev.tischbein.pulse.data.settings.AppSettings
import dev.tischbein.pulse.data.settings.SettingsRepository
import dev.tischbein.pulse.data.shapeProblems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The upload logic against a real Room database (Robolectric) and a real DataStore, with a
 * scripted HTTP layer: which sessions are sent, what each server answer does to their state,
 * and when a run stops and asks to be retried.
 */
@RunWith(RobolectricTestRunner::class)
class SessionUploaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: PulseDatabase
    private lateinit var sessions: SessionRepository
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var settings: SettingsRepository
    private val http = FakeHttp()
    private var now = 1_800_000_000_000L
    private lateinit var uploader: SessionUploader

    private val server = "https://pulse.example.org"
    private val token = "test-token-not-real"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java).allowMainThreadQueries().build()
        sessions = SessionRepository(db.sessionDao())
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") })
        uploader = SessionUploader(sessions, settings, SyncClient(http, allowLocalHttp = false), allowLocalHttp = false, nowMs = { now })
        runBlocking { settings.save(AppSettings(serverUrl = server, apiToken = token)) }
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    private val result by lazy { TestSessions.fourByFourResult() }

    /** A finished (complete) session, [order] days after the fixture start. */
    private suspend fun completeSession(
        id: String,
        order: Int,
    ) {
        sessions.startSession(row(id, order))
        sessions.finishSession(id, result)
    }

    private fun row(
        id: String,
        order: Int,
    ): SessionEntity = TestSessions.sessionRow(id).copy(startedAtEpochMs = TestSessions.STARTED_AT + order * 86_400_000L)

    private suspend fun state(id: String) = assertNotNull(sessions.session(id))

    private fun idOf(body: String?) = Json.parseToJsonElement(body!!).jsonObject.getValue("id").jsonPrimitive.content

    @Test
    fun `uploads finished sessions oldest first as contract documents and marks them synced`() =
        runBlocking {
            completeSession("b", order = 2)
            completeSession("a", order = 1)
            sessions.startSession(row("c", order = 3))
            sessions.appendRecording(listOf(SampleEntity(sessionId = "c", tMs = 1_000, hr = 120, rrMs = "500")), emptyList(), emptyList())
            sessions.markIncomplete("c") // app died mid-session: finished, incomplete

            assertEquals(UploadRun.DONE, uploader.uploadPending())

            assertEquals(listOf("a", "b", "c"), http.posts.map { idOf(it.body) })
            assertTrue(http.posts.all { it.url == "$server/api/sessions" && it.token == token })
            val example = Json.parseToJsonElement(TestSessions.contractExample())
            http.posts.forEach { assertEquals(emptyList(), shapeProblems(example, Json.parseToJsonElement(it.body!!))) }
            assertEquals(sessions.exportJson("a"), http.posts.first().body, "the document is the contract mapper's output")
            for (id in listOf("a", "b", "c")) {
                val s = state(id)
                assertEquals(SyncState.SYNCED, s.syncState)
                assertNull(s.syncError)
                assertEquals(now, s.syncAttemptAtEpochMs)
                assertEquals(1, s.syncAttempts)
            }
            // Nothing left: a second run sends nothing.
            assertEquals(UploadRun.DONE, uploader.uploadPending())
            assertEquals(3, http.posts.size)
        }

    @Test
    fun `200 already there counts as synced`() =
        runBlocking {
            completeSession("a", order = 1)
            http.onPost = { HttpResponse(200, """{"created":false}""") }
            assertEquals(UploadRun.DONE, uploader.uploadPending())
            assertEquals(SyncState.SYNCED, state("a").syncState)
        }

    @Test
    fun `never uploads a session that is still recording`() =
        runBlocking {
            sessions.startSession(row("live", order = 1))
            assertEquals(UploadRun.DONE, uploader.uploadPending())
            assertTrue(http.posts.isEmpty())
            assertEquals(SyncState.PENDING, state("live").syncState)
            assertEquals(0, sessions.observeSyncOverview().first().pending, "a recording session is not 'waiting'")
        }

    @Test
    fun `wrong token stops the run, keeps sessions pending with the reason and asks for a retry`() =
        runBlocking {
            completeSession("a", order = 1)
            completeSession("b", order = 2)
            http.onPost = { HttpResponse(401, """{"detail":"Not authenticated"}""") }

            assertEquals(UploadRun.RETRY, uploader.uploadPending())
            assertEquals(1, http.posts.size, "stops at the first session: the next would fail the same way")
            val a = state("a")
            assertEquals(SyncState.PENDING, a.syncState)
            assertEquals(SyncClient.WRONG_TOKEN, a.syncError)
            assertEquals(1, a.syncAttempts)
            val overview = sessions.observeSyncOverview().first()
            assertEquals(2, overview.pending)
            assertEquals(SyncClient.WRONG_TOKEN, overview.lastError)

            // Token fixed: the retry uploads both and clears the error.
            http.onPost = { HttpResponse(201, "{}") }
            now += 60_000
            assertEquals(UploadRun.DONE, uploader.uploadPending())
            assertEquals(SyncState.SYNCED, state("a").syncState)
            assertNull(state("a").syncError)
            assertEquals(2, state("a").syncAttempts)
            assertNull(sessions.observeSyncOverview().first().lastError)
            assertEquals(now, sessions.observeSyncOverview().first().lastSyncedAtEpochMs)
        }

    @Test
    fun `server errors and no connection are retried later`() =
        runBlocking {
            completeSession("a", order = 1)
            http.onPost = { HttpResponse(503, "") }
            assertEquals(UploadRun.RETRY, uploader.uploadPending())
            assertEquals(SyncState.PENDING, state("a").syncState)
            assertEquals("Server error (HTTP 503)", state("a").syncError)

            http.onPost = FakeHttp.offline()
            assertEquals(UploadRun.RETRY, uploader.uploadPending())
            assertEquals(SyncState.PENDING, state("a").syncState)
            assertEquals(SyncClient.UNREACHABLE, state("a").syncError)
            assertEquals(2, state("a").syncAttempts)
        }

    @Test
    fun `413 and 422 fail that session for good and the run goes on`() =
        runBlocking {
            completeSession("big", order = 1)
            completeSession("bad", order = 2)
            completeSession("good", order = 3)
            http.onPost = { req ->
                when (idOf(req.body)) {
                    "big" -> HttpResponse(413, """{"detail":"Request body too large"}""")
                    "bad" -> HttpResponse(422, """{"detail":[{"loc":["body","samples",0,"hr"],"msg":"Input should be a valid integer"}]}""")
                    else -> HttpResponse(201, "{}")
                }
            }

            assertEquals(UploadRun.DONE, uploader.uploadPending())
            assertEquals(SyncState.FAILED, state("big").syncState)
            assertEquals(SyncState.FAILED, state("bad").syncState)
            assertEquals("Rejected by the server: samples.0.hr: Input should be a valid integer", state("bad").syncError)
            assertEquals(SyncState.SYNCED, state("good").syncState)

            // Not retried automatically...
            assertEquals(UploadRun.DONE, uploader.uploadPending())
            assertEquals(3, http.posts.size)
            assertEquals(2, sessions.observeSyncOverview().first().failed)
            // ...but "Sync now" gives them one more try.
            assertEquals(2, sessions.retryRejected())
            http.onPost = { HttpResponse(201, "{}") }
            assertEquals(UploadRun.DONE, uploader.uploadPending())
            assertEquals(SyncState.SYNCED, state("bad").syncState)
        }

    @Test
    fun `without URL and token nothing is sent`() =
        runBlocking {
            completeSession("a", order = 1)
            settings.save(AppSettings())
            assertEquals(UploadRun.NOT_CONFIGURED, uploader.uploadPending())
            settings.save(AppSettings(serverUrl = server))
            assertEquals(UploadRun.NOT_CONFIGURED, uploader.uploadPending())
            settings.save(AppSettings(serverUrl = "http://pulse.example.org", apiToken = token))
            assertEquals(UploadRun.NOT_CONFIGURED, uploader.uploadPending(), "never over plain http")
            assertTrue(http.requests.isEmpty())
            assertEquals(SyncState.PENDING, state("a").syncState)
        }

    @Test
    fun `a different server gets the whole history again, the same one does not`() =
        runBlocking {
            completeSession("a", order = 1)
            assertEquals(UploadRun.DONE, uploader.uploadPending())
            assertEquals(1, http.posts.size)

            settings.save(AppSettings(serverUrl = "$server/", apiToken = "another-token"))
            assertEquals(UploadRun.DONE, uploader.uploadPending())
            assertEquals(1, http.posts.size, "same server (trailing slash) and a new token: nothing to resend")

            settings.save(AppSettings(serverUrl = "https://other.example.org", apiToken = token))
            assertEquals(UploadRun.DONE, uploader.uploadPending())
            assertEquals(2, http.posts.size)
            assertEquals("https://other.example.org/api/sessions", http.posts.last().url)
            assertEquals(SyncState.SYNCED, state("a").syncState)
        }

    @Test
    fun `a session deleted after it was listed is skipped`() =
        runBlocking {
            completeSession("a", order = 1)
            completeSession("b", order = 2)
            http.onPost = { req ->
                if (idOf(req.body) == "a") runBlocking { sessions.delete("b") }
                HttpResponse(201, "{}")
            }
            assertEquals(UploadRun.DONE, uploader.uploadPending())
            assertEquals(listOf("a"), http.posts.map { idOf(it.body) })
        }
}
