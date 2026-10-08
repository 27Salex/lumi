<p align="center">
  <img src="docs/images/logo.svg" alt="Lumi" width="96" />
</p>

<h1 align="center">Lumi</h1>

<p align="center">
  Un asistente de IA y gestor de tareas para Android, privado y en el propio móvil.<br/>
  Háblale en español o en inglés: planifica tu día, te avisa en el momento y el lugar adecuados y funciona sin internet.
</p>

<p align="center">
  <a href="README.md">Read in English</a> ·
  <a href="https://github.com/27Salex/lumi/releases">Descargar el APK</a> ·
  <a href="#cómo-funciona">Cómo funciona</a>
</p>

---

<p align="center">
  <img src="docs/images/home.png" alt="Inicio" width="230" />
  <img src="docs/images/assistant.png" alt="Asistente" width="230" />
  <img src="docs/images/tasks.png" alt="Tareas" width="230" />
</p>

## Qué hace

**Háblale con naturalidad.** «Recuérdame llamar al banco mañana a las 5», «muévela al viernes», «ponle prioridad
alta», «apunta leche, pan y café». Entiende fechas, prioridades, repeticiones («cada lunes y jueves a las 7 de la
tarde»), varias órdenes en una frase y lo que digas después sobre la tarea que acabas de mencionar.

**Avisos que se adaptan a la tarea.** Lumi decide cuándo avisarte (la víspera de una cita importante, 10 minutos antes
de una reunión…) o añades los tuyos. Los avisos por lugar saltan al llegar a casa o al salir del trabajo, y una tarea
como «avisa a mamá cuando llegue a casa» trae un botón que abre el mensaje ya escrito.

**Un asistente, no solo una lista.**
- Planifica tu día según tu calendario, tu horario y tus prioridades («¿Qué hago ahora?») y te propone qué hacer en un hueco libre.
- El tiempo (Open-Meteo, sin clave) con avisos para tus tareas al aire libre.
- Responde preguntas generales y cuentas rápidas; solo recuerda datos cuando se lo pides.
- Lee tus mensajes sin leer y responde desde la notificación («¿qué me han escrito?», «respóndele que ya voy»).
- Acciones del móvil: llamadas, WhatsApp/SMS, alarmas, temporizadores, linterna, No molestar, música, rutas.
- Rutinas: «buenas noches» pone una alarma inteligente según tu primera reunión de mañana, activa No molestar y te
  cuenta lo que viene.
- Resumen de la mañana y repaso de la tarde como notificaciones.

**En todo el móvil.** Palabra de activación «Oye Lumi» (o «Hey Lumi» en inglés, sin internet), el botón lateral como asistente del sistema,
un tile de Ajustes rápidos, un widget, «Compartir → Lumi» desde cualquier app, la pantalla de bloqueo y un chip con
cuenta atrás para la próxima tarea (Android 16 / Now Bar).

**Privado por diseño.** El cerebro por defecto es **Gemma funcionando en tu móvil**; nada sale del dispositivo salvo
que actives el modelo en la nube (opcional). «Oye Lumi» se detecta en local sin transcribir nada, y los mensajes solo
se leen de las notificaciones activas y nunca se guardan.

**Sincroniza si quieres.** Google Calendar (a través del calendario del móvil) y Google Tasks en los dos sentidos.
Copia de seguridad completa en un fichero JSON para cambiar de móvil.

## Cómo funciona

Los modelos de lenguaje entienden bien a las personas y mal las fechas y las cuentas. Lumi reparte el trabajo:
**el modelo solo interpreta y redacta**, y el código calcula fechas, planes, estadísticas y avisos, así que un modelo
pequeño en el móvil no puede inventarse datos.

- **Cadena de motores:** gana el primero disponible: Gemini Nano (en móviles compatibles), Gemma 4 E2B con LiteRT-LM
  (se descarga una vez, 2,6 GB, por Wi-Fi), la API de Gemini con tu propia clave (desactivada por defecto) y, siempre,
  un motor de reglas determinista.
- **Reconciliación:** lo que el modelo omite o confunde (Gemma suele equivocarse con los días de la semana) se completa
  o corrige con las reglas.
