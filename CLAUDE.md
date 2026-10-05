# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Read `AGENTS.md` first**: it holds the binding implementation rules and the non-obvious file map. **`MEMORY.md`** is
the dated decision log + known issues (add an entry for any non-obvious decision); **`TODO.md`** tracks pending work.
Code and comments are in **English**. UI text lives in string resources: `res/values` (English, default) and
`res/values-es` (Spanish), always both. The app is branded **Lumi** (sober Manus/Revolut/Apple-style design, animated
two-circle logo); the package/applicationId is `io.github.salex27.lumi`.

## Commands

```powershell
.\gradlew.bat compileDebugKotlin --console=plain   # fastest correctness check (./gradlew on Linux/macOS)
.\gradlew.bat assembleDebug                         # APK → app/build/outputs/apk/debug/
.\gradlew.bat testDebugUnitTest                     # JVM tests (app/src/test), pure-Kotlin logic
.\gradlew.bat testDebugUnitTest --tests "io.github.salex27.lumi.data.ai.RuleBasedEngineTest"
```

JDK 17 is pinned in `gradle.properties`. Toolchain: AGP 8.13.2 / Gradle 8.14.3 / Kotlin 2.4.20 / KSP 2.3.12 /
compileSdk 36. Newer AndroidX releases require compileSdk 37 + AGP 9.1, so don't bump them casually (see AGENTS.md).
Don't write files with PowerShell 5 `Set-Content` (corrupts UTF-8). In Git Bash, prefix adb commands with
`MSYS_NO_PATHCONV=1` or device paths like `/sdcard` get mangled. An emulator AVD `Medium_Phone_API_36.0` exists; start
it with `-gpu host` (a software GPU produces blank screenshots).

## Architecture (big picture)

**Command pipeline.** Every natural-language input (AssistantActivity chat/voice, widget mic, "What should I do now?")
goes through `TaskRepository.processNaturalLanguageCommand`: language detection → routines → conversation follow-ups
→ top-level route (`IntentRouter`: agent / opinion / ask-when-unsure, rules only) → multi-command split → `AssistantOrchestrator.interpret` → a `TaskAICommand` (`CREATE | CREATE_MANY | UPDATE_STATUS |
RESCHEDULE | SET_PRIORITY | PLAN_DAY | SUMMARIZE | WEATHER | ASK | DEVICE | …` + title/category/dueDate/description/
recurrence/remindBeforeMinutes/meeting/priority/place) → the repository executes it → `AssistantOrchestrator.reply`
writes the conversational answer → `AIProcessingResult(reply, engine)`.

**Languages.** `ReplyLanguage.current` is the language of the conversation (detected per sentence by
`LanguageDetector`); `ReplyLanguage.app` is the UI language. English input goes to `EnglishCommands` /
`EnglishDateParser`, Spanish to `RuleBasedEngine` / `SpanishDateParser`. Screens use the app language (`uiLabel`,
`uiLocale`), chat replies the conversation language.

**Engine chain** (`AssistantEngine`, first available wins, `null` = fall through): `GeminiNanoEngine` (ML Kit Prompt
API; unavailable on the user's Galaxy S25) → `GemmaLocalEngine` (LiteRT-LM, Gemma 4 E2B downloaded at runtime by
`GemmaModelManager`; **no timeout** by user decision) → `CloudGeminiEngine` (REST, user-supplied API key, off by
default) → `RuleBasedEngine` (always available). LLMs only interpret/phrase; the code computes plans (`DayPlanner`),
dates, categories (`CategoryHeuristics`) and stats; these pure-Kotlin classes are the unit-tested core. `reconcile()`
backfills LLM omissions from the rules.

**Side effects of task writes.** `TaskRepositoryImpl` is the only writer: it stamps `completedAt`, schedules
AlarmManager reminders, and notifies `TaskChangeListener`s: `CalendarTaskSync` (timed tasks ↔ phone calendar events),
`PlaceReminderManager` (Play Services geofences for "when I get home" tasks, one-shot), `LiveUpdateManager` (promoted
ongoing notification with the next timed task/meeting → S25 Now Bar; logic in pure `LiveUpdatePlanner`),
`GoogleTasksSync` (two-way REST sync with a "Lumi" list; setup in `docs/GOOGLE_TASKS_SETUP.md`) and `WidgetUpdater`.
Updates must use `TaskEntity.mergeFrom` so sync ids survive.

**Design system.** All colors come from `Lumi.colors` (`presentation/theme/LumiTheme.kt`: light = white + sky blue,
dark = near-black + pastel magenta, Inter font); gradients only in the Lumi logo/edge glow; no emojis in UI. Reusable
pieces live in `presentation/components` (`LumiMark`, `TaskRow`, `SmartBar`, `ListGroup`, `UiLabels`…).

**Reminders, recurrence, voice.** `ReminderPlanner` (pure) decides Lumi's automatic reminders per task;
`AlarmReminderScheduler` stores one row per reminder in the `reminders` table and one alarm each. Completing a
recurring task spawns the next occurrence inside `TaskRepositoryImpl.updateTask`. "Hey Lumi" / "Oye Lumi" (one model for both) is `WakeWordService`
(openWakeWord TFLite detector in `assets/oww` + optional local Voice Match, mic foreground service, started only while
the app is visible). Replies to voice requests are read aloud by `LumiSpeaker` (system TTS, voice chosen per reply
language).

**UI.** `MainActivity` hosts a bottom nav (Home / Tasks / Agenda / Progress) plus Settings and a shared
`TaskEditSheet`; each tab has its own ViewModel. `AssistantActivity` is a translucent activity registered for
`ACTION_ASSIST` and `SEND` (compact pill from outside the app, full chat from inside); it must stay an Activity because
AICore only runs inference in the foreground, and it shows over the lock screen (asking to unlock before opening
another app). The daily brief is cached in `BriefStore` (per day and language) to save AI requests.

**Assistant layer.** Weather (`WeatherService`, Open-Meteo, no key) + pure `WeatherAdvisor`; general questions
(`QuickMath` → Gemini with `google_search` → LLM → web search); day brief, smart alarm (`AlarmPlanner`), message
reading/replying (`LumiNotificationListener`, active notifications only, in memory), routines (`RoutinesStore`),
phone actions (`DeviceActions`), personal memory (only saved on explicit request), Lumi Hub (`data/hub` +
`tools/lumi-hub`: AI agents on the PC message you / ask / propose tasks over Tailscale; their input is untrusted text,
see AGENTS.md rule 9), Orbit (`data/orbit`, `presentation/orbit`: chats with AI agents, `@Name` routing, one-step
"Chat with Claude" through the Hub). New intents are recognised in
`data/ai/AssistantIntents.kt` (Spanish) and `EnglishCommands` (English) and are RULES_FIRST.

**Persistence.** Room DB version 9 with hand-written migrations (`exportSchema = false`); enums stored as names; seed
data on first create (in the app language). Settings are SharedPreferences exposed as `StateFlow<AppSettings>`.
`BackupManager` exports/imports everything as JSON, except the API key and phone-tied ids.
