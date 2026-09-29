# 🤖 AGENTS.md — Lumi (Android)

Guía técnica y reglas operativas para agentes de IA y desarrolladores. Decisiones y su porqué: **`MEMORY.md`**.
Trabajo pendiente: **`TODO.md`**. Todo el texto de la UI y los comentarios van en **español**.

---

## 📌 Visión general

**Lumi** es un gestor de tareas con asistente de IA (marca: logo de dos arcos entrelazados; diseño sobrio Manus × Revolut × Apple).
Se habla o escribe en lenguaje natural («recuérdame llamar al dentista mañana a las 5»), entiende fechas,
planifica el día según día de la semana y horario, avisa antes de citas y sincroniza con Google Calendar y Google Tasks.

- **Stack:** Kotlin 2.4, Jetpack Compose (Material 3, tema claro/oscuro propio, edge-to-edge), Coroutines/Flow, Room v6, Glance, Vosk, Play Services Location (geovallas).
- **IA:** cadena de motores (ver abajo). Dispositivo objetivo: Samsung Galaxy S25 (sin Gemini Nano Prompt API).
- **Arquitectura:** capas `domain` / `data` / `presentation` + UDF con `StateFlow`. Sin DI: `TaskManagerApplication` es el service locator.

---

## 🏗️ Estructura (lo no evidente)

```
domain/
  ai/AssistantEngine.kt        # Contrato de motores + ReplyRequest + DayPlanResult
  model/                       # Task, TaskCategory, TaskAICommand, AIProcessingResult, AgendaEvent, Recurrence, TaskReminder
  reminder/ReminderPlanner.kt  # Política de avisos automáticos de Lumi (pura, testeada)
  stats/TaskStats.kt           # StatsCalculator (puro, testeado)
  time/DueDateFormatter.kt     # "hoy a las 17:00", saludos
  repository/                  # TaskRepository + TaskChangeListener
data/
  ai/                          # RuleBasedEngine, SpanishDateParser, TaskPhraseParser, CategoryHeuristics, DayPlanner, MeetingMatcher (puros, testeados)
                               # LlmEngine (base) → GeminiNanoEngine, GemmaLocalEngine(+GemmaModelManager), CloudGeminiEngine
                               # AssistantOrchestrator (cadena + reconcile), AssistantPrompts (prompts compartidos)
  local/                       # Room (v6: tasks, reminders, sync_tombstones) + BriefStore (resumen guardado)
  sync/                        # DeviceCalendar/CalendarTaskSync (CalendarContract), GoogleTasksAuth/GoogleTasksSync (REST)
  settings/SettingsRepository  # SharedPreferences → StateFlow<AppSettings>
service/reminder/              # AlarmManager (una alarma por aviso) + ReminderReceiver (Hecho / +1 h / Responder / reinicio)
service/wakeword/              # «Oye Lumi»: WakeWordService (Vosk, micrófono en primer plano) + VoskModelManager
service/LumiTileService.kt     # Tile de Ajustes rápidos
presentation/
  main/                        # MainActivity (navegación inferior), HomeScreen, MainViewModel
  tasks/ agenda/ stats/ settings/
  assistant/                   # AssistantActivity translúcida: modo compacto (píldora, voz) y completo (chat); ACTION_ASSIST y SEND
  components/LumiMark.kt       # Logo animado (LumiState), brillo de borde, TypewriterText
  components/AssistantBits.kt  # Kit UI: TaskRow (deslizar), SmartBar, ListGroup/ListRow, PillButton, SectionHeader…
  theme/LumiTheme.kt           # Tokens LumiColors claro/oscuro, Inter, iconos/colores de categoría
  widget/QuickTaskWidget.kt    # Glance + WidgetUpdater
docs/GOOGLE_TASKS_SETUP.md     # Cliente OAuth para Google Tasks (lo hace el usuario una vez)
```

---

## ⚙️ Compilación (Windows / PowerShell, solo existe `gradlew.bat`)

```powershell
.\gradlew.bat compileDebugKotlin --console=plain
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat testDebugUnitTest --tests "com.antigravity.gemininanotaskmanager.data.ai.SpanishDateParserTest"
```

- **JDK 17** fijado en `gradle.properties`. AGP 8.13.2 · Gradle 8.14.3 · Kotlin 2.4.20 · KSP 2.3.12 · compileSdk/targetSdk 36.
- **No subir** core-ktx ≥1.19, lifecycle ≥2.11, activity ≥1.13 ni Compose BOM ≥2026.08: exigen compileSdk 37 + AGP 9.1.
- No editar ficheros con `Set-Content` de PowerShell 5 (rompe UTF-8). Usar las herramientas de edición o Python.

