# 🧠 MEMORY.md — Registro de decisiones

Decisiones de diseño y problemas conocidos del proyecto. Añadir entradas nuevas arriba, con fecha,
**qué** se decidió y **por qué**. Para reglas técnicas generales ver `AGENTS.md`; tareas pendientes en `TODO.md`.

---

## 2026-09-29 — 1.0 prep: English codebase, bilingual app (en default + es)

- **All code and comments are now English** (user decision for the public GitHub release). UI text lives in
  `res/values/strings.xml` (English, default) and `res/values-es/strings.xml`; both are generated from one list so the
  keys never drift. Resource names can't be Java keywords (`import`, `export`, `package` broke the build → `action_import`…).
- **Two languages at runtime:** `ReplyLanguage.app` = the UI language (set from the configuration in the Application
  and in each Activity's `onCreate`); `ReplyLanguage.current` = the language of the conversation in progress (detected
  per sentence). Screens must use the app language (`uiLabel`, `uiLocale`, `lang = ReplyLanguage.app`), never the plain
  `label` getter, or an English chat turns a Spanish app's lists English. Chat replies (including the view model's local
  ones like "OK, I won't") follow the conversation language; choice answers accept English ("yes", "the second one").
- **Per-app language:** `res/xml/locales_config.xml` + Settings → Appearance → Language (Android 13+, `LocaleManager`).
  The daily summary cache stores its language and is regenerated when the app language changes.
- **Voice:** `LumiSpeaker` picks the TTS voice per reply (English text is not read with a Spanish voice).
- **Default routines** are created in the app language (names and steps); triggers understand both languages.
- **Create-task sheet** now also asks "Discard changes?" when closing, but only if something was changed (user request);
  the old shortcut "new task with an empty title closes silently" was removed.

## 2026-09-29 — v3.7.4: Gemma probada de verdad (emulador) → el título se perdía; fechas; GPU que tira la app

Por primera vez se probó Gemma 4 E2B real (mismo .litertlm y LiteRT-LM que en el S25) en el emulador:
- **Bug gordo:** Gemma pone el título de una tarea nueva en `newTitle` (campo de renombrar, que salía primero en el
  esquema) y deja `targetTitle` vacío → Lumi usaba siempre el título de las reglas («Tengo dentista», «Me recuerdes
  llamar a mi madre», «Que se llama…»). Arreglo: `AssistantPrompts.movedTitle` recoloca newTitle→targetTitle (salvo
  EDIT) y el esquema pone targetTitle primero y newTitle al final («solo en EDIT»).
- **Fechas:** Gemma falla con los días de la semana («el viernes» → domingo 4). En `reconcile` manda la fecha de las
  reglas; la del LLM solo si las reglas no vieron ninguna.
- Las DECISIONES de Gemma fueron buenas (ASK en «estoy cansadísimo» / «qué ceno con huevos», UPDATE_STATUS en «lo del
  informe ya lo he terminado»), mejores que las reglas. Por eso se aplazó el «router en dos pasos»: el problema era
  la fontanería, no la decisión. Revisar con más frases antes de rehacerlo.
- «(tengo que) acordarme de que X» → REMEMBER. Respuestas generales sin saludo.
- **GPU que tira la app:** en el emulador el backend GPU de LiteRT-LM usa WebGPU y muere con SIGSEGV (no capturable).
  `GemmaLocalEngine`: en emulador solo CPU; y en cualquier móvil se marca `gpu_trial_pending` (commit) antes de probar
  la GPU y se borra tras la primera respuesta → si la app muere a mitad, al volver se usa CPU en vez de caer en bucle.
- **Probar Gemma en el emulador:** AVD `Medium_Phone` con `disk.dataPartition.size = 16G` y `hw.ramSize = 8192`
  (con 2 GB se congelaba entero). CPU: ~10 s por interpretación, 1-2 min por respuesta larga. Logs:
  `adb logcat -s LumiInterpret` (crudo del LLM, reglas, final, tiempos, respuestas). Batería de frases:
  `tools/gemma_batch.sh < tools/gemma_frases.txt`. La RTX 4060 del PC no se puede usar dentro del emulador (solo
  gráficos); para cientos de frases valdría Ollama en el PC, pero no es el mismo runtime que el móvil.

## 2026-09-29 — v3.7.3: las preguntas se responden; la memoria solo si se pide

«He dejado una natilla en la nevera abierta toda la noche, ¿me la podría comer?» → Gemma eligió REMEMBER y se
guardó como recuerdo permanente, sin responder.
- `AssistantIntents.looksLikeQuestion`: preguntas sin «¿?» (la voz las pierde): «me la puedo comer», «se puede»,
  «es malo/seguro…», «estará bueno», «qué pasa si», «cuánto dura»… → ASK. Las peticiones a Lumi («¿puedes
  apuntar…?», «¿me recuerdas…?») no cuentan.
- `reconcile`: REMEMBER/FORGET del LLM solo valen si el usuario lo pide (`asksToRemember`: «recuerda que», «ten en
  cuenta», «apunta que», «mi X es…»); si no, pregunta → ASK, si no → reglas. Un CREATE del LLM sobre una
  pregunta clara (reglas = ASK) → ASK.
- Prompt: REMEMBER «SOLO si lo pide expresamente» + ejemplo de la natilla como ASK. Tests: `QuestionsTest`
  (con un LLM falso que devuelve REMEMBER, como hizo Gemma).

## 2026-09-29 — v3.7.2: «Que se llama…» en el título (Gemma) y respuestas con «TODO»

«créame una tarea que se llama hacer X» → Gemma devolvió el título «Que se llama hacer X» y respondió «Buenos
días… Está marcada como TODO».
- `TaskPhraseParser.cleanTitle` (pura, testeada) se aplica a TODO título, venga del motor que venga (reglas,
  reconcile y `buildAndSave`): quita «(créame) una tarea», «que se llama/llame», «llamada», «titulada», «con el
  nombre de», «tarea:». Antes solo se reconocía «que se llame» (subjuntivo) y solo en las reglas.
