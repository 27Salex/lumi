package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.assistant.DeviceCommand
import io.github.salex27.lumi.domain.model.Recurrence
import io.github.salex27.lumi.domain.model.TaskAICommand
import io.github.salex27.lumi.domain.model.TaskPriority
import io.github.salex27.lumi.domain.model.TaskStatus
import io.github.salex27.lumi.domain.weather.PartOfDay
import io.github.salex27.lumi.domain.weather.WeatherQuery
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * English understanding for the rules engine (pure, tested). The English twin of the Spanish parsers in
 * [RuleBasedEngine], [AssistantIntents], [TaskPhraseParser] and `DeviceCommandParser`: it always returns a command
 * (CREATE when nothing else matches), so Lumi works in English even without an AI model.
 */
object EnglishCommands {

    private const val I = "(?i)"

    fun parse(input: String, now: LocalDateTime): TaskAICommand {
        val text = input.trim().trimEnd('.', '!')
        if (text.isBlank()) return TaskAICommand(action = TaskAICommand.PLAN_DAY)
        val lower = text.lowercase()

        AssistantIntents.nearby(text)?.let { return it }
        followUp(text, now)?.let { return it }
        navigate(text)?.let { return it }
        edit(text, now)?.let { return it }
        device(text)?.let { return TaskAICommand(action = TaskAICommand.DEVICE, device = it.serialize()) }

        if (isWeather(text)) return TaskAICommand(action = TaskAICommand.WEATHER, targetTitle = text)
        dayBrief(text, now)?.let { return TaskAICommand(action = TaskAICommand.DAY_BRIEF, dueDate = it.toString()) }
        if (SMART_ALARM.containsMatchIn(lower)) {
            return TaskAICommand(action = TaskAICommand.SMART_ALARM, newStatus = if (lower.endsWith("?") || lower.startsWith("what time")) "ASK" else null)
        }
        notifications(text)?.let { return TaskAICommand(action = TaskAICommand.NOTIFICATIONS, targetTitle = it) }
        if (PLAN.containsMatchIn(lower)) return TaskAICommand(action = TaskAICommand.PLAN_DAY)
        if (SUMMARY.containsMatchIn(lower)) return TaskAICommand(action = TaskAICommand.SUMMARIZE)
        if (GENERAL_ASK.containsMatchIn(lower) || QuickMath.answer(text) != null) return TaskAICommand(action = TaskAICommand.ASK, targetTitle = text)

        memory(text, now)?.let { return it }
        priority(text)?.let { return it }
        reschedule(text, now)?.let { return it }
        status(text)?.let { return it }

        // A question is never a task: memory → tasks → general knowledge
        if (QUESTION.containsMatchIn(text)) return TaskAICommand(action = TaskAICommand.RECALL, targetTitle = text)

        shoppingList(text)?.let { return it }

        val chunks = splitList(text)
        if (chunks.size > 1) {
            val shared = EnglishDateParser.parse(text, now)
            return TaskAICommand(action = TaskAICommand.CREATE_MANY, items = chunks.map { chunk ->
                val item = create(chunk, now)
                if (item.dueDate == null && shared != null) item.copy(dueDate = shared.isoDate(), hasTime = shared.hasTime) else item
            })
        }
        return create(text, now)
    }

    // ── Follow-ups about the last task ("move it to 5pm") ─────────────────────

