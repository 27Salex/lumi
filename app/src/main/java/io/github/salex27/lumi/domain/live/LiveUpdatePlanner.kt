package io.github.salex27.lumi.domain.live

import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.Task

/**
 * What to show in the live update (lock screen / status bar chip / Samsung Now Bar) and when to recompute it.
 * Pure and tested. Only timed things: a task with a time or a calendar meeting starting within the next
 * [LOOKAHEAD_MS] (or already running). The closest wins; on a tie, the meeting.
 */
object LiveUpdatePlanner {

    data class Item(
        val kind: Kind,
        /** Task or event id. */
        val id: Long,
        val title: String,
        val start: Long,
        /** Shown until: the meeting's end, or a few minutes after the task's time. */
        val until: Long,
        /** Meeting address (for "Directions"). */
        val location: String = ""
    ) {
        enum class Kind { TASK, MEETING }
        /** Stable key for "Hide". */
        val key: String get() = "$kind:$id"
        fun isOngoing(now: Long) = now >= start
    }

    data class Result(val item: Item?, val nextRefreshAt: Long)

    const val LOOKAHEAD_MS = 2 * 60 * 60_000L
    /** A timed task stays visible this long after its time (in case you haven't done it yet). */
    const val TASK_GRACE_MS = 15 * 60_000L
    /** With nothing to show, check again every hour (meetings added from another app). */
    const val IDLE_REFRESH_MS = 60 * 60_000L
    /** While something is shown, refresh the progress bar every 5 min (the system runs the countdown itself). */
    const val ACTIVE_REFRESH_MS = 5 * 60_000L

    /** Progress 0..100: from "enters the window" (2 h before) to its time; while running, from start to end. */
    fun progress(item: Item, now: Long): Int {
        val (from, to) = if (item.isOngoing(now)) item.start to item.until else (item.start - LOOKAHEAD_MS) to item.start
        if (to <= from) return 100
        return (((now - from).toDouble() / (to - from)) * 100).toInt().coerceIn(0, 100)
    }

    fun plan(
        tasks: List<Task>,
        events: List<AgendaEvent>,
        now: Long,
        excludedEventIds: Set<Long> = emptySet(),
        /** Item the user hid from the notification. */
        hiddenKey: String? = null
    ): Result {
        val taskItems = tasks.filter { it.isActive && it.dueHasTime && it.dueAt != null }
            .map { Item(Item.Kind.TASK, it.id, it.title, it.dueAt!!, it.dueAt + TASK_GRACE_MS) }
        val eventItems = events.filter { !it.allDay && it.id !in excludedEventIds }
            .map { Item(Item.Kind.MEETING, it.id, it.title, it.begin, maxOf(it.end, it.begin + TASK_GRACE_MS), it.location) }
        val all = taskItems + eventItems

        val visible = all.filter { now < it.until && it.start <= now + LOOKAHEAD_MS && it.key != hiddenKey }
            .sortedWith(compareBy<Item>({ it.start }, { if (it.kind == Item.Kind.MEETING) 0 else 1 }))
        val chosen = visible.firstOrNull()

        // Next change: the shown item starts ("Now"), ends, or something new enters the window
        val candidates = buildList {
            chosen?.let { add(it.start); add(it.until); add(now + ACTIVE_REFRESH_MS) }
            all.filter { it.start > now + LOOKAHEAD_MS }.minOfOrNull { it.start - LOOKAHEAD_MS }?.let { add(it) }
            add(now + IDLE_REFRESH_MS)
        }.filter { it > now }
        return Result(chosen, candidates.min())
    }

    /** "en 25 min" / "in 25 min", "en 1 h 10 min" / "in 1 h 10 min", "ahora" / "now". Uses the app language. */
    fun countdown(start: Long, now: Long, lang: Lang = ReplyLanguage.app): String {
        val minutes = ((start - now + 59_999) / 60_000).toInt()
        val prefix = if (lang == Lang.EN) "in" else "en"
        return when {
            minutes <= 0 -> if (lang == Lang.EN) "now" else "ahora"
            minutes < 60 -> "$prefix $minutes min"
            minutes % 60 == 0 -> "$prefix ${minutes / 60} h"
            else -> "$prefix ${minutes / 60} h ${minutes % 60} min"
        }
    }
}
