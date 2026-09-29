# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Read `AGENTS.md` first** — it holds the binding implementation rules (in Spanish) and the non-obvious file map. **`MEMORY.md`** is the dated decision log + known issues (append an entry for any non-obvious decision); **`TODO.md`** tracks pending work. All UI strings and code comments are Spanish — keep it that way. The app is branded **Lumi** (v3: sober Manus/Revolut/Apple-style design, animated two-arc logo); the package/applicationId is still `io.github.salex27.lumi` on purpose (in-place upgrades, OAuth client).

## Commands (Windows — only `gradlew.bat` exists)

```powershell
.\gradlew.bat compileDebugKotlin --console=plain   # fastest correctness check
.\gradlew.bat assembleDebug                         # APK → app/build/outputs/apk/debug/
.\gradlew.bat testDebugUnitTest                     # JVM tests (app/src/test), pure-Kotlin logic
.\gradlew.bat testDebugUnitTest --tests "io.github.salex27.lumi.data.ai.RuleBasedEngineTest"
```

JDK 17 is pinned in `gradle.properties`. Toolchain: AGP 8.13.2 / Gradle 8.14.3 / Kotlin 2.4.20 / KSP 2.3.12 / compileSdk 36. Newer AndroidX releases require compileSdk 37 + AGP 9.1 — don't bump them casually (see AGENTS.md). Don't write files with PowerShell 5 `Set-Content` (corrupts UTF-8). In Git Bash, prefix adb commands with `MSYS_NO_PATHCONV=1` or device paths like `/sdcard` get mangled. An emulator AVD `Medium_Phone_API_36.0` exists; start it with `-gpu host` (software GPU produces blank screenshots).

## Architecture (big picture)

**Command pipeline.** Every natural-language input (AssistantActivity chat/voice, widget mic, "¿Qué hago ahora?") goes through `TaskRepository.processNaturalLanguageCommand` → `AssistantOrchestrator.interpret` → a `TaskAICommand` (`CREATE | CREATE_MANY | UPDATE_STATUS | RESCHEDULE | SET_PRIORITY | PLAN_DAY | SUMMARIZE` + title/category/dueDate/description/recurrence/remindBeforeMinutes/meeting/priority/place) → repository executes it → `AssistantOrchestrator.reply(ReplyRequest)` writes the conversational answer → `AIProcessingResult(reply, engine)`.

**Engine chain** (`AssistantEngine`, first available wins, `null` = fall through): `GeminiNanoEngine` (ML Kit Prompt API; unavailable on the user's Galaxy S25) → `GemmaLocalEngine` (LiteRT-LM, Gemma 4 E2B downloaded at runtime by `GemmaModelManager` via DownloadManager; **no timeout** by user decision) → `CloudGeminiEngine` (REST, user-supplied API key, off by default) → `RuleBasedEngine` (always available). LLMs only interpret/phrase; the code computes plans (`DayPlanner`), dates (`SpanishDateParser`), categories (`CategoryHeuristics`) and stats — these pure-Kotlin classes are the unit-tested core. `reconcile()` backfills LLM omissions from the rules.

**Side effects of task writes.** `TaskRepositoryImpl` is the only writer: it stamps `completedAt`, schedules AlarmManager reminders, and notifies `TaskChangeListener`s — `CalendarTaskSync` (timed tasks ↔ events in the phone calendar via `CalendarContract`), `PlaceReminderManager` (Play Services geofences for «cuando llegue a casa» tasks, one-shot), `LiveUpdateManager` (promoted ongoing notification with the next timed task/meeting → S25 Now Bar; selection logic in pure `LiveUpdatePlanner`), `GoogleTasksSync` (two-way REST sync with a "Lumi" list, debounced, OAuth via Play Services Authorization API; setup in `docs/GOOGLE_TASKS_SETUP.md`) and `WidgetUpdater`. Updates must use `TaskEntity.mergeFrom` so sync ids survive.

**Design system.** All colors come from `Lumi.colors` (`presentation/theme/LumiTheme.kt`: light = white + sky blue, dark = near-black + pastel magenta, Inter font); gradients only in the Lumi logo/edge glow; no emojis in UI. Reusable pieces live in `presentation/components` (`LumiMark`, `TaskRow`, `SmartBar`, `ListGroup`…).

**Reminders, recurrence, voice.** `ReminderPlanner` (pure) decides Lumi's automatic reminders per task; `AlarmReminderScheduler` stores one row per reminder in the `reminders` table and one alarm each. Completing a recurring task spawns the next occurrence inside `TaskRepositoryImpl.updateTask`. "Oye Lumi" is `WakeWordService` (openWakeWord TFLite detector in assets/oww + optional local Voice Match, mic foreground service, started only while the app is visible). Replies to voice requests are read aloud by `LumiSpeaker` (system TTS).

**UI.** `MainActivity` hosts a bottom nav (Inicio / Tareas / Agenda / Progreso) plus Settings and a shared `TaskEditSheet`; each tab has its own ViewModel (`MainViewModel`, `AgendaViewModel`, `StatsViewModel`, `SettingsViewModel`). `AssistantActivity` is a translucent activity registered for `ACTION_ASSIST` and `SEND` (compact pill that drops from the top when invoked from outside the app, full chat from inside) — it must stay an Activity because AICore only runs inference in the foreground. The daily brief is cached in `BriefStore` and only regenerated when missing/stale/refreshed, to save AI requests.

**Persistence.** Room DB version 7 with hand-written migrations (`exportSchema = false`); enums stored as names; seed data on first create. Settings are SharedPreferences exposed as `StateFlow<AppSettings>`.

**Assistant layer (v3.6).** Weather (`WeatherService`, Open-Meteo, no key) + pure `WeatherAdvisor`; general questions (`ASK`/`RECALL` → `QuickMath` → Gemini with `google_search` → LLM → web search); `DAY_BRIEF`, `SMART_ALARM` (`AlarmPlanner`), `NOTIFICATIONS` (`LumiNotificationListener`, active notifications only, in memory); routines (`RoutinesStore`, matched in `processNaturalLanguageCommand` before interpretation, steps parsed by rules only). New intents are recognised in `data/ai/AssistantIntents.kt` and are all RULES_FIRST. `AssistantActivity` shows over the lock screen and asks to unlock (`whenUnlocked`) before anything that opens another app.