- Prompt de respuestas: el estado va en español (pendiente/en marcha…), prohibido mencionar datos internos
  (TODO, mayúsculas, JSON) y no saludar a mitad de conversación.
- **Lección:** cualquier limpieza de títulos debe ir en el punto común (repositorio), no solo en las reglas: con
  Gemma activo el título viene del LLM.

## 2026-09-29 — v3.7.1: la tarea «Avisar a X» enseña a quién está vinculada

El usuario probó «avisar a Roberto…» y creyó que no funcionaba: solo veía una tarea «Avisa a…» sin ningún vínculo
al contacto (el botón solo existía al saltar el aviso por lugar, y «casa» ni siquiera estaba guardada).
- `ActionPreview` resuelve el contacto AL CREAR (alias → número literal → agenda) y la respuesta lo dice:
  «Entonces tendrás un botón para enviar un WhatsApp a Roberto Pérez («Ya he llegado a casa»)…», o qué falta
  (sin permiso de contactos, no encontrado, varios).
- Editor de tarea: tarjeta `TaskActionCard` bajo «Lugar» con contacto, número, mensaje y «Probar ahora».
- Títulos en infinitivo: «Avisa a Roberto» → «Avisar a Roberto» (avisa/escribe/llama/manda/envía…).
- Sigue sin enviarse solo: WhatsApp no lo permite; la opción real sería SMS automático (SEND_SMS), pendiente de
  que el usuario la quiera.

## 2026-09-28 — v3.7.0: voz que no corta, varias órdenes por frase, «avisa a X al llegar», sitios por nombre

Quejas del usuario (con captura): el micro corta al hacer una pausa; no admite varias cosas seguidas («parece una
Alexa»); «Una tarea llamada escribir a Roberto para cuando vuelva a casa» creó una tarea con ese título entero;
quería avisar a alguien al llegar; el campo de lugar de la tarea solo servía con calles exactas.

- **Voz encadenada** (`VoiceSpeechManager`): el reconocedor de Google da el resultado tras ~1 s de silencio e ignora
  los extras de silencio → al llegar un trozo se guarda y se vuelve a escuchar; se envía tras `voicePauseMs` sin
  hablar (Corta 0,9 s / Normal 1,5 s / Larga 2,5 s, Ajustes → Conversación por voz). Parar = enviar lo dicho.
- **Conversación seguida**: tras responder a una frase dicha por voz (y acabar de hablar), la píldora vuelve a
  escuchar en modo «quiet» (si no dices nada se cierra sin error). No se hace si Lumi se va a otra app.
- **Varias órdenes** (`CommandSplitter`, puro): solo se corta ANTES de un verbo de orden en imperativo (lista cerrada),
  así «comprar pan y leche» y «dile a Ana que compre pan y que me espere» no se rompen; «…y avísame cuando llegue a
  casa / y ponle prioridad alta» se vuelve a pegar a la orden anterior. Cada trozo pasa por la cadena normal (LLM
  incluido) y se reúne en un `AIProcessingResult.Routine` (acciones en cola + tareas). Si un trozo pregunta
  («¿Te refieres a…?»), se para ahí. Las listas de infinitivos siguen yendo al brain dump.
- **Títulos**: «(crea) una tarea llamada X» → X (`TASK_NAMED_REGEX`); lugar con «para cuando vuelva/regrese», «al
  volver». Si el LLM devuelve un título «sin limpiar» (con «tarea llamada», «cuando llegue», «recuérdame»…) manda el
  de las reglas (`looksUncleaned`).
- **«Avisa a Roberto cuando llegue a casa»** = tarea con aviso por lugar (no un mensaje ahora: LATER/lugar en el
  texto). `TaskActions` (puro) detecta «llamar a X», «escribir/avisar a X (que …)» → el aviso lleva un botón
  («Llamar», «Enviar a Roberto») que abre la píldora con EXTRA_DEVICE, lanza la acción y da la tarea por hecha.
  «Avisar a X» sin texto + lugar → «Ya he llegado a casa». **No se envía solo**: WhatsApp no tiene API para enviar
  sin tocar (wa.me deja el texto escrito); enviar SMS automáticos pediría SEND_SMS → descartado por ahora.
- **Sitios por nombre** (`PlaceSearch`): Photon (OpenStreetMap, gratis, sin clave, con User-Agent) sesgado a tu
  ubicación + Geocoder para direcciones. «Cuando llegue al Mercadona»: si no es un lugar propio (casa, trabajo…)
  se usa el más cercano **a < 60 km**; si Lumi no sabe dónde estás, NO elige (en el emulador eligió una farmacia de
  Italia) y pide guardar el lugar. La respuesta dice la dirección elegida.
- Probado en emulador: la frase del usuario + «pon una alarma a las 7» + «qué tiempo hace en Madrid» en una sola
  frase (3 resultados correctos), Mercadona sin ubicación, botón «Enviar» (abre wa.me). La voz no se puede probar
  en el emulador. Tests: `Lumi37Test`.

## 2026-09-28 — v3.6.0: Lumi asistente (tiempo, preguntas, resumen de la mañana, mensajes, rutinas, bloqueo)

Petición del usuario: que Lumi se sienta asistente y no solo un atajo de tareas con voz. Eligió 1 (tiempo), 3 (preguntas
generales), 4 (resumen de la mañana), B (leer mensajes) y C (rutinas, con énfasis en la alarma según agenda/tareas), y
pidió que funcione con la pantalla bloqueada.

- **Tiempo con Open-Meteo** (`data/weather/WeatherService`): gratis, sin API key (uso no comercial). Ubicación aproximada
  (lastLocation < 6 h, si no getCurrentLocation 6 s) → lugar «casa» → última conocida. Ciudades con su geocoder gratuito.
  Caché 30 min en memoria + JSON crudo en prefs (Inicio lo muestra sin red). **Por qué no la IA:** los números salen de
  Open-Meteo y las frases de `WeatherAdvisor` (puro, testeado); un LLM pequeño inventaría temperaturas.
