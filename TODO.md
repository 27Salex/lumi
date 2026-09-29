# TODO — Lumi 3.0

Alcance pedido el 2026-09-28. Se marca `[x]` al terminar cada punto. (v2.1 completada: ver MEMORY.md)

## Datos y motor
- [x] Room v5: tabla `reminders` + recurrencia + vínculo a reunión en `tasks`
- [x] Avisos múltiples por tarea: política automática de Lumi + avisos personalizados
- [x] Recurrencia (cada día / lunes y jueves / cada mes / días laborables…): al completar se crea la siguiente
- [x] Reprogramar por voz: «mueve la reunión al jueves», «pospón lo del dentista una semana»
- [x] Brain dump: una frase con varias tareas → varias tareas (LLM + reglas)
- [x] Vincular tareas a reuniones del calendario (auto + manual) + aviso de preparación antes de la reunión
- [x] Prompts LLM y `reconcile` actualizados; tests

## Captura rápida
- [x] Compartir → Lumi desde cualquier app (texto y enlaces)
- [x] Tile de Ajustes rápidos
- [x] Responder desde la notificación de un aviso («hecho», «pospón una hora», «mañana a las 10»)

## Voz
- [x] «Oye Lumi»: palabra de activación offline (Vosk, gratis), servicio en primer plano, opción solo con pantalla encendida

## Diseño 3.0 (Manus × Revolut × Apple)
- [x] Tokens de tema claro (blanco + azul cielo) y oscuro (+ magenta pastel); selector Sistema/Claro/Oscuro
- [x] Tipografía Inter; sin degradados en textos/botones, sin aurora, sin emojis de categoría (iconos + color)
- [x] Logo de Lumi (dos arcos entrelazados azul cielo + magenta) animado: respira en reposo, reacciona a la voz, orbita al pensar; también icono de app
- [x] Fuera de la app (botón lateral, «Oye Lumi», widget, tile): píldora compacta estilo Siri/Gemini, voz primero; tocar el logo → escribir
- [x] Dentro de la app: barra inteligente con logo + campo de texto (tocar el logo → hablar)
- [x] Brillo de borde sutil estilo Apple Intelligence
- [x] Rediseño de Inicio, Tareas, Agenda, Progreso, Ajustes, asistente, editor, widget e icono

## Cierre
- [x] Capturas en emulador (claro y oscuro), tests, docs (CLAUDE.md, AGENTS.md, MEMORY.md)
- [x] APK a Google Drive (Mi unidad/Lumi/Lumi-3.0.0.apk)

## Lumi 3.1 (plan «Lumi — Plan v3.1»)
- [x] Barra inferior sin temblor (barra propia, solo colores animados)
- [x] Píldora del asistente desde arriba (desliza hacia arriba para cerrar) + brillo superior
- [x] Prioridad en tareas (Room v6, reglas + LLM, «pon X como urgente», filtro y orden, plan, avisos, widget)
- [x] Confirmar antes de salir del editor si hay cambios
- [x] Próxima tarea en directo (pantalla de bloqueo / Now Bar)
- [x] Lumi responde en voz alta cuando le hablas
- [x] Avisos por lugar (geovallas; Ajustes → Lugares)

## Pendiente de probar en el Galaxy S25
- [ ] Now Bar: que One UI promocione la notificación (Android 16 + One UI 8)
- [ ] Avisos por lugar con «Ubicación todo el tiempo» (llegar / salir de casa)
- [ ] Voz de respuesta (TTS de Samsung/Google en español)
- [ ] «Oye Lumi» con el micrófono real (y batería con «solo pantalla encendida»)
- [ ] Gemma en GPU tras el arreglo de OpenCL (v2.1.1)
- [ ] Respuesta desde la notificación de un aviso
- [ ] Vincular a reuniones reales de Google Calendar

## Ideas siguientes (ver doc «Lumi — Plan v3.1»)
- [ ] Dividir en pasos (subtareas con Gemma)
- [ ] Modo foco (Pomodoro ligado a una tarea)
- [ ] Tareas desde capturas (OCR on-device)
- [ ] Repaso semanal del domingo
- [ ] Hábitos y rachas
- [ ] Captura desde el Galaxy Watch
- [ ] Copia de seguridad automática a Google Drive
- [ ] Resumen matinal / repaso nocturno como notificación programada
- [ ] Bloques de tiempo: sugerir huecos libres del calendario para cada tarea
