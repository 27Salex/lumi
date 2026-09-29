package com.antigravity.gemininanotaskmanager.data.ai

import com.antigravity.gemininanotaskmanager.domain.ai.DayMode
import com.antigravity.gemininanotaskmanager.domain.ai.ReplyRequest
import com.antigravity.gemininanotaskmanager.domain.model.AgendaEvent
import com.antigravity.gemininanotaskmanager.domain.model.Task
import com.antigravity.gemininanotaskmanager.domain.model.TaskAICommand
import com.antigravity.gemininanotaskmanager.domain.time.DueDateFormatter
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Prompts compartidos por todos los motores LLM (Gemini Nano, Gemma, Gemini cloud).
 * Los datos (qué tareas, qué plan) los calcula siempre el código; el LLM solo interpreta y redacta.
 * Así un modelo pequeño on-device no puede inventarse tareas ni fechas.
 */
object AssistantPrompts {

    val INTERPRET_SYSTEM = """
Eres el intérprete de comandos de una app de tareas en español. Conviertes la frase del usuario en UN objeto JSON.
Responde SOLO con el JSON, sin markdown, sin ``` y sin texto adicional.

Esquema:
{"action":"CREATE|CREATE_MANY|UPDATE_STATUS|RESCHEDULE|SET_PRIORITY|EDIT|RECALL|REMEMBER|NAVIGATE|WEATHER|ASK|SUMMARIZE|PLAN_DAY","targetTitle":"...","newStatus":"TODO|IN_PROGRESS|COMPLETED|CANCELLED","category":"PERSONAL|WORK|STUDY|HEALTH|OTHER","dueDate":"YYYY-MM-DDTHH:MM o YYYY-MM-DD o null","hasTime":true|false,"description":"texto o null","recurrence":"DAILY|WEEKDAYS|WEEKLY:MO,TH|MONTHLY:1|YEARLY o null","remindBeforeMinutes":[120],"postponeMinutes":null,"meeting":"texto o null","priority":"NONE|LOW|MEDIUM|HIGH o null","place":"casa|trabajo|... o null","placeOnArrive":true|false,"items":[],"newTitle":"solo en EDIT, el nombre nuevo; si no, null"}

Reglas:
- CREATE: quiere apuntar/recordar algo. targetTitle = la tarea corta, en infinitivo, SIN fecha ni hora ni "recuérdame". El título va SIEMPRE en targetTitle (newTitle es solo para renombrar en EDIT).
- UPDATE_STATUS: dice que terminó (COMPLETED), empezó (IN_PROGRESS) o cancela (CANCELLED) una tarea. targetTitle = palabras clave de esa tarea.
- CREATE_MANY: la frase contiene VARIAS tareas distintas ("comprar pan, llamar a Ana y acabar el informe"). Pon cada una como un CREATE dentro de "items".
- RESCHEDULE: quiere mover/posponer/adelantar una tarea existente. targetTitle = palabras clave de la tarea; o dueDate (nueva fecha) o postponeMinutes (desplazamiento; negativo para adelantar).
- recurrence: solo si repite ("cada lunes y jueves" → WEEKLY:MO,TH; "todos los días" → DAILY; "entre semana" → WEEKDAYS; "el día 1 de cada mes" → MONTHLY:1).
- remindBeforeMinutes: avisos extra que pida ("avísame 2 horas antes" → [120]). Si no pide, [].
- meeting: si la tarea es para una reunión/llamada concreta ("para la reunión del sprint"), el texto de esa reunión; si no, null.
- priority: HIGH si dice "urgente", "importante", "cuanto antes"; LOW si dice "sin prisa", "cuando pueda"; MEDIUM si dice "prioridad media". Si no dice nada, null.
- SET_PRIORITY: cambia la prioridad de una tarea existente ("pon lo del dentista como urgente"). targetTitle = palabras clave; priority = la nueva.
- place: aviso por lugar ("cuando llegue a casa" → place "casa", placeOnArrive true; "al salir del trabajo" → place "trabajo", placeOnArrive false). Si no, null.
- EDIT: quiere cambiar una tarea existente (título, fecha, área, prioridad, nota). targetTitle = palabras clave de esa tarea (NO la frase entera); newTitle/dueDate/category/priority/description = solo lo que cambia. Si solo dice "edita X", sin cambios.
- UPDATE_STATUS, RESCHEDULE, SET_PRIORITY y EDIT: targetTitle son las palabras que identifican la tarea ("mi tarea sobre llevar a Víctor al trabajo" → "llevar a Víctor"). No hace falta que coincida exacto: la app la busca.
- RECALL: hace una PREGUNTA ("¿cuál es el wifi de la oficina?", "¿cuándo es lo del dentista?"). targetTitle = la pregunta. Nunca crees una tarea a partir de una pregunta.
- REMEMBER: SOLO si pide expresamente que recuerdes un dato ("recuerda que mi dentista es la Dra. López"). targetTitle = el dato. Si cuenta algo que ha pasado y pregunta algo ("he dejado la leche fuera toda la noche, ¿me la puedo tomar?"), es ASK, nunca REMEMBER.
- Si hay [CONTEXTO PERSONAL], úsalo solo para entender a qué se refiere; no lo copies en targetTitle.
- NAVIGATE: pide ir a un sitio o cómo llegar ("llévame a casa", "¿cómo llego a la reunión?"). targetTitle = el destino tal cual.
- WEATHER: pregunta por el tiempo, lluvia, frío, calor o si necesita paraguas/chaqueta. targetTitle = la frase tal cual.
- ASK: pregunta o petición general que NO es sobre sus tareas ni su vida (datos, cálculos, ideas, recetas, traducciones, explicaciones). targetTitle = la frase tal cual. Nunca crees una tarea con esto.
- PLAN_DAY: pregunta qué hacer, pide un plan, recomendaciones o por dónde empezar.
- SUMMARIZE: pide un resumen o cómo va.
- category: WORK trabajo/reuniones/clientes, STUDY clases/exámenes/deberes, HEALTH deporte/médico/citas de salud, PERSONAL casa/compras/familia/amigos, OTHER resto.
- dueDate: calcula la fecha absoluta a partir de la FECHA ACTUAL que te doy. "a las 5" sin más contexto = 17:00. Si no hay fecha, null.
- hasTime: true solo si se dijo una hora concreta.
- description: SOLO si el usuario dicta detalles explícitos ("descripción: …", "nota: …", "detalles …"), copiando sus palabras. Si no, null. NUNCA inventes ni resumas una descripción.

Ejemplos (fecha actual lunes 2026-09-28 10:00):
"recuérdame llamar a mamá mañana a las 6" → {"action":"CREATE","targetTitle":"Llamar a mamá","newStatus":"TODO","category":"PERSONAL","dueDate":"2026-09-29T18:00","hasTime":true,"description":null}
"comprar regalo a Ana el viernes, nota: algo de libros" → {"action":"CREATE","targetTitle":"Comprar regalo a Ana","newStatus":"TODO","category":"PERSONAL","dueDate":"2026-10-02","hasTime":false,"description":"Algo de libros"}
"entregar el informe antes del viernes" → {"action":"CREATE","targetTitle":"Entregar el informe","newStatus":"TODO","category":"WORK","dueDate":"2026-10-02","hasTime":false}
"ya terminé la presentación" → {"action":"UPDATE_STATUS","targetTitle":"presentación","newStatus":"COMPLETED"}
"gimnasio cada lunes y jueves a las 19" → {"action":"CREATE","targetTitle":"Gimnasio","category":"HEALTH","dueDate":"2026-09-28T19:00","hasTime":true,"recurrence":"WEEKLY:MO,TH"}
"pospón lo del dentista una semana" → {"action":"RESCHEDULE","targetTitle":"dentista","postponeMinutes":10080}
"mueve la reunión al jueves" → {"action":"RESCHEDULE","targetTitle":"reunión","dueDate":"2026-10-01"}
"mañana comprar pan, llamar a Ana y acabar el informe" → {"action":"CREATE_MANY","items":[{"action":"CREATE","targetTitle":"Comprar pan","category":"PERSONAL","dueDate":"2026-09-29"},{"action":"CREATE","targetTitle":"Llamar a Ana","category":"PERSONAL","dueDate":"2026-09-29"},{"action":"CREATE","targetTitle":"Acabar el informe","category":"WORK","dueDate":"2026-09-29"}]}
"llamar al banco, es urgente" → {"action":"CREATE","targetTitle":"Llamar al banco","category":"PERSONAL","priority":"HIGH"}
"cuando llegue a casa recuérdame sacar la basura" → {"action":"CREATE","targetTitle":"Sacar la basura","category":"PERSONAL","place":"casa","placeOnArrive":true}
"crea una tarea llamada escribir a Roberto para cuando vuelva a casa" → {"action":"CREATE","targetTitle":"Escribir a Roberto","category":"PERSONAL","place":"casa","placeOnArrive":true}
"avisa a Roberto cuando llegue al trabajo" → {"action":"CREATE","targetTitle":"Avisar a Roberto","category":"PERSONAL","place":"trabajo","placeOnArrive":true}
"pon el informe como prioridad alta" → {"action":"SET_PRIORITY","targetTitle":"informe","priority":"HIGH"}
"edítame mi tarea de llevar a Víctor al trabajo y ponla para mañana" → {"action":"EDIT","targetTitle":"llevar a Víctor","dueDate":"2026-09-29"}
"¿cuándo tenía el dentista?" → {"action":"RECALL","targetTitle":"¿cuándo tenía el dentista?"}
"¿necesito chaqueta esta noche?" → {"action":"WEATHER","targetTitle":"¿necesito chaqueta esta noche?"}
"dame ideas para cenar algo ligero" → {"action":"ASK","targetTitle":"dame ideas para cenar algo ligero"}
"he dejado las natillas fuera de la nevera toda la noche me las puedo comer" → {"action":"ASK","targetTitle":"he dejado las natillas fuera de la nevera toda la noche me las puedo comer"}
"¿qué hago hoy?" → {"action":"PLAN_DAY"}
""".trim()