- **Núcleo con tests:** los parsers, el planificador, la política de avisos y las estadísticas son Kotlin puro con tests.

El diagrama y los detalles están en el [README en inglés](README.md#how-it-works) y en [`AGENTS.md`](AGENTS.md).

## Instalar

1. Descarga el último `lumi-<versión>.apk` de [Releases](https://github.com/27Salex/lumi/releases) e instálalo
   (permite instalar desde el navegador o la app de archivos).
2. Abre Lumi y descarga Gemma (Inicio → IA local) con Wi-Fi.
3. Opcional: Ajustes → «Oye Lumi», Asistente digital, Lugares, Google Calendar,
   [Google Tasks](docs/GOOGLE_TASKS_SETUP.md).

Necesita Android 10 o superior. Desarrollado y probado en un Samsung Galaxy S25 (Android 16) y en el emulador.
El APK va firmado en modo depuración: es un proyecto personal, no una app de Play Store.

**Mi PC (solo ver).** Con Lumi Hub en tu PC, Lumi puede mostrar las pantallas de tu PC en el móvil (Inicio, icono de escritorio). Viene desactivado (`lumi_hub.py enable-pc-view` en el PC), pide tu huella o bloqueo de pantalla en cada sesión y nunca controla el PC. Ver `tools/lumi-hub/README.md`.

**Actualizaciones.** Lumi busca la última versión en GitHub al abrirse (como máximo cada 12 horas; Ajustes, Actualizaciones tiene «Comprobar ahora») y ofrece descargarla e instalarla. Los APK de las versiones van firmados en modo depuración, así que una actualización solo se instala sobre un Lumi firmado con la misma clave; si no, Lumi lo avisa antes de abrir el instalador (desinstala antes, tras exportar una copia).

**Chats.** La pestaña Chats de la barra inferior tiene un único historial de todas las conversaciones (chats con Lumi, hilos de Orbit, buzones de agentes) agrupadas por día, con búsqueda, renombrar y borrar (pulsación larga). Los agentes y «lo que Lumi ha aprendido» están en Gestionar agentes. «Nuevo chat» siempre crea un hilo nuevo con Lumi o con cualquier agente.

**Servidores y ajustes.** Ajustes es una lista corta de grupos (Asistente e IA, Servidores y PC, Voz, Notificaciones, Calendario, Apariencia, Copia y actualizaciones, Acerca de) con buscador. En Servidores y PC defines uno o varios servidores (nombre, URL, token opcional) y eliges cuál usan Lumi Hub/agentes, la búsqueda web y Mi PC, o uno solo para todo; cada servicio tiene prueba de conexión.

**Búsqueda web.** Por defecto funciona en el propio móvil (SearXNG público opcional, luego DuckDuckGo y Wikipedia); tu SearXNG, Brave o Claude por el Hub son opciones. Las peticiones de sitios («dónde puedo comer barato») usan tu ubicación y listan sitios cercanos con enlaces a Google Maps.

**Velocidad de Claude.** En el chat de Claude de Orbit eliges modelo y nivel de razonamiento (por defecto Sonnet, medio); el Hub mantiene un proceso de Claude activo por chat y muestra la respuesta según se escribe. `lumi` (ver `tools/lumi-hub/README.md`) arranca el Hub y SearXNG en el PC.

## Compilar

```bash
./gradlew assembleDebug        # APK → app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # tests
```

JDK 17, Android SDK 36. En Windows, `gradlew.bat`.

## Cómo se ha hecho

Lumi lo ha hecho una persona dirigiendo a un agente de programación con IA ([Claude Code](https://claude.com/claude-code)):
yo decidía qué debía hacer el asistente, lo probaba cada día en mi móvil y contaba lo que fallaba; el agente escribió
la mayor parte del código, los tests y la documentación. El historial de git y `MEMORY.md` muestran ese proceso tal
cual, fallos incluidos.

## Licencia

[Apache License 2.0](LICENSE). Los componentes y fuentes de datos de terceros están en
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). Gemma se descarga al usarla y está sujeta a los
[términos de uso de Gemma](https://ai.google.dev/gemma/terms).
