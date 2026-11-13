package dev.tischbein.pulse.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's SQLite database (file "pulse.db" in the app's private storage).
 *
 * exportSchema = true writes app/schemas/<version>.json at build time; commit it. When the
 * schema changes: bump [version], add a Migration (or an AutoMigration) from the old version,
 * and never edit an already-committed schema file.
 */
@Database(
    entities = [
        SessionEntity::class, SampleEntity::class, EventEntity::class, PhaseEntity::class,
        IntervalEntity::class, GapEntity::class, PauseEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        fun open(context: Context): PulseDatabase =
            Room.databaseBuilder(context.applicationContext, PulseDatabase::class.java, "pulse.db").build()
    }
}
