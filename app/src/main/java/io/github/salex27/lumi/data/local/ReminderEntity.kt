package io.github.salex27.lumi.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import io.github.salex27.lumi.domain.model.TaskReminder

/** A task's reminders (several per task). Deleted automatically with the task (CASCADE). */
@Entity(
    tableName = "reminders",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["task_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("task_id")]
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: Long,
    @ColumnInfo(name = "trigger_at") val triggerAt: Long,
    /** "AUTO" | "CUSTOM" */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "offset_minutes") val offsetMinutes: Int? = null,
    @ColumnInfo(name = "label") val label: String
)

fun ReminderEntity.toDomain() = TaskReminder(id, taskId, triggerAt, TaskReminder.Kind.valueOf(kind), offsetMinutes, label)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE task_id = :taskId ORDER BY trigger_at")
    suspend fun forTask(taskId: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE trigger_at > :now ORDER BY trigger_at")
    suspend fun upcoming(now: Long): List<ReminderEntity>

    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminders WHERE task_id = :taskId AND kind = 'AUTO'")
    suspend fun deleteAuto(taskId: Long)
}