- **Avisos del tiempo en tareas al aire libre** (`WeatherAdvisor.taskWarnings`, palabras clave: correr, bici, playa…):
  en el resumen del día y al crear la tarea, **solo con la previsión ya descargada** (no se espera a la red al crear).
- **Preguntas generales** (acción ASK; RECALL sin datos personales cae aquí): 1) `QuickMath` sin IA (los LLM pequeños
  fallan cuentas), 2) si pide datos actuales (`needsFreshData`) → Gemini online con la herramienta `google_search`
  (`askWeb`; si falla, sin ella), 3) el LLM disponible con `generalSystem`, 4) sin LLM o respuesta `NO_LO_SE` →
  búsqueda en Google (DeviceCommand.WebSearch). Nunca se crea una tarea con una pregunta.
- **Alarma inteligente** (`AlarmPlanner`, puro): lo primero del día entre 5:00 y 13:00 (reunión, tarea con hora o entrar
  a trabajar de L-V) − preparación (60) − trayecto (30, solo si hay dirección/lugar o es el trabajo), redondeo a 5 min.
  «Mañana» a las 2:00 es hoy. Pregunta («¿a qué hora…?») → confirma antes; orden → la pone (AlarmClock SKIP_UI).
  En el repaso de la tarde se propone con un botón que es un PendingIntent de **Activity** (desde un receptor Android
  no deja abrir el reloj).
- **Resumen de la mañana** (`MorningScheduler`, 8:00 por defecto, ±15 min): lo escriben reglas (`DayBriefComposer`),
  no la IA, para que llegue aunque Gemma no esté cargada. «Escuchar» abre la píldora con EXTRA_SPEAK (lo lee en voz alta).
- **Leer mensajes** (`LumiNotificationListener`): solo notificaciones ACTIVAS (= sin leer), en memoria, nada en disco.
  MessagingStyle si lo hay; si no, título/texto. Responder con la acción «Responder» de la notificación (como Android
  Auto), **siempre tras confirmar** («¿Lo envío?»); si la notificación ya no está → wa.me/SMS. «Dile a X que…» usa
  esta vía si X tiene un mensaje sin leer con «Responder». Las claves de notificación llevan «|» → URL-encode al serializar.
- **Rutinas** (`RoutinesStore` + `RoutineMatcher`): frase exacta (sin tildes/signos/«oye lumi») → pasos que son frases
  normales interpretadas SOLO con reglas (rápido y predecible); un paso que acabaría en CREATE se salta con aviso.
  Acciones que no salen de Lumi primero (alarma, No molestar…), luego las que abren apps, la ruta al final.
  Dentro de una rutina no se abren pantallas de permisos (se menciona); `inRoutine` evita repetir la alarma en el resumen.
  Predefinidas: Buenas noches, Buenos días, Me voy a casa, Me voy al trabajo (editables/restaurables).
- **No molestar**: `setInterruptionFilter` con el acceso ACCESS_NOTIFICATION_POLICY (en Android 15+ es el modo de Lumi).
- **Pantalla bloqueada**: AssistantActivity con `setShowWhenLocked/setTurnScreenOn`; lo que abre otra app (llamar,
  WhatsApp, mapas, editar) pide desbloquear con `requestDismissKeyguard` y sigue solo al desbloquear. Alarma,
  temporizador, linterna, No molestar y responder mensajes funcionan sin desbloquear (`DeviceCommand.staysInLumi`).
- Probado en emulador: tiempo real de Madrid sobre la pantalla de bloqueo, «buenas noches» (alarma 7:30 + No molestar
  + resumen de mañana), ajustes nuevos. El emulador no da ubicación (tiempo «aquí» sin probar), ni hay mensajes reales.
- Tests: `Lumi36Test` (frases, tiempo, alarma, resumen, mensajes, rutinas).

## 2026-09-28 — v3.5.0: «Oye Lumi» con openWakeWord + mensajes y llamadas arreglados

- **Vosk fuera de la detección**: su modelo español no tiene «lumi» en el vocabulario (nunca lo transcribe). Ahora
  `OyeLumiDetector` = openWakeWord: melspectrogram.tflite + embedding_model.tflite (LiteRT 1.4.0, en assets) + un MLP
  propio (1536→128→64→1, `WakeClassifier` en Kotlin puro, `assets/oww/oye_lumi.bin`). Trozos de 80 ms (1280 muestras
  + 480 de contexto → 8 frames mel → 1 embedding); ventana de 16 embeddings. Vosk queda solo para la huella de voz
  (se calcula sobre los últimos 2 s al detectar) y para «Entrenar mi voz»; el detector no necesita descargas.
- **Entrenamiento** (scripts en el scratchpad de la sesión, `oww/`): 2 268 positivos con edge-tts (45 voces es-* de
  20 países + 12 multilingües, velocidades y tonos variados), aumentos (velocidad, eco sintético, ruido de colores,
  conversación de fondo, SNR 3-25 dB), 480 frases trampa, 252 frases normales, 347 h de negativos reales (primeros 3 GB
  de ACAV100M de openWakeWord por HTTP Range) y minado de negativos difíciles en media validación. 5 voces apartadas.
  Iteraciones: v1 72 falsas/h (sobreajuste a voces TTS) → v2 0 falsas/h pero 40 % acierto (peso negativo 50) →
  v3 (peso 8 y 2,3× positivos) elegido. Evaluación «en directo» (frase dentro de 3 s, 13 ventanas).
- **Umbrales**: Estricta 0,6 (66 % acierto voces nuevas / 0 falsas/h / 7,5 % trampas), Normal 0,4 (75 % / 0,4 / 13 %),
  Relajada 0,3 (81 % / 0,75 / 19 %); paciencia 1 (exigir varias ventanas perdía más aciertos que falsas quitaba).
  Las trampas las filtran además la huella de voz y la confirmación «¿Quieres que lo apunte?».