    val REPLY_SYSTEM = """
Eres Lumi, la asistente personal del usuario para organizar su día.
Tu personalidad: cercana, clara, inteligente y breve (estilo Apple/Revolut: preciso, sin florituras). Hablas en español de España.

Recibes HECHOS ya calculados por la app y redactas la respuesta para el usuario.
Reglas estrictas:
- Usa SOLO las tareas, fechas y números de los HECHOS. Nunca inventes tareas, horas ni datos.
- Máximo 4 frases cortas. Tono humano y motivador, sin sonar a robot ni a informe.
- Tutea al usuario. Estilo sobrio y claro: sin emojis (como mucho uno si aporta).
- No saludes («buenos días», «hola»): ya estáis hablando. Salvo en un resumen o plan del día, empieza directamente.
- Nunca menciones datos internos (estados en inglés como TODO, categorías en mayúsculas, JSON ni corchetes).
- Ten en cuenta el día de la semana y el momento del día que se indican.
- Sin markdown (ni **, ni #). Para listas usa líneas que empiecen por "1.", "2."...
""".trim()

    private val ES = Locale.forLanguageTag("es-ES")
    private val NOW_FMT = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", ES)

    fun interpretUser(prompt: String, now: LocalDateTime): String =
        "FECHA ACTUAL: ${now.format(NOW_FMT)}\nFRASE: $prompt"

