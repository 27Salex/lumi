# AGENTS.md — Lumi (Android)

Technical guide and working rules for AI agents and developers. Decisions and their reasons: **`MEMORY.md`**.
Pending work: **`TODO.md`**. Code and comments are in **English**; UI text lives in string resources
(`res/values` = English, default; `res/values-es` = Spanish).

---

## Overview

**Lumi** is an AI assistant and task manager for Android. You talk or type in natural language, in English or Spanish
("remind me to call the dentist tomorrow at 5", «recuérdame llamar al dentista mañana a las 5»). It understands dates,
plans your day around your schedule, reminds you before appointments (by time or by place), answers questions, checks
the weather, reads and replies to your messages, sets a smart alarm from your calendar, and syncs with Google Calendar
and Google Tasks.

- **Stack:** Kotlin 2.4, Jetpack Compose (Material 3, own light/dark theme, edge-to-edge), Coroutines/Flow, Room 9,
  Glance, LiteRT (openWakeWord + LiteRT-LM), Vosk (voice print only), Play Services Location (geofences).
- **AI:** an engine chain (see below). Reference device: Samsung Galaxy S25 (no Gemini Nano Prompt API).
- **Architecture:** `domain` / `data` / `presentation` layers + unidirectional data flow with `StateFlow`. No DI
  framework: `TaskManagerApplication` is the service locator.

---

## Layout (the non-obvious parts)

```
domain/
  ai/AssistantEngine.kt        # Engine contract + ReplyRequest + DayPlanResult
  assistant/                   # Conversation (Lang, ReplyLanguage, LanguageDetector, ConversationContext),
                               # DeviceCommand, MultiStep (CommandSplitter, TaskActions), Messages (routines),
                               # DayAssistant (AlarmPlanner, DayBriefComposer), ProactiveAssistant (check-in)
  model/                       # Task, TaskCategory, TaskPriority, TaskAICommand, AIProcessingResult, Recurrence, PlaceTrigger…
  reminder/ReminderPlanner.kt  # Lumi's automatic reminder policy (pure, tested)
  stats/  time/  weather/  live/   # StatsCalculator, DueDateFormatter, WeatherAdvisor, LiveUpdatePlanner (all pure)
data/
  ai/                          # RuleBasedEngine, SpanishDateParser, EnglishDateParser, EnglishCommands, TaskPhraseParser,
                               # CategoryHeuristics, DayPlanner, MeetingMatcher, AssistantIntents, QuickMath,
                               # IntentRouter (top-level task/agent/opinion/unsure route) (pure, tested)
                               # LlmEngine (base) → GeminiNanoEngine, GemmaLocalEngine (+GemmaModelManager), CloudGeminiEngine,
                               # ApiEngines (AnthropicEngine, OpenAiEngine for OpenAI + compatible servers; ApiFormats tested)
                               # AssistantOrchestrator (chain + reconcile), AssistantPrompts (shared prompts)
  local/                       # Room (v9: tasks, reminders, sync_tombstones, memories, chat_sessions/messages, agents,
                               # orbit_members) + BriefStore
  chat/                        # ChatStore (sessions shared by pill and app), ChatMemory (rolling summary for the LLM)
  hub/                         # Lumi Hub client: HubProtocol (events, SSE, safety; pure, tested), HubClient, HubConnection
                               # (SSE only while a screen is visible), HubInbox (HUB chat sessions + notifications)
  hub/PcViewClient             # My PC (view only): status, unlock, frames; domain/pcview = pure state machine + zoom/pan (tested)
  search/WebSearchService      # Opt-in web search (#7): Wikipedia / SearXNG / Brave / Lumi Hub + SearchParsers (tested)
  orbit/                       # OrbitRepository (threads = chat sessions of kind ORBIT, members, streamed agent turns),
                               # AgentBackends (extension point: LumiBrainBackend, ClaudePcBackend)
  backup/BackupManager.kt      # Export / import everything to a JSON file (phone change, reinstall)
  sync/                        # DeviceCalendar/CalendarTaskSync (CalendarContract), GoogleTasksAuth/GoogleTasksSync (REST)
  places/ routines/ weather/   # Saved places, routines store, Open-Meteo client
  settings/SettingsRepository  # SharedPreferences → StateFlow<AppSettings>
service/
  reminder/                    # AlarmManager (one alarm per reminder) + ReminderReceiver (Done / +1 h / Reply / reboot)
  wakeword/                    # "Hey Lumi" / "Oye Lumi": WakeWordService (openWakeWord detector, mic foreground service), Voice Match
  checkin/ live/ notify/ place/  # Evening check-in + morning summary, Now Bar chip, message reading, geofences
presentation/
  main/                        # MainActivity (bottom nav), HomeScreen, MainViewModel
  tasks/ agenda/ stats/ settings/
  assistant/                   # Translucent AssistantActivity: compact (pill, voice) and full (chat); ACTION_ASSIST and SEND
  orbit/                       # ChatsScreen (history of all chats; Home "Chats" pill), OrbitScreen (Manage agents, thread, agent editor) + OrbitViewModel
  update/                      # UpdateUi (Home card + Settings section); logic in domain/update (UpdateLogic) + data/update (UpdateManager)
  agent/                       # DeviceActions (calls, messages, alarms…), ContactAliases, ActionPreview
  components/                  # LumiMark (animated logo), AssistantBits (UI kit), UiLabels (labels in the app language)
  theme/LumiTheme.kt           # LumiColors light/dark tokens, Inter, category icons/colors
  widget/QuickTaskWidget.kt    # Glance + WidgetUpdater
docs/GOOGLE_TASKS_SETUP.md     # OAuth client for Google Tasks (the user does it once)
tools/lumi-hub/                # PC side of Lumi Hub: MCP server + relay + wake-per-message Claude Code (stdlib Python)
tools/intent_eval.sh           # Scores the top-level routing of a device's engine on app/src/test/resources/intent_eval.tsv
tools/gemma_batch.sh           # Runs a batch of phrases through the real Gemma on a device and greps the log
```