- **Paridad verificada**: `WakeClassifierTest` (Kotlin = PyTorch, 1e-4) y en el emulador con `WakeSelfTestReceiver`
  (adb, protegido con DUMP): mismas puntuaciones que la tubería en directo de Python (0,8457 / 0,9970 / 0,0198).
- **Plantillas personales descartadas**: la similitud coseno de embeddings no separa «Oye Lumi» de frases trampa de la
  misma voz (0,84 vs 0,80).
- **WhatsApp acababa como tarea**: la regla solo aceptaba una forma exacta. Ahora: detección de intención (canal +
  verbo, también en infinitivo «enviar WhatsApp a…»), varios patrones, y el LLM (`assistant.ask`) extrae destinatario y
  texto y lo pasa a estilo directo («dile que si viene a cenar» → «¿Vienes a cenar?»). Si falta algo, `AskFollowUp`
  («¿Qué le digo a Víctor?»). «Recuérdame enviar…» o una fecha en la orden → sigue siendo tarea.
- **Llamar solo marcaba**: CALL_PHONE se pedía junto a contactos y, si faltaba, se abría el marcador. Ahora se pide
  justo al llamar (una vez); si se deniega, marcador.

## 2026-09-28 — v3.4.0: modo agente (editar, memoria bajo demanda, acciones del móvil, contactos)

- **Encontrar la tarea correcta** (`TaskMatcher`, puro): palabras clave sin tildes/plurales/muletillas; puntuación =
  0,6·cobertura del título + 0,4·precisión. Segura si ≥ 0,7 y destaca (≥ 0,15 sobre la 2.ª); si no, «¿Te refieres a…?»
  con hasta 3 candidatas (`AIProcessingResult.Choose`, se re-ejecuta con `targetId`). Se responde tocando o diciendo
  «sí / la segunda / no / la del cumpleaños» (`parseChoiceAnswer`). «Ya terminé X» sin coincidencia ya NO crea una tarea.
- **EDIT**: renombrar, fecha, área, prioridad, nota, lugar; «edita X» sin cambios abre el editor (MainActivity con
  `EXTRA_EDIT_TASK_ID`). Renombrar «de X a Y» es ambiguo con «a» → `RenameSplitter` elige el corte que encaja con una tarea.
- **Memoria bajo demanda** (tabla `memories`, Room v7, `MemoryRetriever` tipo BM25): nunca se carga entera; al LLM solo
  le llegan los 2-3 recuerdos relacionados con la frase. «Recuerda que…» con obligación o fecha = tarea, no recuerdo.
  Las preguntas → RECALL (memoria + tareas), nunca una tarea nueva. Ajustes → Memoria para ver/olvidar/añadir.
- **Reglas primero** para órdenes claras (DEVICE, REMEMBER, FORGET, NAVIGATE, EDIT): no se espera a Gemma/Gemini;
  el resto va al LLM con el esquema ampliado y la app resuelve la tarea con `TaskMatcher` (el LLM no necesita el título exacto).
- **Acciones del móvil** (`DeviceCommandParser` puro + `DeviceActions`): abrir app, alarma, temporizador, llamar,
  WhatsApp/SMS prellenado (wa.me), música (MEDIA_PLAY_FROM_SEARCH), búsqueda, wifi/Bluetooth/volumen, linterna.
  «Llamar al banco» (infinitivo) y «llama al banco mañana» siguen siendo tareas.
- **Contactos**: búsqueda sin tildes que ignora prefijos de acceso rápido («AA Mamá») y usa el apodo; varios →
  «¿A cuál?» y se aprende como alias (`ContactAliases`); Ajustes → Contactos rápidos. Con CALL_PHONE llama directo.
- **Bug corregido**: los efectos que limpiaban su propio estado y luego esperaban (`delay`) se cancelaban (el efecto se
  reinicia al cambiar la clave) → «llévame a…» (3.2/3.3) nunca abría el mapa y «edita» no abría el editor. Ahora la
  espera va en `lifecycleScope`. Regla: en un `LaunchedEffect(clave)`, no suspender después de cambiar esa clave.
- **«Oye Lumi» con Vosk no funciona**: el modelo español no tiene «lumi» en su vocabulario, así que nunca lo transcribe.
  Pendiente de decisión: detector de palabra de activación real (openWakeWord o Porcupine).

## 2026-09-28 — v3.3.0: «Oye Lumi» más tolerante, lugares con dirección, tarjeta de hueco libre

- **«Oye Lumi» seguía sin reconocer al usuario, incluso en «Relajada».** Causas: (1) «Relajada» solo bajaba el umbral de
  voz; el filtro de frase exigía «oye/hola/ey/hey» exactos y el modelo pequeño transcribe a menudo «hoy lumi», «o lumi»;
  (2) sin huella de voz (frases muy cortas) se descartaba en silencio; (3) el entrenamiento solo aceptaba muestras que
  ya empezaban por «oye». Cambios (`WakePhrases.matches`): la sensibilidad controla también la frase (palabras de llamada
  ampliadas en Normal/Relajada, tolerancia de la frase entera 0 / ~1 / 1-2, hasta 4 palabras en Relajada); el
  entrenamiento guarda la FRASE tal como la transcribe el modelo con tu voz (vía B) → hay que volver a entrenar; en
  Relajada se acepta sin huella. Diagnóstico en Ajustes → Mi voz: última frase oída, % de voz y motivo.
  «luni» se descartó como variante (activaba con «hola luna llena»). Las 20 frases de conversación siguen sin activar.
- **Tarjeta de hueco libre**: al pulsar «Empezar» la tarea sigue en la tarjeta en modo «En marcha» (Hecho / Ahora no);
  «Ahora no» la oculta hasta el siguiente hueco. Minutos legibles («4 h 56 min»).
- **Lugares en tareas**: `PlaceTrigger` admite dirección suelta con coordenadas (buscada con el Geocoder del sistema,
  gratis, sesgado a ~50 km de tus lugares) y modo «Sin aviso» (solo referencia + «Cómo llegar»). El editor sugiere los
  lugares guardados, busca «Otra dirección…» y permite «Guardar como lugar». Ajustes → Lugares: «Añadir buscando la
  dirección» (sin estar allí). Formato compatible con v3.1 («casa|ARRIVE»).