    private fun followUp(text: String, now: LocalDateTime): TaskAICommand? {
        // Fillers people put before a correction: "actually, move it to Friday", "no wait, make it urgent"
        val t = text.trim().trimEnd('.', '!').replace(Regex("$I^(?:(?:actually|oh|no|wait|ok|okay|sorry|hmm|please|and|so)[,\\s]+)+"), "")
        Regex("$I^(?:and\\s+)?(?:move|push|reschedule|change|set)\\s+it\\s+(?:to\\s+|for\\s+)?(.+)$").find(t)?.let { m ->
            val date = EnglishDateParser.parse(m.groupValues[1], now) ?: return@let
            return TaskAICommand(action = TaskAICommand.RESCHEDULE, refersToLast = true, dueDate = date.isoDate(), hasTime = date.hasTime)
        }
        Regex("$I^(?:and\\s+)?(?:postpone|delay|push\\s+back|bring\\s+forward|move\\s+up)\\s+it\\s+(?:by\\s+|for\\s+)?(.+)$").find(t)?.let { m ->
            val minutes = amountMinutes(m.groupValues[1]) ?: return@let
            val sign = if (Regex("$I^(?:and\\s+)?(?:bring\\s+forward|move\\s+up)").containsMatchIn(t)) -1 else 1
            return TaskAICommand(action = TaskAICommand.RESCHEDULE, refersToLast = true, postponeMinutes = minutes * sign)
        }
        Regex("$I^(?:and\\s+)?(?:make|mark|set)\\s+it\\s+(?:as\\s+)?(?:a\\s+)?(urgent|important|high|medium|low|normal|no)(?:\\s+priority)?$").find(t)?.let { m ->
            return TaskAICommand(action = TaskAICommand.SET_PRIORITY, refersToLast = true, priority = priorityWord(m.groupValues[1])?.name)
        }
        if (Regex("$I^(?:and\\s+)?(?:mark\\s+it\\s+(?:as\\s+)?(?:done|complete|completed|finished)|it'?s\\s+done|i\\s+(?:did|finished|completed)\\s+it|done)$").matches(t)) {
            return TaskAICommand(action = TaskAICommand.UPDATE_STATUS, refersToLast = true, newStatus = TaskStatus.COMPLETED.name)
        }
        if (Regex("$I^(?:and\\s+)?(?:delete|cancel|remove)\\s+it$").matches(t)) {
            return TaskAICommand(action = TaskAICommand.UPDATE_STATUS, refersToLast = true, newStatus = TaskStatus.CANCELLED.name)
        }
        Regex("$I^(?:and\\s+)?(?:rename|call)\\s+it\\s+(?:to\\s+)?(.+)$").find(t)?.let { m ->
            return TaskAICommand(action = TaskAICommand.EDIT, refersToLast = true, newTitle = m.groupValues[1].trim().replaceFirstChar { it.uppercase() })
        }
        Regex("$I^(?:and\\s+)?add\\s+(?:a\\s+)?note(?:\\s+to\\s+it)?\\s*[:,]?\\s*(.+)$").find(t)?.let { m ->
            return TaskAICommand(action = TaskAICommand.EDIT, refersToLast = true, description = m.groupValues[1].trim().replaceFirstChar { it.uppercase() })
        }
        return null
    }

    // ── Routes, editing and phone actions ──────────────────────────────────────

    private fun navigate(text: String): TaskAICommand? {
        val m = Regex("$I^(?:take\\s+me|navigate|get\\s+me|directions|drive\\s+me|how\\s+(?:do|can)\\s+i\\s+get|route)\\s+(?:to\\s+)?(.+?)\\??$").find(text) ?: return null
        val dest = m.groupValues[1].trim().replace(Regex("$I^(?:the|my)\\s+"), "")
        if (dest.isBlank()) return null
        return TaskAICommand(action = TaskAICommand.NAVIGATE, targetTitle = placeKey(dest))
    }

    private fun edit(text: String, now: LocalDateTime): TaskAICommand? {
        Regex("$I^rename\\s+(?:the\\s+task\\s+|my\\s+task\\s+)?(.+?)\\s+(?:to|as)\\s+(.+)$").find(text)?.let { m ->
            val spec = text.substring(m.groups[1]!!.range.first)
            return TaskAICommand(action = TaskAICommand.EDIT, targetTitle = cleanTarget(m.groupValues[1]), newTitle = m.groupValues[2].trim().replaceFirstChar { it.uppercase() }, renameSpec = spec)
        }
        Regex("$I^add\\s+(?:a\\s+)?note\\s+to\\s+(.+?)\\s*[:,]\\s*(.+)$").find(text)?.let { m ->
            return TaskAICommand(action = TaskAICommand.EDIT, targetTitle = cleanTarget(m.groupValues[1]), description = m.groupValues[2].trim().replaceFirstChar { it.uppercase() })
        }
        Regex("$I^(?:move|put)\\s+(.+?)\\s+(?:to|in|into)\\s+(?:the\\s+)?(work|personal|study|health|other)(?:\\s+(?:area|category))?$").find(text)?.let { m ->
            val category = CategoryHeuristics.fromWord(m.groupValues[2]) ?: return@let
            return TaskAICommand(action = TaskAICommand.EDIT, targetTitle = cleanTarget(m.groupValues[1]), category = category.name)
        }
        val m = Regex("$I^(?:edit|open|change|update)\\s+(?:the\\s+task\\s+|my\\s+task\\s+)(?:called\\s+|about\\s+|named\\s+)?(.+)$").find(text) ?: return null
        return TaskAICommand(action = TaskAICommand.EDIT, targetTitle = cleanTarget(m.groupValues[1]))
    }

    /** "Remind me to text Ana tomorrow" is a TASK for later, not a send now. */
    private val LATER = Regex(
        "$I^(?:remind|don'?t\\s+(?:let\\s+me\\s+)?forget|i\\s+(?:need|have)\\s+to|add|note)|" +
            "\\b(?:tomorrow|later|tonight|next\\s+week|on\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|at\\s+\\d|when\\s+i\\s+(?:get|arrive|leave))\\b"
    )

