package com.antigravity.gemininanotaskmanager.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.antigravity.gemininanotaskmanager.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY created_at DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status = :status ORDER BY created_at DESC")
    fun getTasksByStatus(status: TaskStatus): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE LOWER(title) LIKE '%' || LOWER(:query) || '%' ORDER BY updated_at DESC")
    suspend fun searchTasksByTitle(query: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status != 'COMPLETED' AND status != 'CANCELLED' ORDER BY created_at DESC")
    suspend fun getActiveTasksSnapshot(): List<TaskEntity>

    @Query("SELECT * FROM tasks ORDER BY created_at DESC")
    suspend fun getAllTasksSnapshot(): List<TaskEntity>

    /** Tareas activas con fecha futura: se usan para reprogramar recordatorios (arranque, cambio de ajustes). */
    @Query("SELECT * FROM tasks WHERE due_at IS NOT NULL AND status != 'COMPLETED' AND status != 'CANCELLED'")
    suspend fun getActiveTasksWithDue(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE google_task_id = :googleId LIMIT 1")
    suspend fun getByGoogleTaskId(googleId: String): TaskEntity?

    @Query("UPDATE tasks SET calendar_event_id = :eventId WHERE id = :taskId")
    suspend fun setCalendarEventId(taskId: Long, eventId: Long?)

    @Query("SELECT calendar_event_id FROM tasks WHERE calendar_event_id IS NOT NULL")
    suspend fun linkedCalendarEventIds(): List<Long>

    @Query("SELECT COUNT(*) FROM tasks WHERE status != 'COMPLETED' AND status != 'CANCELLED'")
    fun getPendingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Delete
    suspend fun deleteTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)
}