---

## 🧠 Reglas clave

### 1. Motores de IA
- Orden: Gemini Nano → **Gemma (sin timeout)** → Gemini cloud (opcional) → reglas. Un motor devuelve `null` si no puede → siguiente.
- El LLM **solo interpreta y redacta**; plan del día, fechas y estadísticas los calcula el código. Así un modelo pequeño no inventa datos.
- `AssistantOrchestrator.reconcile`: si el LLM omite fecha/categoría que las reglas detectaron, se completan; la descripción del LLM
  solo se acepta si sale literalmente de lo que dijo el usuario.
- Si se añade un campo a `TaskAICommand`: actualizar esquema de `AssistantPrompts.INTERPRET_SYSTEM`, `RuleBasedEngine.parse` y `reconcile`.
- **Gemma (LiteRT-LM):** siempre `ThinkingConfig(enableThinking = false)` y `maxOutputToken`; al cancelar, `conversation.cancelProcess()`.
- GPU de Gemma: el manifest DEBE declarar `<uses-native-library>` para `libOpenCL.so` y `libvndksupport.so`; si la GPU falla al generar se pasa a CPU automáticamente.
- **AICore/Gemini Nano** solo infiere con una Activity en primer plano → nunca llamar a la IA desde un `Service`.
- Respuestas del LLM en JSON puro; `AssistantPrompts.parseCommand` tolera ```json y texto alrededor.

### 2. Datos
- Room v6 con migraciones manuales (`exportSchema = false`): cualquier cambio de esquema = subir versión + `Migration`.
- Actualizar tareas con `TaskEntity.mergeFrom(task)` (conserva `google_task_id`, `calendar_event_id`…), nunca `toEntity()`.
- Toda escritura pasa por `TaskRepository` → notifica a `TaskChangeListener` (calendario, Google Tasks, widget) y a recordatorios.
- `completedAt` lo sella el repositorio según el estado (las estadísticas dependen de ello).

### 3. Coroutines / Flow
- `combine` tipado admite **máximo 5 flujos**: agrupar en sub-estados (ver `SettingsExtras`, `Filters`, `BriefingState`).

### 4. Compose y diseño
- Colores SIEMPRE desde `Lumi.colors` (nunca hex sueltos en pantallas). Texto de acento con `accentText`, rellenos con `accent`.
- Sin degradados en textos ni botones; el degradado es exclusivo de `LumiMark` y `ScreenEdgeGlow`. Sin emojis en la UI.
- Categorías: `category.icon()` + `category.color(isDark)` + nombre (el color nunca va solo).
- `LumiState` alimenta logo y brillo de borde. Nuevos estados → revisar todos los `when` sobre `LumiState`.
- Para que un contenedor bloquee toques usar `clickable(indication = null, …) {}`; `clickable(enabled = false)` NO consume eventos.

### 5. Glance (widget)
- Colores con `ColorProvider(day = …, night = …)` de `androidx.glance.color`.
- Abrir Activities con `actionStartActivity(intent)` (un `startActivity` desde `ActionCallback` es "background activity launch" en Android 14+).
- El widget se refresca desde `WidgetUpdater` (listener del repositorio), no por polling. Fondos con variantes `drawable-night`.

### 6. Gráficos
- Seguir la skill dataviz: serie única sin leyenda, ≥2 series con leyenda, un solo eje, texto en colores de tinta, tabla de datos accesible.
- Colores de series: azul `#3987E5`, naranja `#D95926` (pasos oscuros validados). Estados (verde/rojo) no se usan como series.

### 7. Avisos, recurrencia y voz
- Los avisos se gestionan por `ReminderScheduler.schedule(task)` (recalcula AUTO, conserva CUSTOM). Borrar tarea: `cancel` ANTES de borrar.
- Completar una tarea recurrente crea la siguiente en `TaskRepositoryImpl.updateTask` (no duplicar esa lógica en la UI).
- `WakeWordService` solo se arranca con la app en primer plano (Android 14) y se pausa con `WakeWordService.pause/resume`
  mientras cualquier otra cosa use el micrófono.

### 8. Ahorro de peticiones a la IA
- El resumen diario se guarda (`BriefStore`) y no se regenera al abrir la app; las estadísticas nunca llaman a la IA.