    fun device(text: String): DeviceCommand? {
        val t = text.trim().trimEnd('?', '.', '!')
        // Timer: "set a timer for 10 minutes", "timer 5 minutes", "remind me in 20 minutes"
        Regex("$I^(?:set\\s+|start\\s+)?(?:a\\s+)?timer\\s+(?:for\\s+)?(\\d+|a|an|one|two|three|five|ten|fifteen|twenty|thirty)\\s+(seconds?|secs?|minutes?|mins?|hours?)(?:\\s+(?:for|to)\\s+(.+))?$").find(t)?.let { m ->
            val n = number(m.groupValues[1]) ?: return@let
            val unit = m.groupValues[2].lowercase()
            val seconds = when { unit.startsWith("s") -> n; unit.startsWith("h") -> n * 3600; else -> n * 60 }
            return DeviceCommand.Timer(seconds, m.groupValues[3].ifBlank { null })
        }
        // Alarm with an explicit time: "set an alarm for 7am", "wake me up at 6:30"
        Regex("$I^(?:set\\s+(?:an?\\s+|my\\s+)?alarm|wake\\s+me(?:\\s+up)?)\\s+(?:for|at)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?:\\s+(?:tomorrow|in\\s+the\\s+morning))?(?:\\s+(?:for|to)\\s+(.+))?$").find(t)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val s = m.groupValues[3].lowercase().replace(".", "")
            if (s == "pm" && h < 12) h += 12
            if (s == "am" && h == 12) h = 0
            if (h !in 0..23 || min !in 0..59) return@let
            return DeviceCommand.Alarm(h, min, m.groupValues[4].ifBlank { null })
        }
        if (Regex("$I^turn\\s+on\\s+(?:the\\s+)?(?:flashlight|torch)|^(?:flashlight|torch)\\s+on$").containsMatchIn(t)) return DeviceCommand.Flashlight(true)
        if (Regex("$I^turn\\s+off\\s+(?:the\\s+)?(?:flashlight|torch)|^(?:flashlight|torch)\\s+off$").containsMatchIn(t)) return DeviceCommand.Flashlight(false)
        if (Regex("$I^(?:turn\\s+on|enable|activate|start)\\s+(?:do\\s+not\\s+disturb|dnd|silent\\s+mode)$|^(?:silence|mute)\\s+(?:my\\s+)?phone$").containsMatchIn(t)) return DeviceCommand.DoNotDisturb(true)
        if (Regex("$I^(?:turn\\s+off|disable|deactivate|stop)\\s+(?:do\\s+not\\s+disturb|dnd|silent\\s+mode)$").containsMatchIn(t)) return DeviceCommand.DoNotDisturb(false)
        Regex("$I^(?:open|show)\\s+(?:the\\s+)?(wi-?fi|bluetooth|volume|settings)(?:\\s+settings)?$").find(t)?.let { m ->
            return DeviceCommand.OpenSettings(when (m.groupValues[1].lowercase().replace("-", "")) {
                "wifi" -> DeviceCommand.SettingsPanel.WIFI
                "bluetooth" -> DeviceCommand.SettingsPanel.BLUETOOTH
                "volume" -> DeviceCommand.SettingsPanel.VOLUME
                else -> DeviceCommand.SettingsPanel.GENERAL
            })
        }
        message(t)?.let { return it }
        // Call: imperative only, and not for later ("call the bank tomorrow" is a task)
        Regex("$I^(?:call|phone|ring|dial)\\s+(.+)$").find(t)?.let { m ->
            if (LATER.containsMatchIn(t)) return null
            return DeviceCommand.Call(m.groupValues[1].trim().replace(Regex("$I^my\\s+"), "my "))
        }
        Regex("$I^(?:play|put\\s+on)\\s+(?:some\\s+)?(?:music\\s+by\\s+|songs?\\s+by\\s+|the\\s+song\\s+|the\\s+album\\s+)?(.+?)(?:\\s+on\\s+(spotify|youtube\\s+music|youtube|amazon\\s+music|deezer|apple\\s+music))?$").find(t)?.let { m ->
            val q = m.groupValues[1].trim()
            return DeviceCommand.PlayMusic(if (q.equals("music", true) || q.equals("some music", true)) "" else q, m.groupValues[2].ifBlank { null })
        }
        Regex("$I^(?:search\\s+(?:for\\s+|the\\s+web\\s+for\\s+)?|google\\s+|look\\s+up\\s+)(.+)$").find(t)?.let { return DeviceCommand.WebSearch(it.groupValues[1].trim()) }
        Regex("$I^(?:open|launch|start)\\s+(?:the\\s+)?(?:app\\s+)?(.+?)(?:\\s+app)?$").find(t)?.let { m ->
            if (Regex("$I^(?:my\\s+)?task").containsMatchIn(m.groupValues[1])) return null
            return DeviceCommand.OpenApp(m.groupValues[1].trim())
        }
        return null
    }

    private const val CHANNEL = "(?:whats\\s*app|a\\s+whatsapp|a\\s+message|a\\s+text|an\\s+sms|a\\s+text\\s+message)"

    /** "text Ana saying I'm late", "send a WhatsApp to mom that I'm on my way", "tell Víctor I'll be there in 10". */
    private fun message(t: String): DeviceCommand? {
        if (LATER.containsMatchIn(t)) return null
        val sms = Regex("$I\\b(?:sms|text\\s+message)\\b").containsMatchIn(t)
        fun clean(s: String) = s.trim().removePrefix("that ").trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        Regex("$I^(?:send|write)\\s+$CHANNEL\\s+to\\s+(.+?)(?:\\s+(?:saying|that|:)\\s*(.+))?$").find(t)?.let { m ->
            return DeviceCommand.Message(m.groupValues[1].trim(), clean(m.groupValues[2]), !sms)
        }
        Regex("$I^(?:text|message|whatsapp)\\s+(.+?)(?:\\s+(?:saying|that|:)\\s*(.+))?$").find(t)?.let { m ->
            return DeviceCommand.Message(m.groupValues[1].trim(), clean(m.groupValues[2]), !sms)
        }
        Regex("$I^tell\\s+(?!me\\b)(.+?)\\s+(?:on\\s+whatsapp\\s+)?(?:that\\s+)(.+)$").find(t)?.let { m ->
            return DeviceCommand.Message(m.groupValues[1].trim(), clean(m.groupValues[2]), true)
        }
        return null
    }

    // ── Weather, day summary, alarm, messages ──────────────────────────────────

    private val WEATHER_TOPIC = Regex(
        "$I\\b(?:weather|forecast|rain(?:ing|y)?|umbrella|raincoat|jacket|coat|sweater|cold|hot|warm|temperature|degrees|snow(?:ing)?|storm|sunny|cloudy)\\b"
    )
    private val WEATHER_ASK = Regex("$I\\?\\s*$|^(?:what|how|is|will|do|does|should|am|are|any|weather|forecast|tell\\s+me)\\b")
    private val TASKY = Regex("$I^(?:remind|add|buy|bring|take|pack|get|i\\s+(?:need|have)\\s+to)\\b")

    fun isWeather(text: String): Boolean {
        val t = text.trim()
        return WEATHER_TOPIC.containsMatchIn(t) && WEATHER_ASK.containsMatchIn(t) && !TASKY.containsMatchIn(t)
    }

    /** "Will it rain tomorrow afternoon in London?" → tomorrow, afternoon, rain, London. */
    fun weatherQuery(text: String, now: LocalDateTime): WeatherQuery {
        var t = text.trim().trimEnd('?', '.', '!')
        val part = when {
            Regex("$I\\b(?:this\\s+|in\\s+the\\s+|tomorrow\\s+)morning\\b").containsMatchIn(t) -> PartOfDay.MORNING
            Regex("$I\\b(?:this\\s+|in\\s+the\\s+|tomorrow\\s+)afternoon\\b").containsMatchIn(t) -> PartOfDay.AFTERNOON
            Regex("$I\\btonight\\b|\\b(?:this|tomorrow)\\s+(?:evening|night)\\b|\\bin\\s+the\\s+evening\\b").containsMatchIn(t) -> PartOfDay.NIGHT
            else -> null
        }
        t = t.replace(Regex("$I\\b(?:this|in\\s+the)\\s+(?:morning|afternoon|evening)\\b|\\btonight\\b"), " ")
        val date = EnglishDateParser.parse(t, now)?.dateTime?.toLocalDate()?.takeIf { !it.isBefore(now.toLocalDate()) }
            ?: if (Regex("$I\\bweekend\\b").containsMatchIn(t)) nextSaturday(now.toLocalDate()) else now.toLocalDate()
        val topic = when {
            Regex("$I\\bsnow").containsMatchIn(t) -> WeatherQuery.Topic.SNOW
            Regex("$I\\brain|\\bumbrella|\\braincoat|\\bstorm").containsMatchIn(t) -> WeatherQuery.Topic.RAIN
            Regex("$I\\bcold\\b|\\bjacket|\\bcoat\\b|\\bsweater").containsMatchIn(t) -> WeatherQuery.Topic.COLD
            Regex("$I\\bhot\\b|\\bwarm\\b").containsMatchIn(t) -> WeatherQuery.Topic.HEAT
            else -> WeatherQuery.Topic.GENERAL
        }
        val place = Regex("$I\\bin\\s+([A-Za-zÀ-ÿ][A-Za-zÀ-ÿ .'-]{1,40}?)\\s*$").find(t.trim())?.groupValues?.get(1)?.trim()
            ?.replace(Regex("$I\\s+(?:today|tomorrow|on\\s+\\w+|this\\s+\\w+|next\\s+\\w+)$"), "")
            ?.takeIf { it.isNotBlank() && !Regex("$I^(?:the\\s+(?:morning|afternoon|evening)|general)$").matches(it) }
        return WeatherQuery(date, part, topic, place)
    }

    private fun nextSaturday(today: LocalDate): LocalDate {
        var d = today
        while (d.dayOfWeek != DayOfWeek.SATURDAY) d = d.plusDays(1)
        return d
    }

    private val DAY_BRIEF = Regex(
        "$I^(?:what\\s+do\\s+i\\s+have\\s+(?:on\\s+)?(?:today|tomorrow|for\\s+today|for\\s+tomorrow|on\\s+\\w+day)|what'?s\\s+(?:on\\s+)?(?:my\\s+)?(?:day|schedule|agenda|calendar)(?:\\s+(?:like\\s+)?(?:today|tomorrow))?|" +
            "what'?s\\s+my\\s+day\\s+like|how\\s+does\\s+(?:my\\s+day|today|tomorrow)\\s+look|brief\\s+me|morning\\s+briefing|daily\\s+briefing|summary\\s+of\\s+(?:today|tomorrow))\\??$"
    )

    fun dayBrief(text: String, now: LocalDateTime): LocalDate? {
        val t = text.trim()
        if (!DAY_BRIEF.containsMatchIn(t)) return null
        return EnglishDateParser.parse(t, now)?.dateTime?.toLocalDate() ?: now.toLocalDate()
    }

    private val SMART_ALARM = Regex(
        "(?i)^(?:smart\\s+alarm|set\\s+(?:my|the|an?)\\s+alarm(?:\\s+for\\s+tomorrow)?|wake\\s+me(?:\\s+up)?(?:\\s+tomorrow)?(?:\\s+in\\s+time)?|" +
            "what\\s+time\\s+should\\s+i\\s+(?:wake\\s+up|get\\s+up|set\\s+(?:my|the)\\s+alarm)(?:\\s+tomorrow)?)\\??$"
    )

    fun notifications(text: String): String? {
        val t = text.trim().trimEnd('?', '.', '!')
        val any = Regex(
            "$I^(?:read\\s+(?:me\\s+)?(?:my\\s+)?(?:new\\s+|latest\\s+|unread\\s+)?(?:messages|notifications|texts|whatsapps|emails)|" +
                "(?:do\\s+i\\s+have|are\\s+there|any)\\s+(?:new\\s+|unread\\s+)?(?:messages|notifications|texts|emails)|" +
                "who\\s+(?:texted|messaged|wrote\\s+to|called)\\s+me|did\\s+(?:anyone|anybody)\\s+(?:text|message|write)|what\\s+did\\s+.+?\\s+(?:say|send|write))"
        )
        if (!any.containsMatchIn(t)) return null
        val who = Regex("$I^what\\s+did\\s+(.+?)\\s+(?:say|send|write)").find(t)?.groupValues?.get(1)
            ?: Regex("$I\\b(?:messages|texts|emails)\\s+from\\s+(.+)$").find(t)?.groupValues?.get(1)
        return who?.trim()?.replace(Regex("$I^(?:my|the)\\s+"), "").orEmpty()
    }

    // ── Plan, summary, general questions, memory ───────────────────────────────

    private val PLAN = Regex("(?i)^what\\s+(?:should|can|could)\\s+i\\s+do|^plan\\s+my\\s+day|^what'?s\\s+next|^where\\s+(?:do|should)\\s+i\\s+start|^what\\s+do\\s+you\\s+recommend|^organi[sz]e\\s+my\\s+day")
    private val SUMMARY = Regex("(?i)^how\\s+am\\s+i\\s+doing|^(?:give\\s+me\\s+a\\s+)?summary\\b|^summari[sz]e|^(?:show\\s+|list\\s+)?my\\s+tasks\\b|^what'?s\\s+pending|^what\\s+do\\s+i\\s+have\\s+pending")
    private val GENERAL_ASK = Regex(
        "(?i)^(?:give\\s+me\\s+(?:some\\s+)?(?:ideas?|tips?|advice|a\\s+recipe|a\\s+fact)|tell\\s+me\\s+(?:a|an|about|how|why|what)|explain|translate|" +
            "help\\s+me\\s+(?:with|to)|write\\s+me\\s+an?|how\\s+(?:do|can|should)\\s+(?:i|you)\\s+(?!get\\s+to)|how\\s+to\\b|why\\b|" +
            "what\\s+(?:is|are|does|was|were)\\s+(?:a|an|the)\\b|who\\s+(?:is|was|wrote|invented|won)\\b|how\\s+(?:much|many|long|far|old)\\b)"
    )
    private val QUESTION = Regex("(?i)\\?\\s*$|^(?:what|when|where|who|which|how|do|does|did|is|are|can|could|should|will|would)\\b")

    private fun memory(text: String, now: LocalDateTime): TaskAICommand? {
        Regex("$I^forget\\s+(?:that\\s+|about\\s+)(.+)$").find(text)?.let { return TaskAICommand(action = TaskAICommand.FORGET, targetTitle = it.groupValues[1].trim()) }
        val fact = Regex("$I^(?:hey\\s+)?(?:please\\s+)?(?:i\\s+(?:need|have|want)\\s+to\\s+|don'?t\\s+(?:let\\s+me\\s+)?forget\\s+|you\\s+should\\s+)?(?:remember|keep\\s+in\\s+mind|note)?\\s*(?:that\\s+)(.+)$").takeIf { AssistantIntents.asksToRemember(text) }?.find(text)?.groupValues?.get(1)?.trim()
            ?: text.takeIf { Regex("$I^my\\s+[\\p{L} ]{2,40}?\\s+(?:is|are|lives\\s+in|is\\s+called)\\s+.+$").matches(it.trim()) && !QUESTION.containsMatchIn(it) }?.trim()
            ?: return null
        val looksLikeTask = Regex("$I\\b(?:i\\s+(?:need|have)\\s+to|must|should)\\b").containsMatchIn(fact) || EnglishDateParser.parse(fact, now) != null
        if (looksLikeTask) return create(fact, now)
        return TaskAICommand(action = TaskAICommand.REMEMBER, targetTitle = fact.trimEnd('.').replaceFirstChar { it.uppercase() })
    }

    // ── Changing existing tasks ────────────────────────────────────────────────

    private fun priority(text: String): TaskAICommand? {
        Regex("$I^(?:make|mark|set)\\s+(.+?)\\s+(?:as\\s+)?(?:a\\s+)?(urgent|important|high|medium|low|normal|no)(?:\\s+priority)?$").find(text)?.let { m ->
            if (m.groupValues[1].equals("it", true)) return null
            return TaskAICommand(action = TaskAICommand.SET_PRIORITY, targetTitle = cleanTarget(m.groupValues[1]), priority = priorityWord(m.groupValues[2])?.name)
        }
        Regex("$I^prioriti[sz]e\\s+(.+)$").find(text)?.let { m ->
            return TaskAICommand(action = TaskAICommand.SET_PRIORITY, targetTitle = cleanTarget(m.groupValues[1]), priority = TaskPriority.HIGH.name)
        }
        return null
    }

    private fun reschedule(text: String, now: LocalDateTime): TaskAICommand? {
        val m = Regex("$I^(move|reschedule|push|postpone|delay|bring\\s+forward|push\\s+back)\\s+(.+)$").find(text) ?: return null
        val verb = m.groupValues[1].lowercase()
        val rest = m.groupValues[2].trim()
        Regex("$I\\s+(?:by|for)\\s+(.+)$").find(rest)?.let { r ->
            val minutes = amountMinutes(r.groupValues[1]) ?: return@let
            val sign = if (verb.startsWith("bring")) -1 else 1
            return TaskAICommand(action = TaskAICommand.RESCHEDULE, targetTitle = cleanTarget(rest.substring(0, r.range.first)), postponeMinutes = minutes * sign)
        }
        val date = EnglishDateParser.parse(rest, now) ?: return null
        return TaskAICommand(action = TaskAICommand.RESCHEDULE, targetTitle = cleanTarget(date.remainingText.replace(Regex("$I\\s+to$"), "")), dueDate = date.isoDate(), hasTime = date.hasTime)
    }

    private fun status(text: String): TaskAICommand? {
        val t = text.trim()
        fun cmd(title: String, s: TaskStatus) = TaskAICommand(action = TaskAICommand.UPDATE_STATUS, targetTitle = cleanTarget(title).replaceFirstChar { it.uppercase() }, newStatus = s.name)
        Regex("$I^(?:i(?:'ve|\\s+have)?\\s+(?:just\\s+)?(?:finished|completed|done)|done\\s+with|mark\\s+(.+?)\\s+(?:as\\s+)?(?:done|complete|completed|finished))\\s*(.*)$").find(t)?.let { m ->
            return cmd(m.groupValues[1].ifBlank { m.groupValues[2] }, TaskStatus.COMPLETED)
        }
        Regex("$I^(.+?)\\s+is\\s+(?:done|finished|complete)$").find(t)?.let { return cmd(it.groupValues[1], TaskStatus.COMPLETED) }
        Regex("$I^(?:i'?m\\s+(?:working\\s+on|starting|doing)|start(?:ing)?|begin)\\s+(.+)$").find(t)?.let { return cmd(it.groupValues[1], TaskStatus.IN_PROGRESS) }
        Regex("$I^(?:cancel|delete|remove|drop)\\s+(?:the\\s+task\\s+)?(.+)$").find(t)?.let { return cmd(it.groupValues[1], TaskStatus.CANCELLED) }
        return null
    }

    // ── Creating tasks ─────────────────────────────────────────────────────────

    private val CREATE_PREFIX = Regex(
        "$I^(?:(?:please|hey\\s+lumi|lumi)\\s*,?\\s+)?(?:remind\\s+me\\s+(?:to\\s+)?|don'?t\\s+let\\s+me\\s+forget\\s+(?:to\\s+)?|i\\s+(?:need|have|must|should)\\s+(?:to\\s+)?|" +
            "(?:add|create|make)\\s+(?:a\\s+|the\\s+)?(?:new\\s+)?(?:task|to-?do|reminder)\\s*(?:called|named|to|:)?\\s*|(?:add|note|new\\s+task|to-?do)\\s*:?\\s+)"
    )

    fun create(text: String, now: LocalDateTime): TaskAICommand {
        var rest = text.trim()
        var description: String? = null
        Regex("$I[\\s,;.]+(?:note|notes|details)\\s*:\\s*(.+)$").find(rest)?.let { m ->
            description = m.groupValues[1].trim().trimEnd('.').replaceFirstChar { it.uppercase() }
            rest = rest.removeRange(m.range).trim()
        }
        val reminders = mutableListOf<Int>()
        Regex("$I,?\\s*(?:and\\s+)?remind\\s+me\\s+(\\d+|a|an|one|two|half\\s+an)\\s+(minutes?|mins?|hours?|days?)\\s+before").find(rest)?.let { m ->
            amountMinutes("${m.groupValues[1]} ${m.groupValues[2]}")?.let { reminders += it }
            rest = rest.removeRange(m.range).trim()
        }
        var priority: TaskPriority? = null
        Regex("$I,?\\s*(?:it'?s\\s+|this\\s+is\\s+|as\\s+)?(?:urgent|very\\s+important|important|asap|high\\s+priority|top\\s+priority|no\\s+rush|low\\s+priority|medium\\s+priority)\\b").find(rest)?.let { m ->
            val w = m.value.lowercase()
            priority = when {
                w.contains("no rush") || w.contains("low") -> TaskPriority.LOW
                w.contains("medium") -> TaskPriority.MEDIUM
                else -> TaskPriority.HIGH
            }
            rest = rest.removeRange(m.range).trim()
        }
        var place: String? = null
        var onArrive = true
        Regex("$I,?\\s*(?:for\\s+)?when\\s+i\\s+(get|arrive|come|go|leave|am|'m)\\s+(?:back\\s+)?(?:to\\s+|at\\s+|in\\s+|from\\s+)?(?:the\\s+|my\\s+)?(home|work|the\\s+office|office|gym|university|school|supermarket|[\\p{L}]+)").find(rest)?.let { m ->
            onArrive = !m.groupValues[1].equals("leave", true)
            place = placeKey(m.groupValues[2])
            rest = rest.removeRange(m.range).trim()
        }
        var recurrence: Recurrence? = null
        Regex("$I\\b(?:every\\s+day|daily|every\\s+weekday|on\\s+weekdays|weekdays|every\\s+week|weekly|every\\s+month|monthly|every\\s+year|yearly|every\\s+((?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?:\\s*(?:,|and)\\s*(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))*))\\b").find(rest)?.let { m ->
            val w = m.value.lowercase()
            recurrence = when {
                m.groupValues[1].isNotBlank() -> Recurrence(Recurrence.Frequency.WEEKLY, Regex("(?i)monday|tuesday|wednesday|thursday|friday|saturday|sunday").findAll(m.groupValues[1]).map { DayOfWeek.valueOf(it.value.uppercase()) }.toSet())
                w.contains("weekday") -> Recurrence(Recurrence.Frequency.WEEKDAYS)
                w.contains("day") || w == "daily" -> Recurrence(Recurrence.Frequency.DAILY)
                w.contains("week") -> Recurrence(Recurrence.Frequency.WEEKLY)
                w.contains("month") -> Recurrence(Recurrence.Frequency.MONTHLY, dayOfMonth = now.dayOfMonth)
                else -> Recurrence(Recurrence.Frequency.YEARLY)
            }
            rest = rest.removeRange(m.range).trim()
        }
        val date = EnglishDateParser.parse(rest, now)
        val withoutDate = date?.remainingText ?: rest
        val title = withoutDate.replace(CREATE_PREFIX, "").trim().trimEnd('.', ',')
            .ifBlank { withoutDate }.replaceFirstChar { it.uppercase() }
        val due = date?.isoDate() ?: recurrence?.let { r ->
            val first = r.firstOnOrAfter(now.toLocalDate())
            first.toString()
        }
        return TaskAICommand(
            action = TaskAICommand.CREATE,
            targetTitle = TaskPhraseParser.cleanTitle(title),
            newStatus = TaskStatus.TODO.name,
            category = CategoryHeuristics.infer(text)?.name,
            dueDate = due,
            hasTime = date?.hasTime ?: false,
            description = description,
            recurrence = recurrence?.serialize(),
            remindBeforeMinutes = reminders,
            priority = priority?.name,
            place = place,
            placeOnArrive = onArrive
        )
    }

    /** "buy milk, call Ana and finish the report" → 3 chunks, only when each chunk starts with a verb. */
    private val LIST_VERBS = setOf(
        "buy", "call", "send", "finish", "pay", "book", "clean", "pick", "take", "write", "read", "email", "fix", "prepare",
        "check", "get", "go", "make", "do", "cook", "study", "walk", "visit", "order", "return", "wash", "text", "print", "review"
    )

    /** "add milk, bread and coffee to my shopping list" → one "Buy …" task per item. */
    private fun shoppingList(text: String): TaskAICommand? {
        val m = Regex("$I^(?:please\\s+)?(?:add|put)\\s+(.+?)\\s+(?:to|on)\\s+(?:my|the)\\s+(?:shopping|grocery|groceries)\\s+list\\.?$").find(text.trim()) ?: return null
        val items = m.groupValues[1].split(Regex("$I\\s*(?:,|\\s+and\\s+)\\s*")).map { it.trim().removePrefix("some ") }.filter { it.isNotBlank() }
        val tasks = items.map { TaskAICommand(action = TaskAICommand.CREATE, targetTitle = "Buy $it", category = "PERSONAL") }
        return if (tasks.size == 1) tasks.first() else TaskAICommand(action = TaskAICommand.CREATE_MANY, items = tasks)
    }

    private fun splitList(text: String): List<String> {
        val body = text.replace(CREATE_PREFIX, "")
        val parts = body.split(Regex("$I\\s*(?:,|;|\\s+and\\s+|\\s+then\\s+|\\s+also\\s+)\\s*")).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 2) return listOf(text)
        return if (parts.all { it.substringBefore(' ').lowercase() in LIST_VERBS }) parts else listOf(text)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** English place → saved-place key ("home" → "casa", "work"/"the office" → "trabajo"). */
    fun placeKey(name: String): String = when (val n = name.lowercase().trim().removePrefix("the ").removePrefix("my ")) {
        "home", "house" -> "casa"
        "work", "office", "job" -> "trabajo"
        "gym" -> "gimnasio"
        "university", "uni", "college" -> "universidad"
        "school" -> "colegio"
        "supermarket" -> "supermercado"
        else -> TaskPhraseParser.normalizePlace(n)
    }

    fun priorityWord(w: String): TaskPriority? = when (w.lowercase()) {
        "urgent", "important", "high", "top" -> TaskPriority.HIGH
        "medium", "normal" -> TaskPriority.MEDIUM
        "low" -> TaskPriority.LOW
        "no", "none" -> TaskPriority.NONE
        else -> null
    }

    private fun number(s: String): Int? = s.toIntOrNull() ?: mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30
    )[s.lowercase()]

    /** "2 hours" → 120, "a week" → 10080, "30 minutes" → 30, "half an hour" → 30. */
    fun amountMinutes(s: String): Int? {
        val t = s.lowercase().trim()
        if (t.startsWith("half an hour")) return 30
        val m = Regex("^(\\d+|a|an|one|two|three|five|ten|fifteen|twenty|thirty)\\s+(minutes?|mins?|hours?|hrs?|days?|weeks?)").find(t) ?: return null
        val n = number(m.groupValues[1]) ?: return null
        val unit = m.groupValues[2]
        return when {
            unit.startsWith("min") -> n
            unit.startsWith("h") -> n * 60
            unit.startsWith("d") -> n * 60 * 24
            else -> n * 60 * 24 * 7
        }
    }

    /** "the meeting" → "meeting", "my task about the dentist" → "dentist". */
    private fun cleanTarget(text: String) = text
        .replace(Regex("$I^(?:the\\s+task\\s+(?:called\\s+|about\\s+)?|my\\s+task\\s+(?:called\\s+|about\\s+)?|the\\s+|my\\s+)"), "")
        .trim().trimEnd(',', '.').ifBlank { text }
}