---

## Building (Windows / PowerShell; `gradlew` also exists for Linux/macOS)

```powershell
.\gradlew.bat compileDebugKotlin --console=plain
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat testDebugUnitTest --tests "io.github.salex27.lumi.data.ai.EnglishTest"
```

- **JDK 17** is pinned in `gradle.properties`. AGP 8.13.2 · Gradle 8.14.3 · Kotlin 2.4.20 · KSP 2.3.12 · compileSdk/targetSdk 36.
- **Don't bump** core-ktx ≥1.19, lifecycle ≥2.11, activity ≥1.13 or Compose BOM ≥2026.08: they require compileSdk 37 + AGP 9.1.
- Don't edit files with PowerShell 5 `Set-Content` (it breaks UTF-8). Use the editing tools or Python.

---

## Key rules

### 1. AI engines
- Order: Gemini Nano → **Gemma (no timeout)** → Gemini cloud → Anthropic → OpenAI → OpenAI-compatible (all optional) →
  rules; the user's "brain" choice moves one to the front (`BrainChain`). API keys: `BrainSettings` (Keystore), never
  in the backup. An engine returns `null` if it can't
  answer → the next one tries.
- The LLM **only interprets and phrases**; day plans, dates and stats are computed by code, so a small model can't make
  data up. Intents that the rules recognise reliably (weather, device actions, follow-ups, routines…) are RULES_FIRST
  and skip the LLM.
- `AssistantOrchestrator.reconcile`: fills in what the LLM left out; the rules' date wins (Gemma gets weekdays wrong);
  the LLM description is only accepted if it literally comes from what the user said.
- Adding a field to `TaskAICommand`: update the schema in `AssistantPrompts.INTERPRET_SYSTEM`, `RuleBasedEngine.parse`,
  `EnglishCommands.parse` and `reconcile`.
- **Gemma (LiteRT-LM):** always `ThinkingConfig(enableThinking = false)` and `maxOutputToken`; on cancel,
  `conversation.cancelProcess()`. The emulator's WebGPU crashes → CPU on emulators; a `gpu_trial_pending` flag switches
  to CPU after a GPU crash.
