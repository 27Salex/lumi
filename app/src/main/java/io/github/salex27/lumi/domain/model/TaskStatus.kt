package io.github.salex27.lumi.domain.model

enum class TaskStatus {
    TODO,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    companion object {
        fun fromString(value: String): TaskStatus {
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: TODO
        }
    }
}