## 2026-09-28 — v3.2.0: chip de Android 16, Lumi proactiva, rutas y logo «Mirada»

- **Now Bar no disponible**: en el S25 el usuario solo veía la notificación. En One UI la Now Bar / píldora de la pantalla
  de bloqueo es para apps de Samsung y socios; no se puede forzar. Se sustituye por el **chip de la barra de estado de
  Android 16** (API 36): `Notification.ProgressStyle` + petición de promoción + cronómetro (como Maps). Android 16 deja
  desactivar el chip por app → `NotificationManager.canPostPromotedNotifications()` y Ajustes muestra el estado
  (`LiveUpdateManager.ChipStatus`) con acceso a `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`. Si el sistema no pone
  `FLAG_PROMOTED_ONGOING`, se avisa («tu versión no lo convierte en chip»).
- **Huecos libres** (`FreeTimeFinder`, puro): hueco ≥ 25 min entre reuniones/tareas con hora (8–22 h) + tarea sin hora
  que adelantar. Tarjeta en Inicio (Empezar / Otra) y en la respuesta de «¿qué hago ahora?».
- **Repaso de la tarde** (`CheckInComposer` puro + `CheckInScheduler`): 20:00 por defecto (17–23), solo si hoy hubo algo;
  Pasar a mañana / Responder (comando normal) / Hablar con Lumi.
- **«Hablar»** en el chip y en el repaso → píldora escuchando (`AssistantActivity.talkPendingIntent`).
- **Rutas en la app elegida** (`MapsLauncher`): Google Maps y Waze abren navegación directa; el resto `geo:`; «Preguntar»
  = selector. Comando `NAVIGATE` («llévame a casa», «¿cómo llego a la reunión?»): lugar guardado → coordenadas;
  «la reunión» → próxima reunión con dirección (`AgendaEvent.location`); si no, el texto tal cual.
- **Bug TTS**: faltaba `<queries>` con `android.intent.action.TTS_SERVICE`; sin él, en Android 11+ TextToSpeech puede no
  encontrar el motor y Lumi no hablaba.
- **Logo «Mirada»** (elegido por el usuario entre Chispa / Líquido / Mirada / Cristal): dos círculos que se solapan con
  dos ojos en el solape. Sin giros (el giro + degradado de barrido «saltaba» y no le gustaba). Parpadea, mira a los lados,
  mira arriba al pensar, ojos «^ ^» al completar. El icono de notificación conserva el punto (a 24 dp no se ven ojos).

## 2026-09-28 — v3.1.0: prioridad, lugares, Now Bar, voz de respuesta, píldora arriba

Plan aprobado por el usuario (doc «Lumi — Plan v3.1»). Decisiones:
- **Barra inferior propia** (`LumiNavItem` en MainActivity): la `NavigationBarItem` de Material animaba a la vez indicador,
  etiqueta e icono y en el S25 «temblaba». Geometría fija, solo se animan colores. No volver a NavigationBarItem.
- **Píldora compacta arriba** (tipo Dynamic Island): cae con un muelle, se cierra deslizando hacia arriba, respuesta y
  confirmación debajo; brillo solo en el borde superior (`TopEdgeGlow`). No tapa teclado ni barra de gestos.
- **Prioridad** `TaskPriority` NONE/LOW/MEDIUM/HIGH (tres niveles + ninguna; el usuario no eligió «solo urgente»).
  Room 5→6 (`priority TEXT NOT NULL DEFAULT 'NONE'`, `place_trigger TEXT`). Reglas: «urgente/importante/cuanto antes/!!»
  → HIGH, «no es urgente/sin prisa/cuando pueda» → LOW (el orden de las reglas importa). Acción nueva `SET_PRIORITY`
  («pon X como urgente», «prioriza X»). Afecta a: orden de listas (conmutable «Orden: fecha»), DayPlanner (HIGH primero),
  ReminderPlanner (víspera 20:00 también sin hora), widget y brief. Google Tasks no tiene prioridad → solo local.
- **Confirmar al salir del editor**: `confirmValueChange` del sheet intercepta deslizar/tocar fuera/atrás si hay cambios;
  diálogo Guardar / Descartar / Seguir editando. Nueva tarea vacía se cierra sin preguntar.
- **Now Bar / actualización en directo** (`LiveUpdateManager` + `LiveUpdatePlanner` puro): notificación continua
  promocionada (`setRequestPromotedOngoing`, `setShortCriticalText`, cronómetro en cuenta atrás) con la próxima tarea con
  hora o reunión en las próximas 2 h. Se recalcula con alarmas en el siguiente cambio (máx. cada hora), al guardar tareas
  y al abrir la app. «Ocultar» esconde ese elemento. Se excluyen los eventos que Lumi creó para sus tareas.
- **Lumi habla** (`LumiSpeaker`): TTS del sistema solo cuando la petición llegó por voz; foco de audio con ducking; se
  calla si vuelves a hablar o escribes. Ajuste «Respuestas habladas» (activado por defecto).
- **Avisos por lugar**: geovallas de Play Services (`play-services-location` 21.3.0), una por tarea, radio 150 m, sin
  disparo inicial, **un solo aviso** por tarea+lugar (como Recordatorios de Apple; se rearma si cambias el lugar o se
  repite). Lugares en Ajustes → Lugares («Guardar aquí», sinónimos oficina/curro → trabajo). Si dictas un lugar no
  guardado, la tarea lo guarda igualmente y Lumi explica cómo guardarlo. Requiere «Ubicación todo el tiempo» para
  funcionar con la app cerrada. Las geovallas se re-registran al arrancar el móvil. Los fallos de registro se explican
  en Ajustes → Lugares (1000 = falta «Precisión de la ubicación de Google», 1004 = falta «Todo el tiempo»).
