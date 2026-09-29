package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.ai.DayMode
import io.github.salex27.lumi.domain.ai.ReplyRequest
import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.assistant.ReplyLanguage
import io.github.salex27.lumi.domain.model.AgendaEvent
import io.github.salex27.lumi.domain.model.Task
import io.github.salex27.lumi.domain.model.TaskAICommand
import io.github.salex27.lumi.domain.model.TaskPriority
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.time.DueDateFormatter
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Prompts shared by every LLM engine (Gemini Nano, Gemma, Gemini cloud).
 * The data (which tasks, which plan) is always computed by code; the LLM only interprets and phrases.
 * That way a small on-device model can't invent tasks or dates.
 *
 * Instructions are in English (small models follow them best); users may speak Spanish or English.
 * The interpret prompt is kept short on purpose: on a phone every token of it is read (prefilled) for every sentence.
 */
object AssistantPrompts {

    val INTERPRET_SYSTEM = """
You turn one sentence from the user of a to-do app into ONE JSON object. The sentence may be in Spanish or English.
Reply ONLY with the JSON: no markdown, no ```, no extra text.

Fields (omit the ones you don't need):
{"action":"...","targetTitle":"...","newStatus":"TODO|IN_PROGRESS|COMPLETED|CANCELLED","category":"PERSONAL|WORK|STUDY|HEALTH|OTHER","dueDate":"YYYY-MM-DDTHH:MM or YYYY-MM-DD","hasTime":false,"description":null,"recurrence":"DAILY|WEEKDAYS|WEEKLY:MO,TH|MONTHLY:1|YEARLY","remindBeforeMinutes":[],"postponeMinutes":null,"meeting":null,"priority":"LOW|MEDIUM|HIGH","place":null,"placeOnArrive":true,"items":[],"newTitle":null}

Actions:
- CREATE: something to do or remember to do. targetTitle = the short task, infinitive, in the user's language, WITHOUT the date, time, place or "remind me". The title always goes in targetTitle.
- CREATE_MANY: SEVERAL different tasks ("comprar pan, llamar a Ana y acabar el informe"): one CREATE per task in "items".
- UPDATE_STATUS: finished (COMPLETED), started (IN_PROGRESS) or cancelled (CANCELLED) a task.
- RESCHEDULE: move/postpone/bring forward a task: dueDate (new date) or postponeMinutes (negative = earlier).
- SET_PRIORITY: change a task's priority. EDIT: change a task's title (newTitle), date, category, priority or note.
- For UPDATE_STATUS, RESCHEDULE, SET_PRIORITY and EDIT, targetTitle = the words that identify the task ("my task about taking Víctor to work" → "taking Víctor"). If they say "it"/"la"/"lo" and there is a LAST TASK in the context, use its title.
- RECALL: a question about their own things ("¿cuándo es lo del dentista?"). ASK: any other question or request (facts, maths, ideas, recipes, advice, translations). WEATHER: weather, rain, cold, heat, umbrella, jacket. For all three, targetTitle = the sentence as said. A question is NEVER a task.
- REMEMBER: ONLY when they explicitly ask you to remember a fact ("recuerda que…", "remember that…"). Telling something that happened and asking about it is ASK.
- NAVIGATE: go somewhere / how to get there; targetTitle = the destination. PLAN_DAY: what should I do, a plan. SUMMARIZE: a summary, how am I doing.
Other fields: dueDate from the CURRENT DATE given ("a las 5" / "at 5" with no context = 17:00); hasTime true only with an explicit time. priority HIGH for urgent/important, LOW for "no rush". place + placeOnArrive for "when I get home" (place "casa", true) / "when I leave work" (place "trabajo", false). remindBeforeMinutes for "remind me 2 hours before" → [120]. description ONLY if they dictate a note ("nota: …", "note: …"), copying their words.

Examples (current date Monday 2026-09-28 10:00):
"recuérdame llamar a mamá mañana a las 6" → {"action":"CREATE","targetTitle":"Llamar a mamá","category":"PERSONAL","dueDate":"2026-09-29T18:00","hasTime":true}
"remind me to call the bank tomorrow, it's urgent" → {"action":"CREATE","targetTitle":"Call the bank","category":"PERSONAL","dueDate":"2026-09-29","priority":"HIGH"}
"mañana tengo dentista a las 5" → {"action":"CREATE","targetTitle":"Dentista","category":"HEALTH","dueDate":"2026-09-29T17:00","hasTime":true}
"I have a haircut on Friday at 10" → {"action":"CREATE","targetTitle":"Haircut","category":"PERSONAL","dueDate":"2026-10-02T10:00","hasTime":true}
"cuando llegue a casa recuérdame sacar la basura" → {"action":"CREATE","targetTitle":"Sacar la basura","category":"PERSONAL","place":"casa","placeOnArrive":true}
"mañana comprar pan, llamar a Ana y acabar el informe" → {"action":"CREATE_MANY","items":[{"action":"CREATE","targetTitle":"Comprar pan","dueDate":"2026-09-29"},{"action":"CREATE","targetTitle":"Llamar a Ana","dueDate":"2026-09-29"},{"action":"CREATE","targetTitle":"Acabar el informe","category":"WORK","dueDate":"2026-09-29"}]}
"ya terminé la presentación" → {"action":"UPDATE_STATUS","targetTitle":"presentación","newStatus":"COMPLETED"}
"move the meeting to Thursday" → {"action":"RESCHEDULE","targetTitle":"meeting","dueDate":"2026-10-01"}
"pospón lo del dentista una semana" → {"action":"RESCHEDULE","targetTitle":"dentista","postponeMinutes":10080}
"edítame mi tarea de llevar a Víctor al trabajo y ponla para mañana" → {"action":"EDIT","targetTitle":"llevar a Víctor","dueDate":"2026-09-29"}
"he dejado las natillas fuera toda la noche, me las puedo comer" → {"action":"ASK","targetTitle":"he dejado las natillas fuera toda la noche, me las puedo comer"}
"do I need a jacket tonight?" → {"action":"WEATHER","targetTitle":"do I need a jacket tonight?"}
"¿qué hago hoy?" → {"action":"PLAN_DAY"}
""".trim()

