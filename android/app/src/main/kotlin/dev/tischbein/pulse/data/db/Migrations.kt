package dev.tischbein.pulse.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.tischbein.pulse.coaching.RecordedSample
import dev.tischbein.pulse.data.encodeSparkline
import dev.tischbein.pulse.data.sparklineOf

/*
 * Database migrations. Each one turns the schema of version N (app/schemas/.../N.json) into
 * N+1 without losing data. Room runs them on the first app start after an update.
 */

/**
 * 1 -> 2 (Milestone 4): digest columns on `sessions`, back-filled for every session that is
 * already finished, so the Progress page shows the full history right after the update.
 *
 * - avgHrr60 / avgWorkHr / workPctInZone: averages of the stored `intervals` rows (plain SQL;
 *   AVG over no rows is NULL, the same "unknown" as for a new session without intervals).
 * - sparkline: needs the median-per-bucket of the samples, which SQLite cannot do, so it is
 *   computed in Kotlin with the same function the app uses for new sessions.
 * Sessions still "recording" are skipped: they get their digest when they are closed.
 */
val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN avgHrr60 REAL")
            db.execSQL("ALTER TABLE sessions ADD COLUMN avgWorkHr REAL")
            db.execSQL("ALTER TABLE sessions ADD COLUMN workPctInZone REAL")
            db.execSQL("ALTER TABLE sessions ADD COLUMN sparkline TEXT")
            db.execSQL(
                """
                UPDATE sessions SET
                  avgHrr60 = (SELECT AVG(hrr60) FROM intervals WHERE intervals.sessionId = sessions.id),
                  avgWorkHr = (SELECT AVG(avgHr) FROM intervals WHERE intervals.sessionId = sessions.id),
                  workPctInZone = (SELECT AVG(pctInZone) FROM intervals WHERE intervals.sessionId = sessions.id)
                WHERE status != '${SessionStatus.RECORDING}'
                """.trimIndent(),
            )
            val finished = mutableListOf<Pair<String, Long>>()
            db.query("SELECT id, durationMs FROM sessions WHERE status != '${SessionStatus.RECORDING}'").use { c ->
                while (c.moveToNext()) finished += c.getString(0) to (if (c.isNull(1)) 0L else c.getLong(1))
            }
            for ((id, durationMs) in finished) {
                val samples = mutableListOf<RecordedSample>()
                db.query("SELECT tMs, hr FROM samples WHERE sessionId = ? ORDER BY tMs, rowId", arrayOf<Any?>(id)).use { c ->
                    while (c.moveToNext()) samples += RecordedSample(c.getLong(0), if (c.isNull(1)) null else c.getInt(1), emptyList())
                }
                val duration = if (durationMs > 0) durationMs else samples.maxOfOrNull { it.tMs } ?: 0L
                db.execSQL("UPDATE sessions SET sparkline = ? WHERE id = ?", arrayOf<Any?>(encodeSparkline(sparklineOf(samples, duration)), id))
            }
        }
    }

/**
 * 2 -> 3 (Milestone 5): upload state per session. The column defaults do the back-fill: every
 * existing session becomes "pending" with no attempts, so the whole history is uploaded once
 * sync is configured. A session still "recording" is pending too, but the uploader skips it
 * until it is closed (complete or incomplete). The SQL matches app/schemas/.../3.json.
 */
val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN syncState TEXT NOT NULL DEFAULT '${SyncState.PENDING}'")
            db.execSQL("ALTER TABLE sessions ADD COLUMN syncError TEXT")
            db.execSQL("ALTER TABLE sessions ADD COLUMN syncAttemptAtEpochMs INTEGER")
            db.execSQL("ALTER TABLE sessions ADD COLUMN syncAttempts INTEGER NOT NULL DEFAULT 0")
        }
    }
