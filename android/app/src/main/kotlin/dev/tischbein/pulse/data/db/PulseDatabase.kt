package dev.tischbein.pulse.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's SQLite database (file "pulse.db" in the app's private storage).
 *
 * exportSchema = true writes app/schemas/<version>.json at build time; commit it. When the
 * schema changes: bump [version], add a Migration (see Migrations.kt) from the old version,
 * test it in MigrationTest, and never edit an already-committed schema file.
 *
 * History: 1 = Milestone 2 (recording). 2 = Milestone 4 (per-session digest columns).
 * 3 = Milestone 5 (per-session sync state).
 */
@Database(
    entities = [
        SessionEntity::class, SampleEntity::class, EventEntity::class, PhaseEntity::class,
        IntervalEntity::class, GapEntity::class, PauseEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        fun open(context: Context): PulseDatabase =
            Room
                .databaseBuilder(context.applicationContext, PulseDatabase::class.java, "pulse.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
