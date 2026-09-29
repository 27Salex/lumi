package com.antigravity.gemininanotaskmanager.data.local

import androidx.room.TypeConverter
import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import com.antigravity.gemininanotaskmanager.domain.model.TaskPriority
import com.antigravity.gemininanotaskmanager.domain.model.TaskStatus

class Converters {
    @TypeConverter fun fromTaskStatus(v: TaskStatus): String = v.name
    @TypeConverter fun toTaskStatus(v: String): TaskStatus = TaskStatus.fromString(v)
    @TypeConverter fun fromCategory(v: TaskCategory): String = v.name
    @TypeConverter fun toCategory(v: String): TaskCategory =
        runCatching { TaskCategory.valueOf(v) }.getOrDefault(TaskCategory.PERSONAL)
    @TypeConverter fun fromPriority(v: TaskPriority): String = v.name
    @TypeConverter fun toPriority(v: String): TaskPriority = TaskPriority.fromString(v) ?: TaskPriority.NONE
}