    fun replyUser(request: ReplyRequest): String {
        val now = request.now
        val header = "AHORA: ${now.format(NOW_FMT)} (${DueDateFormatter.greeting(now).lowercase()})"
        val facts = when (request) {
            is ReplyRequest.TaskCreated ->
                "EVENTO: el usuario acaba de crear una tarea.\nTAREA: ${describe(request.task, now)}\n" +
                    "Confirma de forma natural y breve" + (if (request.task.dueAt != null) " y di que le recordarás antes." else ".")
            is ReplyRequest.TaskUpdated ->
                "EVENTO: el usuario cambió el estado de una tarea.\nTAREA: ${describe(request.task, now)}\nConfirma con entusiasmo breve."
            is ReplyRequest.TaskRescheduled ->
                "EVENTO: el usuario movió una tarea de fecha.\nTAREA: ${describe(request.task, now)}\nConfirma la nueva fecha en una frase."
            is ReplyRequest.Recall -> buildString {
                append("EVENTO: el usuario hace una pregunta.\nPREGUNTA: ${request.question}\n")
                if (request.facts.isNotEmpty()) append("MEMORIA PERSONAL: ${request.facts.joinToString(" | ")}\n")
                request.task?.let { append("TAREA RELACIONADA: ${describe(it, now)}\n") }
                append("Responde en una o dos frases usando SOLO estos datos. Si no bastan, dilo.")
            }
            is ReplyRequest.PriorityChanged ->
                "EVENTO: el usuario cambió la prioridad de una tarea.\nTAREA: ${describe(request.task, now)}\nConfírmalo en una frase."
            is ReplyRequest.TasksCreated ->
                "EVENTO: el usuario dictó varias tareas de golpe.\nTAREAS: ${list(request.tasks, now)}\nConfírmalas en una lista numerada breve."
            is ReplyRequest.DayPlan -> buildString {
                val p = request.plan
                append("EVENTO: el usuario pregunta qué hacer ahora.\n")
                append("MOMENTO: ").append(
                    when (p.mode) {
                        DayMode.WEEKEND -> "fin de semana → prioriza vida personal, salud y descanso"
                        DayMode.WORK_HOURS -> "día laborable en horario de trabajo → prioriza trabajo"
                        DayMode.AFTER_WORK -> "día laborable fuera del horario de trabajo → prioriza lo personal"
                    }
                ).append('\n')
                if (p.postponedWork > 0) append("TRABAJO APARCADO A PROPÓSITO: ${p.postponedWork} tareas\n")
                append("VENCIDAS: ").append(list(p.overdue, now)).append('\n')
                append("PARA HOY: ").append(list(p.dueToday, now)).append('\n')
                append("SUGERENCIAS EN ORDEN: ").append(list(p.suggestions, now)).append('\n')
                val upcoming = request.events.filter { it.allDay || it.end > System.currentTimeMillis() }
                request.freeSlot?.let { f ->
                    append("HUECO LIBRE AHORA: ${f.minutes} min")
                    f.suggestion?.let { append(", ideal para «${it.title}»") }
                    append('\n')
                }
                if (upcoming.isNotEmpty()) append("CALENDARIO HOY: ").append(upcoming.joinToString(" | ") { eventLine(it) }).append('\n')
                append("Redacta un plan breve y motivador. Menciona las vencidas primero si las hay. Termina con una pregunta corta.")
            }
            is ReplyRequest.Messages -> buildString {
                append("EVENTO: el usuario pide que le leas sus mensajes sin leer.\n")
                append("MENSAJES (app · conversación · quién: texto):\n")
                request.messages.sortedBy { it.time }.takeLast(25).forEach { m ->
                    append("- ${m.app} · ${m.conversation} · ${m.sender}: ${m.text.take(200)}\n")
                }
                append("Resúmelos para leerlos en voz alta en 2-4 frases: quién escribió y qué quiere, lo importante primero. ")
                append("Si alguien pregunta algo o pide algo, dilo. No inventes nada que no esté en los mensajes.")
            }
            is ReplyRequest.Briefing -> buildString {
                append("EVENTO: el usuario pide un resumen de su situación.\n")
                append("TAREAS: ").append(list(request.tasks, now)).append('\n')
                append("Haz un resumen humano e interesante: progreso, lo más urgente, y un consejo concreto para hoy.")
            }
        }
        return "$header\n$facts"
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun eventLine(e: AgendaEvent): String = if (e.allDay) "«${e.title}» (todo el día)" else
        "«${e.title}» a las ${java.time.Instant.ofEpochMilli(e.begin).atZone(java.time.ZoneId.systemDefault()).format(HM)}"

    private fun list(tasks: List<Task>, now: LocalDateTime) =
        if (tasks.isEmpty()) "ninguna" else tasks.joinToString(" | ") { describe(it, now) }

    private fun describe(t: Task, now: LocalDateTime) = buildString {
        val status = when (t.status) {
            com.antigravity.gemininanotaskmanager.domain.model.TaskStatus.TODO -> "pendiente"
            com.antigravity.gemininanotaskmanager.domain.model.TaskStatus.IN_PROGRESS -> "en marcha"
            com.antigravity.gemininanotaskmanager.domain.model.TaskStatus.COMPLETED -> "hecha"
            com.antigravity.gemininanotaskmanager.domain.model.TaskStatus.CANCELLED -> "cancelada"
        }
        append("«${t.title}» [${t.category.label}, $status")
        t.dueAt?.let { append(", vence ${DueDateFormatter.format(it, t.dueHasTime, now)}") }
        if (t.priority != com.antigravity.gemininanotaskmanager.domain.model.TaskPriority.NONE) append(", prioridad ${t.priority.label.lowercase()}")
        t.placeTrigger?.let { append(", aviso ${it.describe()}") }
        append("]")
    }

    // ── Parseo robusto de la salida JSON del LLM ─────────────────────────────

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val VALID_ACTIONS = setOf(
        TaskAICommand.CREATE, TaskAICommand.CREATE_MANY, TaskAICommand.UPDATE_STATUS, TaskAICommand.RESCHEDULE, TaskAICommand.SET_PRIORITY, TaskAICommand.NAVIGATE,
        TaskAICommand.EDIT, TaskAICommand.RECALL, TaskAICommand.REMEMBER, TaskAICommand.WEATHER, TaskAICommand.ASK,
        TaskAICommand.SUMMARIZE, TaskAICommand.PLAN_DAY
    )

    /** Extrae el primer objeto JSON de la respuesta (tolera ```json, texto antes/después) o null si no es válido. */
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
     * Gemma 4 E2B pone el título de una tarea nueva en «newTitle» (el campo de renombrar) y deja targetTitle vacío:
     * antes se perdía y se usaba el título de las reglas («Tengo dentista» en vez de «Dentista»).
     */
    private fun movedTitle(cmd: TaskAICommand): TaskAICommand {
        val fixed = if (cmd.action != TaskAICommand.EDIT && cmd.targetTitle.isNullOrBlank() && !cmd.newTitle.isNullOrBlank() && cmd.newTitle != "null")
            cmd.copy(targetTitle = cmd.newTitle, newTitle = null) else cmd
        return if (fixed.items.isEmpty()) fixed else fixed.copy(items = fixed.items.map { movedTitle(it.copy(action = it.action.uppercase().ifBlank { TaskAICommand.CREATE })) })
    }

    /** Limpia la respuesta conversacional: quita markdown que los modelos pequeños cuelan a veces. */
    /** Preguntas generales («¿cuántas calorías tiene un plátano?», «dame ideas para cenar»). */
    fun generalSystem(now: LocalDateTime, context: List<String>): String = buildString {
        append("Eres Lumi, la asistente personal del usuario. Hablas en español de España, cercana y breve. ")
        append("Hoy es ${now.format(NOW_FMT)}.\n")
        append("No saludes ni te presentes: ve directo a la respuesta. ")
        append("Responde a lo que te pide en 1-3 frases (o una lista corta de 3-5 puntos si pide ideas o pasos). Sin markdown ni emojis. ")
        append("Tu respuesta se puede leer en voz alta: nada de tablas ni enlaces.\n")
        append("Responde tú con lo que sabes, como haría un amigo que sabe de todo: cocina, salud, hogar, conservación de ")
        append("alimentos, dudas prácticas, ideas, cuentas, idiomas. Si hay riesgo (comida, salud), da una respuesta clara y prudente.\n")
        append("Solo responde exactamente NO_LO_SE si necesita datos de ACTUALIDAD que no puedes saber (noticias, resultados, ")
        append("precios de hoy, horarios de un sitio) o un DATO PRIVADO del usuario que no está en el contexto (el nombre de su ")
        append("dentista, su contraseña del wifi). Contar algo que le ha pasado y preguntar qué hacer NO es un dato privado: respóndele.\n")
        append("Ejemplo: «he dejado el pollo cocinado fuera de la nevera toda la noche, ¿me lo puedo comer?» → ")
        append("«Mejor no: la comida cocinada no debe pasar más de 2 horas fuera de la nevera; las bacterias se multiplican aunque ")
        append("no huela mal.»")
        if (context.isNotEmpty()) append("\nCONTEXTO PERSONAL (úsalo solo si viene al caso): ${context.joinToString("; ")}")
    }

    fun cleanReply(raw: String): String? = raw
        .replace("**", "")
        .replace(Regex("(?m)^#+\\s*"), "")
        .trim()
        .takeIf { it.length >= 2 }
}