- **Hoja del editor**: en este BOM, «atrás» llama a `onDismissRequest` sin pasar por `confirmValueChange` → las dos rutas
  comprueban cambios, y «Seguir editando» vuelve a subir la hoja (`sheetState.show()`).
- **Verificado en emulador (API 36)**: migración 5→6 sobre una 3.0.3 instalada, píldora arriba, prioridad + lugar por
  voz, diálogo de descartar, guardar lugar, notificación en directo con cuenta atrás. No verificable en emulador: el chip
  promocionado de la Now Bar (depende de One UI 8), las geovallas disparándose (el emulador no tiene la precisión de
  Google) y la voz del TTS (emulador sin audio).

## 2026-09-28 — v3.0.3: «Oye Lumi» saltaba con conversaciones + «Entrenar mi voz»

- **Causa:** la gramática cerrada de 3.0.2 fuerza a Vosk a encajar *cualquier* audio en una de las frases → con 20 frases de
  conversación sintéticas saltó en 18/20, y con confianza 1.0 (la confianza no sirve para filtrar).
- **Decisión 1 — transcripción libre + filtro de frase corta** (`WakePhrases.isWakePhrase`): el resultado final debe tener
  2-3 palabras, empezar por oye/hola/ey/hey y el resto parecerse a «lumi» (Levenshtein ≤ 1 sobre «lumi/lomi/alumni…»).
  Resultado: 0/20 falsos positivos, 3/5 positivos. **Regla para el usuario:** «Oye Lumi», *pausa*, y luego la petición
  (como Siri). Decir todo seguido no activa.
- **Decisión 2 — Voice Match local** (`VoiceProfile.kt`): modelo de huella `vosk-model-spk-0.4` (13 MB, x-vector de 128),
  se descarga con el modelo de voz (existentes: se baja solo al abrir la app). Entrenamiento con 3 muestras de «Oye Lumi»;
  se guarda la media y cómo transcribió Vosk el «Lumi» del usuario (amplía los nombres aceptados). Umbral de coseno por
  sensibilidad: Estricta 0.55 / Normal 0.45 / Relajada 0.35 (medido: misma voz 0.69–0.97, otra voz ≈ 0.03). Sin perfil
  entrenado, solo aplica el filtro de frase. Todo queda en el móvil (SharedPreferences `voice_profile`).
- **Decisión 3 — red de seguridad:** si el asistente se abre por «Oye Lumi» (`EXTRA_FROM_WAKE_WORD`) y lo oído no parece una
  orden (`CommandLikeness`), se pregunta «He oído «X». ¿Quieres que lo apunte?» en vez de crear tareas. Sin respuesta en
  8 s, o si no oyó nada (2,5 s), se cierra solo.

## 2026-09-28 — v3.0.2: «Oye Lumi» no se activaba

- **Causa:** «lumi» no está en el vocabulario del modelo español de Vosk. Con gramática cerrada, Vosk descarta en silencio
  las palabras desconocidas (`Ignoring word missing in vocabulary: 'lumi'`), así que solo podía reconocer «oye»/«hola» y el
  detector (que busca «lumi») nunca saltaba. **Comprobado** cargando el modelo con Vosk en Python y con audio sintético es-ES.
- **Arreglo:** gramática con «lu mi» (dos palabras del vocabulario que suenan igual) → 3/3 activaciones, 0/5 falsos positivos.
  Se descartó añadir «lo mi»: falso positivo con «la luz del mediodía». Frases en `WakePhrases` con test de regresión
  (falla si alguien vuelve a poner «lumi» en la gramática).
- Regla: **cualquier palabra nueva en la gramática de Vosk hay que comprobarla contra el vocabulario del modelo.**

## 2026-09-28 — v3.0.1: descarga de «Oye Lumi» y errores de voz

- **Síntoma (en la calle, con datos):** «No se ha podido descargar el modelo» al activar «Oye Lumi». El servidor está bien
  (desde el PC baja en 1 s). **Causas:** (1) la descarga corría en el `lifecycleScope` de la pantalla y a la vez se abría el
  permiso «Mostrar sobre otras apps» → si Samsung cerraba la Activity, se cancelaba y se mostraba como error;
  (2) sin reintentos ni reanudación en una red móvil inestable; (3) el motivo real no se mostraba.
  **Arreglo:** descarga en el scope de la app, reanudable (HTTP Range) con 4 intentos, motivo visible + «Reintentar»,
  el permiso de superposición es ahora un botón aparte, y «Oye Lumi» queda activado aunque la descarga siga en curso.
- **Vosk `setPause(true)` NO suelta el micrófono** (sigue grabando): el reconocedor de Google recibiría silencio. Ahora se usa
  `stop()` y se reanuda con `startListening()`.
- Los errores del reconocedor de voz ya se muestran en el asistente (antes se ignoraban y parecía que la voz «no funcionaba»).
  Fuera de casa, el caso típico es `ERROR_NETWORK`: sin el paquete de español sin conexión, Google necesita internet.

## 2026-09-28 — v3.0 «Lumi 3.0»

### Diseño (petición: serio, mezcla Manus × Revolut × toque Apple; sin luciérnaga, se mantiene el nombre)
- **Sistema de diseño** en `presentation/theme/LumiTheme.kt`: tokens `LumiColors` (claro: blanco + azul cielo `#38BDF8`;
  oscuro: casi negro + magenta pastel `#F5A9D0`), tipografía **Inter** (la más parecida a SF, OFL, incluida en `res/font`),
  superficies planas, bordes finos. Acceso: `Lumi.colors`. Selector Sistema/Claro/Oscuro en Ajustes.
- En claro, el azul cielo no tiene contraste suficiente como texto sobre blanco → se usa `accentText = #0284C7` para textos/iconos
  y `accent` solo para rellenos (con texto oscuro encima).
- **Fuera**: degradados en textos/botones, aurora, emojis de categoría (ahora icono + color de la paleta validada), emojis en las
  respuestas (el prompt pide estilo sobrio). **El degradado solo existe en la marca de Lumi.**
