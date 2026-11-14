package dev.tischbein.pulse.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tischbein.pulse.data.SessionDigest
import dev.tischbein.pulse.data.TestSessions
import dev.tischbein.pulse.data.decodeSparkline
import dev.tischbein.pulse.data.encodeRr
import dev.tischbein.pulse.data.encodeSparkline
import dev.tischbein.pulse.data.sparklineOf
import dev.tischbein.pulse.coaching.RecordedSample
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Database migrations, tested against a real SQLite file (Robolectric, on the JVM).
 *
 * Equivalent to Room's MigrationTestHelper without its extra dependency and asset setup: the
 * old database is created from the committed schema JSON of version 1 (exactly the SQL Room
 * generated for that version), filled with rows, then opened with the current Room database
 * and its migrations. Room itself then checks that the migrated schema matches the current
 * entities (it throws "Migration didn't properly handle ..." if not).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private lateinit var context: Context
    private val name = "migration-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    private fun schema(version: Int): JsonObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "schemas").isDirectory) dir = dir.parentFile
        val file = File(checkNotNull(dir) { "app/schemas not found" }, "schemas/${PulseDatabase::class.qualifiedName}/$version.json")
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
    }

    /** Creates the database file exactly as version [version] of the app did. */
    private fun createDatabase(version: Int): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        val s = schema(version)
        for (entity in s.getValue("entities").jsonArray) {
            val e = entity.jsonObject
            val table = e.getValue("tableName").jsonPrimitive.content
            db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        s.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }

    private fun SQLiteDatabase.insertSession(
        id: String,
        status: String,
        durationMs: Long?,
    ) {
        val row = TestSessions.sessionRow(id)
        insertOrThrow(
            "sessions",
            null,
            ContentValues().apply {
                put("id", id)
                put("mode", row.mode)
                put("status", status)
                put("startedAtEpochMs", row.startedAtEpochMs)
                put("hrMaxUsed", row.hrMaxUsed)
                put("deviceKind", row.deviceKind)
                put("settingsJson", row.settingsJson)
                put("durationMs", durationMs)
            },
        )
    }

    /** Opens the test database with the current Room schema and every migration. */
    private fun openMigrated(): PulseDatabase =
        Room
            .databaseBuilder(context, PulseDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `1 to 2 adds the digest columns and back-fills them from the stored rows`() =
        runBlocking {
            val result = TestSessions.fourByFourResult()
            createDatabase(1).apply {
                // A complete session with samples and intervals...
                insertSession("complete", SessionStatus.COMPLETE, result.summary.durationMs)
                result.samples.forEach { s ->
                    insertOrThrow("samples", null, ContentValues().apply { put("sessionId", "complete"); put("tMs", s.tMs); put("hr", s.hr); put("rrMs", encodeRr(s.rrMs)) })
                }
                result.intervals.forEach { i ->
                    insertOrThrow(
                        "intervals",
                        null,
                        ContentValues().apply { put("sessionId", "complete"); put("number", i.number); put("avgHr", i.avgHr); put("peakHr", i.peakHr); put("pctInZone", i.pctInZone); put("hrr60", i.hrr60) },
                    )
                }
                // ...an incomplete one with a few samples and no intervals, and one still recording.
                insertSession("incomplete", SessionStatus.INCOMPLETE, 3_000L)
                listOf(1_000L to 120, 2_000L to 130, 3_000L to 140).forEach { (t, hr) ->
                    insertOrThrow("samples", null, ContentValues().apply { put("sessionId", "incomplete"); put("tMs", t); put("hr", hr); put("rrMs", "") })
                }
                insertSession("recording", SessionStatus.RECORDING, null)
                close()
            }

            val db = openMigrated()
            try {
                val dao = db.sessionDao()
                val expected = SessionDigest.of("complete", result, 0L)
                val complete = assertNotNull(dao.session("complete"))
                assertEquals(expected.avgHrr60!!, complete.avgHrr60!!, 1e-9)
                assertEquals(expected.avgWorkHr!!, complete.avgWorkHr!!, 1e-9)
                assertEquals(expected.workPctInZone!!, complete.workPctInZone!!, 1e-9)
                assertEquals(encodeSparkline(expected.sparkline), complete.sparkline)

                val incomplete = assertNotNull(dao.session("incomplete"))
                assertNull(incomplete.avgHrr60)
                val samples = listOf(RecordedSample(1_000, 120, emptyList()), RecordedSample(2_000, 130, emptyList()), RecordedSample(3_000, 140, emptyList()))
                assertEquals(sparklineOf(samples, 3_000), decodeSparkline(incomplete.sparkline))

                assertNull(assertNotNull(dao.session("recording")).sparkline)
                // Old data is untouched and readable through the new queries.
                assertEquals(result.samples.size, dao.samples("complete").size)
                assertEquals(listOf("complete", "incomplete").sorted(), dao.observeAllFinished().first().map { it.id }.sorted())
            } finally {
                db.close()
            }
        }

    @Test
    fun `2 to 3 adds the sync state, every existing session pending`() =
        runBlocking {
            createDatabase(2).apply {
                insertSession("complete", SessionStatus.COMPLETE, 60_000L)
                insertSession("incomplete", SessionStatus.INCOMPLETE, 3_000L)
                insertSession("recording", SessionStatus.RECORDING, null)
                close()
            }

            val db = openMigrated()
            try {
                val dao = db.sessionDao()
                for (id in listOf("complete", "incomplete", "recording")) {
                    val s = assertNotNull(dao.session(id))
                    assertEquals(SyncState.PENDING, s.syncState)
                    assertNull(s.syncError)
                    assertNull(s.syncAttemptAtEpochMs)
                    assertEquals(0, s.syncAttempts)
                }
                // The finished ones are queued for upload, oldest first; the recording one is not.
                assertEquals(listOf("complete", "incomplete").sorted(), dao.pendingUploadIds().sorted())
                assertEquals(2, dao.observeSyncOverview().first().pending)
                // The sync updates work on migrated rows.
                dao.markSynced("complete", 123L)
                assertEquals(SyncState.SYNCED, assertNotNull(dao.session("complete")).syncState)
                assertEquals(123L, dao.observeSyncOverview().first().lastSyncedAtEpochMs)
            } finally {
                db.close()
            }
        }
}
