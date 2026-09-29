# Conectar Lumi con Google Tasks (gratis, ~10 min)

La API de Google Tasks es gratuita (límite de cortesía: 50.000 peticiones/día). Solo hay que
decirle a Google que la app Lumi puede pedir permiso a tu cuenta. **No hace falta tarjeta.**

> Google Calendar **no** necesita nada de esto: Lumi usa los calendarios que ya están en el móvil
> (Ajustes → Google Calendar → «Dar permiso de calendario»).

## Pasos

1. Entra en <https://console.cloud.google.com> con tu cuenta de Google y crea un proyecto
   (p. ej. «Lumi»).
2. **APIs y servicios → Biblioteca** → busca **Google Tasks API** → **Habilitar**.
3. **APIs y servicios → Pantalla de consentimiento de OAuth**
   - Tipo de usuario: **Externo**.
   - Nombre de la app: «Lumi», email de asistencia y de contacto: el tuyo.
   - En **Usuarios de prueba** añade tu propia cuenta de Google.
   - No hace falta publicar la app ni verificarla (uso personal en modo «prueba»).
4. **APIs y servicios → Credenciales → Crear credenciales → ID de cliente de OAuth**
   - Tipo de aplicación: **Android**.
   - Nombre del paquete: `com.antigravity.gemininanotaskmanager`
   - Huella SHA-1: cópiala desde la app (**Ajustes → Google Tasks → Configuración única**,
     botón «Copiar»). Cada certificado de firma tiene su SHA-1: el APK de depuración de este PC
     tiene uno; si algún día firmas un APK de release, añade también su SHA-1.
5. En la app: **Ajustes → Google Tasks → Conectar con Google Tasks** → elige tu cuenta → Permitir.

Lumi crea una lista **«Lumi»** en Google Tasks y sincroniza en los dos sentidos
(al abrir la app y unos segundos después de cada cambio).

## Limitaciones conocidas

- Google Tasks solo guarda la **fecha** de vencimiento, no la hora. Lumi conserva la hora en el
  móvil mientras el día no cambie.
- Las tareas **canceladas** se borran de Google Tasks (allí no existe ese estado).
- En modo «prueba», Google puede volver a pedir el permiso de vez en cuando: Ajustes mostrará
  «Hay que volver a dar permiso» con un botón para hacerlo.
- Error «Falta configurar el cliente OAuth…» = el paquete o el SHA-1 del paso 4 no coinciden.