- **Logo de Lumi** (`LumiMark`): dos arcos entrelazados azul cielo/magenta + núcleo; estados IDLE/LISTENING/THINKING/SPEAKING/SUCCESS
  (respira, se abre con la voz, orbita al pensar, se cierra al completar). Versión estática generada para icono, widget y notificación.
- **Asistente en dos modos**: compacto (píldora tipo Siri/Gemini, voz primero, tocar el logo → escribir) cuando se invoca desde fuera
  (botón lateral, «Oye Lumi», widget, tile); completo (conversación) desde la barra de la app. La barra de la app tiene logo + texto.
- Brillo de borde (estilo Apple Intelligence) solo en modo compacto mientras escucha/piensa.

### Funciones
- **Avisos múltiples** (tabla `reminders`, Room v5): Lumi decide los AUTO con `ReminderPlanner` (cita importante: víspera 20:00 +
  antelación elegida + 10 min; fecha límite: 2 días antes, ese día 9:00 y 18:00; reunión vinculada: 15 min antes). El usuario añade
  CUSTOM relativos ("avísame 2 horas antes" o en el editor) que siguen al vencimiento si cambia. Interruptor para desactivar los AUTO.
- **Recurrencia** (`Recurrence`, texto compacto "WEEKLY:MO,TH"): al completar se crea la siguiente con los mismos avisos personalizados.
  Solo con "cada/todos los/los + día" (no "el lunes y el jueves", que es puntual).
- **Reprogramar**: solo imperativos (mueve, pospón, aplaza, retrasa, adelanta, cambia, pasa, reprograma). Infinitivos ("pasar la ITV")
  son tareas nuevas. Si no se encuentra la tarea a mover → se crea como tarea nueva.
- **Brain dump**: solo se divide si CADA trozo empieza por verbo ("comprar pan y leche" = 1 tarea); la fecha dicha una vez se comparte.
- **Reuniones**: pista "para la reunión del X" → `MeetingMatcher` (palabras en común; genérica → próxima reunión). Sin fecha propia,
  la tarea vence 1 h antes de la reunión.
- **Captura rápida**: compartir → Lumi (enlaces como «Revisar: …» con la URL en la nota), tile de Ajustes rápidos, responder desde la
  notificación de un aviso ("hecho", "en 20 minutos" = volver a avisar, "pospón una hora" = mover, "mañana a las 10").
- **«Oye Lumi»**: Vosk (Apache 2.0, modelo español pequeño de 40 MB) con gramática cerrada ["oye lumi","hola lumi","hey lumi"]:
  no transcribe nada más. Servicio en primer plano tipo micrófono; solo se arranca con la app visible (restricción de Android 14),
  se pausa mientras el asistente usa el micro y opcionalmente con la pantalla apagada. Para abrir el asistente sobre otras apps usa
  la exención de SYSTEM_ALERT_WINDOW; sin ese permiso muestra una notificación. **No sustituye a «Hey Google»** (reservado al sistema).

## 2026-09-28 (mañana) — v2.1.1: Gemma en GPU

- **Problema (visto en el S25 con el diagnóstico):** Gemma cargaba en GPU (12,6 s) pero cada respuesta fallaba con
  «Can not find OpenCL library on this device» → Lumi caía a otro motor.
  **Causa:** desde Android 12 las librerías nativas del fabricante (`libOpenCL.so`) no son visibles para las apps
  si no se declaran. **Arreglo:** `<uses-native-library android:name="libOpenCL.so" / "libvndksupport.so" required=false>`
  en el manifest (lo exige la guía oficial de LiteRT-LM para GPU).
- **Red de seguridad:** si aun así la GPU falla al generar, `GemmaLocalEngine` recarga en **CPU**, reintenta la misma petición
  y lo recuerda (`force_cpu` en prefs; `retryGpu()` lo revierte). Así sigue siendo IA local en vez de caer a la nube.

## 2026-09-28 (noche) — v2.1 «Lumi» (decisiones tomadas sin el usuario, a petición suya)

### Mascota: Lumi 🌟 (sustituida en v3.0 por un logo abstracto; ver arriba)
- **Decisión:** el asistente se llama **Lumi**, una luciérnaga de IA (no un robot físico). La app también se llama «Lumi».
  **Por qué:** el usuario quería una mascota «cute» estilo las de las grandes empresas; una luciérnaga encaja con el orbe
  luminoso y degradado que ya existía, así que se le puso carita en vez de rediseñar todo.
- Carita animada en `AssistantOrb` (`AuraState`): IDLE parpadea y sonríe, LISTENING ojos grandes, THINKING mira de lado a lado,
  SPEAKING mueve la boca, HAPPY ojos `^ ^` (tras completar una tarea). Icono de app y widget con la misma carita (`lumi_face.xml`).
- **El `applicationId` NO cambia** (`io.github.salex27.lumi`) para que se actualice sobre la app instalada
  sin perder datos ni el cliente OAuth. Los nombres de paquete Kotlin tampoco (refactor sin valor para el usuario).

### IA
- **Gemma SIN timeout** (petición explícita): solo se pasa al siguiente motor si Gemma falla o no devuelve nada.
  Si es lento, el usuario lo desactiva a mano en Ajustes. `AssistantOrchestrator` acepta timeout `null`.
- **Causa de que v2.0 siempre cayera a Gemini cloud** (arreglado): `ThinkingConfig()` de LiteRT-LM viene con el
  razonamiento ACTIVADO y presupuesto ilimitado; sin `maxOutputToken`; y el timeout de corrutina no para la generación
  nativa, que seguía con el mutex tomado y bloqueaba todas las peticiones siguientes. Ahora: thinking off, tokens
  limitados, streaming con `cancelProcess()` al cancelar. Diagnóstico visible en Ajustes («Probar Gemma»).
