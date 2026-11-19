package io.github.salex27.lumi.data.local

import androidx.room.TypeConverter
import io.github.salex27.lumi.domain.model.TaskCategory
import io.github.salex27.lumi.domain.model.TaskPriority
import io.github.salex27.lumi.domain.model.TaskStatus

class Converters {
    @TypeConverter fun fromTaskStatus(v: TaskStatus): String = v.name
    @TypeConverter fun toTaskStatus(v: String): TaskStatus = TaskStatus.fromString(v)
    @TypeConverter fun fromCategory(v: TaskCategory): String = v.name
    @TypeConverter fun toCategory(v: String): TaskCategory =
        runCatching { TaskCategory.valueOf(v) }.getOrDefault(TaskCategory.PERSONAL)
    @TypeConverter fun fromKind(v: io.github.salex27.lumi.domain.model.TaskKind): String = v.name
    @TypeConverter fun toKind(v: String): io.github.salex27.lumi.domain.model.TaskKind =
        io.github.salex27.lumi.domain.model.TaskKind.fromString(v) ?: io.github.salex27.lumi.domain.model.TaskKind.TASK
    @TypeConverter fun fromPriority(v: TaskPriority): String = v.name
    @TypeConverter fun toPriority(v: String): TaskPriority = TaskPriority.fromString(v) ?: TaskPriority.NONE
}