- The manifest MUST declare `<uses-native-library>` for `libOpenCL.so` and `libvndksupport.so` (Gemma on GPU).
- **AICore/Gemini Nano** only runs inference with an Activity in the foreground → never call the AI from a `Service`.
- LLM output is plain JSON; `AssistantPrompts.parseCommand` tolerates ```json fences and text around it.

### 2. Languages
- `ReplyLanguage.app` = the UI language (from the configuration; per-app language on Android 13+).
  `ReplyLanguage.current` = the language of the conversation in progress (detected per sentence).
- Android layers use string resources. Pure Kotlin uses `ReplyLanguage.t(es, en)` (conversation) or
  `ReplyLanguage.ui(es, en)` (UI/notifications).
- Screens must show domain labels in the app language: `uiLabel`, `uiLocale`, `DueDateFormatter.format(…, lang = ReplyLanguage.app)`.
  Never the plain `label` getter in UI code.
- Resource names can't be Java keywords (`import`, `package`…): the resource merger fails.

### 3. Data
- Room v9 with hand-written migrations (`exportSchema = false`): any schema change = bump the version + a `Migration`.
- Update tasks with `TaskEntity.mergeFrom(task)` (keeps `google_task_id`, `calendar_event_id`…), never `toEntity()`.
- Every write goes through `TaskRepository` → notifies `TaskChangeListener`s (calendar, Google Tasks, widget, places,
  live update) and the reminders.
- `completedAt` is stamped by the repository from the status (stats depend on it).
- A new preference or table must be added to `BackupManager` (and to its exclusion list if it is tied to the phone or secret).

### 4. Coroutines / Flow
- Typed `combine` takes **at most 5 flows**: group into sub-states (see `SettingsExtras`, `Filters`, `BriefingState`).

### 5. Compose and design
- Colors ALWAYS from `Lumi.colors` (never loose hex values in screens). Accent text with `accentText`, fills with `accent`.
- No gradients on text or buttons; the gradient belongs only to `LumiMark` and `ScreenEdgeGlow`. No emojis in the UI.
- Categories: `category.icon()` + `category.color(isDark)` + name (color never alone).
- `LumiState` drives the logo and edge glow. New states → review every `when` over `LumiState`.
- To make a container swallow taps use `clickable(indication = null, …) {}`; `clickable(enabled = false)` does NOT consume events.

### 6. Glance (widget)
- Colors with `ColorProvider(day = …, night = …)` from `androidx.glance.color`.
- Open Activities with `actionStartActivity(intent)` (a `startActivity` from an `ActionCallback` is a background
  activity launch on Android 14+).
- The widget is refreshed by `WidgetUpdater` (a repository listener), not by polling. Backgrounds have `drawable-night` variants.

### 7. Charts
- Follow the dataviz skill: single series without a legend, ≥2 series with a legend, one axis, text in ink colors,
  an accessible data table.
- Series colors: blue `#3987E5`, orange `#D95926` (validated dark steps). Status colors (green/red) are not series colors.

### 8. Reminders, recurrence and voice
- Reminders go through `ReminderScheduler.schedule(task)` (recomputes AUTO, keeps CUSTOM). Deleting a task: `cancel` BEFORE deleting.
- Completing a recurring task creates the next one in `TaskRepositoryImpl.updateTask` (don't duplicate that in the UI).
- `WakeWordService` can only start with the app in the foreground (Android 14) and is paused with
  `WakeWordService.pause/resume` while anything else uses the microphone.

### 9. Agents (Lumi Hub) are untrusted
- Anything an agent sends through Lumi Hub (`HubEvent`) is shown as plain text only. Never pass it to
  `processNaturalLanguageCommand`, the LLM interpreter, `DeviceActions` or an Intent; a proposed task becomes a Task
  only on an explicit tap, built by rules (`HubTaskProposal`). Links: http(s) only (`HubSafety.isSafeLink`).
- The hub token lives in the `hub` prefs file, which is NOT in the backup.

### 9b. My PC is view only
- Never add input injection, a terminal or command execution to the hub or the app (decision 2026-10-07). Unlock tokens stay in memory, never in logs or the backup; the screen uses FLAG_SECURE and locks when it is left.

### 10. Saving AI requests
- The daily summary is cached (`BriefStore`, per day and language) and not regenerated on every app start; stats never call the AI.