- **Gemini Nano**: confirmado NO disponible en el Galaxy S25 del usuario (sin Prompt API). Se deja el código por si acaso.
- **Descripciones**: solo si el usuario la da explícitamente («descripción …», «detalles …», «nota: …», «con la nota de que …»).
  La IA solo puede rellenarla si el ≥80 % de sus palabras aparecen en lo que dijo el usuario (`isQuotedFrom`). Nunca se inventa.

### Resumen diario
- **Se guarda** (`BriefStore`, SharedPreferences) y solo se regenera si no hay ninguno, si es de **otro día**, o si el usuario
  pulsa «actualizar». **Por qué:** el usuario no quería gastar peticiones a Gemini/tiempo de Gemma en cada apertura.
  Lo de «otro día» lo añadí yo: un resumen de ayer diría «buenas noches» y tareas viejas.
- El comentario de la pestaña Progreso lo generan reglas locales, **nunca la IA**, por la misma razón.

### UI
- Navegación inferior: **Inicio · Tareas · Agenda · Progreso**; Ajustes desde el engranaje de Inicio.
- Inicio reducido a saludo + tarjeta de Lumi + «Hoy» + barra «Pregúntale a Lumi» (antes había demasiados botones).
- Tareas: filtros como **desplegables** (área y estado), grupos Vencidas/Hoy/Próximas/Sin fecha/Hechas; tocar = editar;
  «+» = crear a mano (`TaskEditSheet`).
- Agenda: vista de día estilo Google Calendar (tira semanal, fila «todo el día», línea de tiempo con bloques de tareas y eventos,
  columnas si se solapan, línea roja de «ahora»). Las tareas con hora duran 30 min en la vista.
- Gráficos (skill dataviz): colores = pasos oscuros validados de la paleta de referencia (azul `#3987E5`, naranja `#D95926`).
  El validador no se pudo ejecutar (no hay Node.js en el PC), pero son los slots 1-2 ya validados de la paleta de referencia;
  la separación CVD no depende del fondo y nuestro fondo (`#111421`) es más oscuro que el de referencia → contraste ≥.
- Voz: idioma por defecto **es-ES** (antes se usaba el idioma del sistema = inglés en el móvil del usuario). Selector en Ajustes.

### Sincronización
- **Google Calendar vía `CalendarContract`** (calendario del sistema) en vez de la API REST: gratis, sin Google Cloud,
  el propio Android sincroniza con Google. Citas con hora → evento de 30 min sin alarma (el aviso lo da Lumi, para no duplicar).
  Los eventos que crea Lumi se excluyen al leer la agenda (si no, saldrían duplicados).
- **Google Tasks vía API REST + Authorization API** (`play-services-auth`). Requiere que el usuario cree un cliente OAuth Android
  (guía: `docs/GOOGLE_TASKS_SETUP.md`; el SHA-1 se muestra en Ajustes con botón copiar). Lista propia «Lumi».
  Conflictos: gana el cambio más reciente. Tombstones para borrados. Canceladas → se borran en Google.
  Sincroniza al abrir la app y 3 s después de cada cambio (sin WorkManager: suficiente para uso personal).
- `TaskChangeListener`: el repositorio notifica cambios a calendario, Google Tasks y widget (desacoplado).
- `TaskEntity.mergeFrom(task)`: al actualizar desde el dominio se conservan los ids de sincronización (antes `toEntity()` los perdía).

### Toolchain (v2.0)
- AGP 8.13.2 + Gradle 8.14.3 + Kotlin 2.4.20 + KSP 2.3.12 + compileSdk 36. Las AndroidX más nuevas (core 1.19, lifecycle 2.11,
  activity 1.13, Compose BOM ≥ 2026.08) exigen compileSdk 37 + AGP 9.1 → se fijaron versiones anteriores. Migrar a AGP 9 es un
  cambio aparte (Kotlin integrado en AGP, desaparece el plugin kotlin-android).
- ⚠️ `Set-Content` de PowerShell 5 corrompe UTF-8; y en heredocs de bash, cuidado con `\n` en Python (se interpretó como salto de línea real y quitó sangrías) (pasó con `app/build.gradle.kts`): editar ficheros con Python o las herramientas de edición.

## 2026-09-28 (día) — v2.0

- Motores de IA en cadena (`AssistantEngine`): Gemini Nano (AICore) → Gemma (LiteRT-LM, descarga ~2,6 GB) → Gemini cloud (API key
  opcional, desactivado por defecto) → reglas. El LLM solo interpreta y redacta; los datos (plan, tareas) los calcula el código.
- El overlay de `Service` se sustituyó por `AssistantActivity` translúcida: AICore solo infiere con una Activity en primer plano, y
  así la app puede ser **asistente digital** del sistema (`ACTION_ASSIST`).
- Fechas/horas en español con `SpanishDateParser` («a las 5» sin más = 17:00). Recordatorios con AlarmManager (+ «Hecho» / «+1 h»).

## 2026-09-28 — v1.x

- Categorías fijas (`TaskCategory`), inferidas por IA → palabras clave → filtro activo → PERSONAL; editables con un toque.
- Coincidencia de tareas en `UPDATE_STATUS`: LIKE y, si falla, solapamiento de palabras (evita duplicados).

---

## ⚠️ Problemas conocidos / deuda técnica

- v3.0 probada en emulador (Android 16, claro y oscuro): Inicio, Tareas, Agenda, Progreso, Ajustes, editor, asistente compacto,
  recurrencia y brain dump. Falta probar en el S25: «Oye Lumi», Gemma GPU, respuesta desde notificación, reuniones reales.
- (v2.1) Nada de esto se había probado en el S25; en emulador (Android 16) funcionan Inicio, Tareas, Agenda, Progreso y el
  asistente con el motor de reglas. Gemma, voz, calendario real y Google Tasks requieren el móvil / configuración OAuth.
- Google Tasks solo tiene fecha (sin hora) → la hora vive solo en local.
- La vista de Agenda no permite arrastrar bloques para cambiar la hora (se edita desde la hoja de edición).
- No hay repositorio git: conviene `git init` para poder volver atrás.
