package com.antigravity.gemininanotaskmanager.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.antigravity.gemininanotaskmanager.domain.model.LinkedMeeting
import com.antigravity.gemininanotaskmanager.domain.model.PlaceTrigger
import com.antigravity.gemininanotaskmanager.domain.model.TaskPriority
import com.antigravity.gemininanotaskmanager.domain.model.Recurrence
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import com.antigravity.gemininanotaskmanager.domain.model.TaskStatus

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "status") val status: TaskStatus = TaskStatus.TODO,
    @ColumnInfo(name = "category") val category: TaskCategory = TaskCategory.PERSONAL,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "due_at") val dueAt: Long? = null,
    @ColumnInfo(name = "due_has_time") val dueHasTime: Boolean = false,
    // v4: estadísticas y sincronización
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    /** Id de la tarea en Google Tasks (null = aún no subida). */
    @ColumnInfo(name = "google_task_id") val googleTaskId: String? = null,
    /** `updated` de Google Tasks en la última sincronización (para detectar cambios remotos). */
    @ColumnInfo(name = "remote_updated_at") val remoteUpdatedAt: Long? = null,
    /** Evento creado en el calendario del móvil (CalendarContract) para citas con hora. */
    @ColumnInfo(name = "calendar_event_id") val calendarEventId: Long? = null,
    // v5: recurrencia, reunión vinculada y avisos automáticos
    @ColumnInfo(name = "recurrence") val recurrence: String? = null,
    @ColumnInfo(name = "meeting_event_id") val meetingEventId: Long? = null,
    @ColumnInfo(name = "meeting_title") val meetingTitle: String? = null,
    @ColumnInfo(name = "meeting_start") val meetingStart: Long? = null,
    @ColumnInfo(name = "auto_reminders") val autoReminders: Boolean = true,
    // v6: prioridad y aviso por lugar
    @ColumnInfo(name = "priority") val priority: TaskPriority = TaskPriority.NONE,
    @ColumnInfo(name = "place_trigger") val placeTrigger: String? = null
)

fun TaskEntity.toDomain(): Task = Task(
    id, title, description, status, category, createdAt, updatedAt, dueAt, dueHasTime, completedAt,
    recurrence = Recurrence.parse(recurrence),
    meeting = if (meetingEventId != null && meetingStart != null) LinkedMeeting(meetingEventId, meetingTitle.orEmpty(), meetingStart) else null,
    autoReminders = autoReminders,
    priority = priority,
    placeTrigger = PlaceTrigger.parse(placeTrigger)
)

/** Solo para tareas nuevas. Para actualizar usar [mergeFrom] y no perder los ids de sincronización. */
fun Task.toEntity(): TaskEntity = TaskEntity(
    id, title, description, status, category, createdAt, updatedAt, dueAt, dueHasTime, completedAt,
    recurrence = recurrence?.serialize(),
    meetingEventId = meeting?.eventId, meetingTitle = meeting?.title, meetingStart = meeting?.start,
    autoReminders = autoReminders,
    priority = priority,
    placeTrigger = placeTrigger?.serialize()
)

/** Aplica los campos editables del dominio conservando los metadatos de sincronización. */
fun TaskEntity.mergeFrom(task: Task): TaskEntity = copy(
    title = task.title,
    description = task.description,
    status = task.status,
    category = task.category,
    updatedAt = task.updatedAt,
    dueAt = task.dueAt,
    dueHasTime = task.dueHasTime,
    completedAt = task.completedAt,
    recurrence = task.recurrence?.serialize(),
    meetingEventId = task.meeting?.eventId,
    meetingTitle = task.meeting?.title,
    meetingStart = task.meeting?.start,
    autoReminders = task.autoReminders,
    priority = task.priority,
    placeTrigger = task.placeTrigger?.serialize()
)
