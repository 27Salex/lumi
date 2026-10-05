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

// v8: chat sessions (shared by the overlay pill and the app; Orbit threads reuse them)
internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ChatSchema.CREATE_V8.forEach { db.execSQL(it) }
    }
}

/** SQL of the chat tables, shared by the migration and its test. Must match ChatEntities exactly (Room checks it). */
internal object ChatSchema {
    val CREATE_V8 = listOf(
        "CREATE TABLE IF NOT EXISTS chat_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, kind TEXT NOT NULL, " +
            "title TEXT NOT NULL, title_custom INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
            "summary TEXT NOT NULL, summary_until INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, session_id INTEGER NOT NULL, " +
            "role TEXT NOT NULL, text TEXT NOT NULL, created_at INTEGER NOT NULL, engine TEXT NOT NULL, is_error INTEGER NOT NULL, " +
            "action TEXT, task_ids TEXT NOT NULL, agent_id INTEGER, payload TEXT, " +
            "FOREIGN KEY(session_id) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS index_chat_messages_session_id ON chat_messages (session_id)"
    )
}

// v9: Orbit agents and their membership in Orbit threads (chat sessions of kind ORBIT)
internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        OrbitSchema.CREATE_V9.forEach { db.execSQL(it) }
    }
}

/** SQL of the Orbit tables, shared by the migration and its check. Must match OrbitEntities exactly (Room checks it). */
internal object OrbitSchema {
    val CREATE_V9 = listOf(
        "CREATE TABLE IF NOT EXISTS agents (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
            "backend TEXT NOT NULL, color TEXT NOT NULL, face TEXT NOT NULL, purpose TEXT NOT NULL, " +
            "can_read_tasks INTEGER NOT NULL, created_at INTEGER NOT NULL, config TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS orbit_members (session_id INTEGER NOT NULL, agent_id INTEGER NOT NULL, " +
            "PRIMARY KEY(session_id, agent_id), " +
            "FOREIGN KEY(session_id) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(agent_id) REFERENCES agents(id) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS index_orbit_members_agent_id ON orbit_members (agent_id)"
    )
}

@Database(
    entities = [
        TaskEntity::class, SyncTombstone::class, ReminderEntity::class, MemoryEntity::class, ChatSessionEntity::class,
        ChatMessageEntity::class, AgentEntity::class, OrbitMemberEntity::class
    ],
    version = 9, exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun tombstoneDao(): SyncTombstoneDao
    abstract fun reminderDao(): ReminderDao
    abstract fun memoryDao(): MemoryDao
    abstract fun chatDao(): ChatDao
    abstract fun orbitDao(): OrbitDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }

        private fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "lumi.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
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
            val t = { es: String, en: String -> io.github.salex27.lumi.domain.assistant.ReplyLanguage.ui(es, en) }

            dao.insertTask(TaskEntity(title = t("Configurar el asistente de IA", "Set up the AI assistant"), description = t("Descargar Gemma o activar Gemini en Ajustes", "Download Gemma or turn on Gemini in Settings"), status = TaskStatus.IN_PROGRESS, category = TaskCategory.WORK))
            dao.insertTask(TaskEntity(title = t("Preparar presentación del sprint", "Prepare the sprint presentation"), status = TaskStatus.TODO, category = TaskCategory.WORK, dueAt = at(1, 10), dueHasTime = true))
            dao.insertTask(TaskEntity(title = t("Estudiar para el examen", "Study for the exam"), status = TaskStatus.TODO, category = TaskCategory.STUDY, dueAt = at(4, 9)))
            dao.insertTask(TaskEntity(title = t("Ir al gimnasio", "Go to the gym"), description = t("Cardio 30 min", "30 min cardio"), status = TaskStatus.TODO, category = TaskCategory.HEALTH, dueAt = at(0, 19), dueHasTime = true))
            dao.insertTask(TaskEntity(title = t("Comprar regalo de cumpleaños", "Buy a birthday present"), status = TaskStatus.TODO, category = TaskCategory.PERSONAL))
            dao.insertTask(TaskEntity(title = t("Revisar arquitectura Clean + UDF", "Review Clean + UDF architecture"), status = TaskStatus.COMPLETED, category = TaskCategory.STUDY, completedAt = at(-1, 18)))
        }
    }
}
