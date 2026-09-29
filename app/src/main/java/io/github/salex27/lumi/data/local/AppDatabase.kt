package io.github.salex27.lumi.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN category TEXT NOT NULL DEFAULT 'PERSONAL'")
    }
}

// v3: due dates / appointments (for reminders and day planning)
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN due_at INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN due_has_time INTEGER NOT NULL DEFAULT 0")
    }
}

// v4: stats (completed_at) + Google Tasks / Calendar sync
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN completed_at INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN google_task_id TEXT")
        db.execSQL("ALTER TABLE tasks ADD COLUMN remote_updated_at INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN calendar_event_id INTEGER")
        // Best approximation for already completed tasks: their last modification
        db.execSQL("UPDATE tasks SET completed_at = updated_at WHERE status = 'COMPLETED'")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_tombstones (googleTaskId TEXT NOT NULL, PRIMARY KEY(googleTaskId))")
    }
}

// v5: recurrence, linked meeting, multiple reminders
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN recurrence TEXT")
        db.execSQL("ALTER TABLE tasks ADD COLUMN meeting_event_id INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN meeting_title TEXT")
        db.execSQL("ALTER TABLE tasks ADD COLUMN meeting_start INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN auto_reminders INTEGER NOT NULL DEFAULT 1")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS reminders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, task_id INTEGER NOT NULL, " +
                "trigger_at INTEGER NOT NULL, kind TEXT NOT NULL, offset_minutes INTEGER, label TEXT NOT NULL, " +
                "FOREIGN KEY(task_id) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reminders_task_id ON reminders (task_id)")
    }
}

// v6: priority + place reminder
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN priority TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL("ALTER TABLE tasks ADD COLUMN place_trigger TEXT")
    }
}

// v7: personal memory
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS memories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL)")
    }
}

@Database(entities = [TaskEntity::class, SyncTombstone::class, ReminderEntity::class, MemoryEntity::class], version = 7, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun tombstoneDao(): SyncTombstoneDao
    abstract fun reminderDao(): ReminderDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }

        private fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "gemini_tasks_db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    CoroutineScope(Dispatchers.IO).launch { seed(getInstance(context).taskDao()) }
                }
            })
            .build()

        /** Sample data on first install, with dates relative to today, in the app's language. */
        private suspend fun seed(dao: TaskDao) {
            val zone = ZoneId.systemDefault()
            fun at(daysFromNow: Long, hour: Int, minute: Int = 0) =
                LocalDate.now().plusDays(daysFromNow).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            val t = io.github.salex27.lumi.domain.assistant.ReplyLanguage::ui

            dao.insertTask(TaskEntity(title = t("Configurar el asistente de IA", "Set up the AI assistant"), description = t("Descargar Gemma o activar Gemini en Ajustes", "Download Gemma or turn on Gemini in Settings"), status = TaskStatus.IN_PROGRESS, category = TaskCategory.WORK))
            dao.insertTask(TaskEntity(title = t("Preparar presentación del sprint", "Prepare the sprint presentation"), status = TaskStatus.TODO, category = TaskCategory.WORK, dueAt = at(1, 10), dueHasTime = true))
            dao.insertTask(TaskEntity(title = t("Estudiar para el examen", "Study for the exam"), status = TaskStatus.TODO, category = TaskCategory.STUDY, dueAt = at(4, 9)))
            dao.insertTask(TaskEntity(title = t("Ir al gimnasio", "Go to the gym"), description = t("Cardio 30 min", "30 min cardio"), status = TaskStatus.TODO, category = TaskCategory.HEALTH, dueAt = at(0, 19), dueHasTime = true))
            dao.insertTask(TaskEntity(title = t("Comprar regalo de cumpleaños", "Buy a birthday present"), status = TaskStatus.TODO, category = TaskCategory.PERSONAL))
            dao.insertTask(TaskEntity(title = t("Revisar arquitectura Clean + UDF", "Review Clean + UDF architecture"), status = TaskStatus.COMPLETED, category = TaskCategory.STUDY, completedAt = at(-1, 18)))
        }
    }
}