    val REPLY_SYSTEM = """
You are Lumi, the user's personal assistant for organizing their day.
Personality: warm, clear, smart and brief (Apple/Revolut style: precise, no fluff).

You receive FACTS already computed by the app and write the reply to the user, in the language given in REPLY LANGUAGE.
Strict rules:
- Use ONLY the tasks, dates and numbers in the FACTS. Never invent tasks, times or data.
- At most 4 short sentences. Human and encouraging, not robotic.
- Address the user informally ("tú" in Spanish). No emojis (one at most if it adds something).
- Don't greet ("hola", "hi"): you are already talking. Except in a summary or a day plan, get straight to the point.
- Never mention internal data (English status names like TODO, upper-case categories, JSON or brackets).
- Keep in mind the weekday and time of day given.
- No markdown (no **, no #). For lists use lines starting with "1.", "2."...
""".trim()

    private val NOW_FMT = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH)

    private fun languageName(lang: Lang = ReplyLanguage.current) = if (lang == Lang.EN) "English" else "Spanish (Spain)"

    /** [context] = recent conversation note (see ConversationContext.promptNote), or empty. */
    fun interpretUser(prompt: String, now: LocalDateTime, context: String = ""): String =
        "CURRENT DATE: ${now.format(NOW_FMT)}\n" + (if (context.isNotBlank()) "$context\n" else "") + "SENTENCE: $prompt"

    fun replyUser(request: ReplyRequest): String {
        val now = request.now
        val header = "NOW: ${now.format(NOW_FMT)}\nREPLY LANGUAGE: ${languageName()}"
        val facts = when (request) {
            is ReplyRequest.TaskCreated ->
                "EVENT: the user just created a task.\nTASK: ${describe(request.task, now)}\n" +
                    "Confirm it naturally and briefly" + (if (request.task.dueAt != null) " and say you'll remind them beforehand." else ".")
            is ReplyRequest.TaskUpdated ->
                "EVENT: the user changed a task's status.\nTASK: ${describe(request.task, now)}\nConfirm with brief enthusiasm."
            is ReplyRequest.TaskRescheduled ->
                "EVENT: the user moved a task to another date.\nTASK: ${describe(request.task, now)}\nConfirm the new date in one sentence."
            is ReplyRequest.Recall -> buildString {
                append("EVENT: the user asks a question.\nQUESTION: ${request.question}\n")
                if (request.facts.isNotEmpty()) append("PERSONAL MEMORY: ${request.facts.joinToString(" | ")}\n")
                request.task?.let { append("RELATED TASK: ${describe(it, now)}\n") }
                append("Answer in one or two sentences using ONLY this data. If it isn't enough, say so.")
            }
            is ReplyRequest.PriorityChanged ->
                "EVENT: the user changed a task's priority.\nTASK: ${describe(request.task, now)}\nConfirm it in one sentence."
            is ReplyRequest.TasksCreated ->
                "EVENT: the user dictated several tasks at once.\nTASKS: ${list(request.tasks, now)}\nConfirm them in a short numbered list."
            is ReplyRequest.DayPlan -> buildString {
                val p = request.plan
                append("EVENT: the user asks what to do now.\n")
                append("MOMENT: ").append(
                    when (p.mode) {
                        DayMode.WEEKEND -> "weekend → favour personal life, health and rest"
                        DayMode.WORK_HOURS -> "weekday during work hours → favour work"
                        DayMode.AFTER_WORK -> "weekday outside work hours → favour personal things"
                    }
                ).append('\n')
                if (p.postponedWork > 0) append("WORK PARKED ON PURPOSE: ${p.postponedWork} tasks\n")
                append("OVERDUE: ").append(list(p.overdue, now)).append('\n')
                append("DUE TODAY: ").append(list(p.dueToday, now)).append('\n')
                append("SUGGESTIONS IN ORDER: ").append(list(p.suggestions, now)).append('\n')
                val upcoming = request.events.filter { it.allDay || it.end > System.currentTimeMillis() }
                request.freeSlot?.let { f ->
                    append("FREE TIME NOW: ${f.minutes} min")
                    f.suggestion?.let { append(", good for «${it.title}»") }
                    append('\n')
                }
                if (upcoming.isNotEmpty()) append("CALENDAR TODAY: ").append(upcoming.joinToString(" | ") { eventLine(it) }).append('\n')
                append("Write a short, encouraging plan. Mention overdue tasks first if any. End with a short question.")
            }
            is ReplyRequest.Messages -> buildString {
                append("EVENT: the user asks you to read their unread messages.\n")
                append("MESSAGES (app · conversation · sender: text):\n")
                request.messages.sortedBy { it.time }.takeLast(25).forEach { m ->
                    append("- ${m.app} · ${m.conversation} · ${m.sender}: ${m.text.take(200)}\n")
                }
                append("Summarize them to be read aloud in 2-4 sentences: who wrote and what they want, the important ones first. ")
                append("If someone asks or requests something, say so. Don't invent anything that isn't in the messages.")
            }
            is ReplyRequest.Briefing -> buildString {
                append("EVENT: the user asks for a summary of their situation.\n")
                append("TASKS: ").append(list(request.tasks, now)).append('\n')
                append("Write a human, interesting summary: progress, the most urgent thing, and one concrete tip for today.")
            }
        }
        return "$header\n$facts"
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    /** A calendar event in the reply language: «Sprint» a las 10:00 / «Sprint» at 10:00. */
    fun eventLine(e: AgendaEvent): String = if (e.allDay) "«${e.title}» (${ReplyLanguage.t("todo el día", "all day")})" else
        "«${e.title}» ${ReplyLanguage.t("a las", "at")} ${java.time.Instant.ofEpochMilli(e.begin).atZone(java.time.ZoneId.systemDefault()).format(HM)}"

    private fun list(tasks: List<Task>, now: LocalDateTime) =
        if (tasks.isEmpty()) "none" else tasks.joinToString(" | ") { describe(it, now) }

    /** A task as facts for the model. Dates are already phrased in the reply language so it can copy them. */
    private fun describe(t: Task, now: LocalDateTime) = buildString {
        val status = when (t.status) {
            TaskStatus.TODO -> "pending"
            TaskStatus.IN_PROGRESS -> "in progress"
            TaskStatus.COMPLETED -> "done"
            TaskStatus.CANCELLED -> "cancelled"
        }
        append("«${t.title}» [${t.category.label}, $status")
        t.dueAt?.let { append(", due ${DueDateFormatter.format(it, t.dueHasTime, now)}") }
        if (t.priority != TaskPriority.NONE) append(", priority ${t.priority.label.lowercase()}")
        t.placeTrigger?.let { append(", reminder ${it.describe()}") }
        append("]")
    }

    // ── Robust parsing of the LLM's JSON output ─────────────────────────────

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val VALID_ACTIONS = setOf(
        TaskAICommand.CREATE, TaskAICommand.CREATE_MANY, TaskAICommand.UPDATE_STATUS, TaskAICommand.RESCHEDULE, TaskAICommand.SET_PRIORITY, TaskAICommand.NAVIGATE,
        TaskAICommand.EDIT, TaskAICommand.RECALL, TaskAICommand.REMEMBER, TaskAICommand.WEATHER, TaskAICommand.ASK,
        TaskAICommand.SUMMARIZE, TaskAICommand.PLAN_DAY
    )

    /** Extracts the first JSON object from the reply (tolerates ```json and text around it), or null if invalid. */
    fun parseCommand(raw: String): TaskAICommand? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val cmd = runCatching { json.decodeFromString<TaskAICommand>(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        val action = cmd.action.uppercase().trim()
        if (action !in VALID_ACTIONS) return null
        return movedTitle(cmd.copy(action = action, dueDate = cmd.dueDate?.takeIf { it.isNotBlank() && it != "null" }))
    }

    /**
     * Gemma 4 E2B puts a new task's title in "newTitle" (the rename field) and leaves targetTitle empty: it used to be
     * lost and the rules' title was used instead ("Tengo dentista" instead of "Dentista").
     */
    private fun movedTitle(cmd: TaskAICommand): TaskAICommand {
        val fixed = if (cmd.action != TaskAICommand.EDIT && cmd.targetTitle.isNullOrBlank() && !cmd.newTitle.isNullOrBlank() && cmd.newTitle != "null")
            cmd.copy(targetTitle = cmd.newTitle, newTitle = null) else cmd
        return if (fixed.items.isEmpty()) fixed else fixed.copy(items = fixed.items.map { movedTitle(it.copy(action = it.action.uppercase().ifBlank { TaskAICommand.CREATE })) })
    }

    /** General questions ("how many calories in a banana?", "give me dinner ideas"). [history] = recent Q&A, for follow-ups. */
    fun generalSystem(now: LocalDateTime, context: List<String>, history: String = ""): String = buildString {
        append("You are Lumi, the user's personal assistant: warm and brief. Reply in ${languageName()}. ")
        append("Today is ${now.format(NOW_FMT)}.\n")
        append("Don't greet or introduce yourself: go straight to the answer. ")
        append("Answer in 1-3 sentences (or a short list of 3-5 points if they ask for ideas or steps). No markdown or emojis. ")
        append("Your answer may be read aloud: no tables or links.\n")
        append("Answer with what you know, like a friend who knows a bit of everything: cooking, health, home, food safety, ")
        append("practical questions, ideas, maths, languages. When there is a risk (food, health), give a clear, prudent answer.\n")
        append("Reply exactly NO_LO_SE only if it needs CURRENT data you can't know (news, results, today's prices, a place's ")
        append("opening hours) or a PRIVATE fact about the user that isn't in the context (their dentist's name, their wifi ")
        append("password). Telling something that happened and asking what to do is NOT a private fact: answer it.\n")
        append("Example: \"I left cooked chicken out of the fridge all night, can I eat it?\" → ")
        append("\"Better not: cooked food shouldn't be out of the fridge for more than 2 hours; bacteria multiply even if it doesn't smell bad.\"")
        if (context.isNotEmpty()) append("\nPERSONAL CONTEXT (use only if relevant): ${context.joinToString("; ")}")
        if (history.isNotBlank()) append("\n$history")
    }

    /** Cleans the conversational reply: removes markdown that small models sometimes sneak in. */
    fun cleanReply(raw: String): String? = raw
        .replace("**", "")
        .replace(Regex("(?m)^#+\\s*"), "")
        .trim()
        .takeIf { it.length >= 2 }
}
