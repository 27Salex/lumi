package com.antigravity.gemininanotaskmanager.presentation.agent

import android.Manifest
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.antigravity.gemininanotaskmanager.domain.assistant.DeviceCommand
import com.antigravity.gemininanotaskmanager.service.notify.LumiNotificationListener
import java.text.Normalizer

/**
 * Ejecuta las acciones del móvil que pide el usuario («llama a mamá», «pon una alarma a las 7», «abre Spotify»…)
 * con intents estándar de Android. Llamar y escribir por nombre usan la agenda (READ_CONTACTS); si falta el permiso,
 * se pide y se vuelve a intentar.
 */
object DeviceActions {

    data class Contact(val name: String, val number: String, val label: String)

    sealed interface Outcome {
        data class Done(val note: String? = null) : Outcome
        data class NeedsPermission(val permissions: Array<String>) : Outcome
        /** Varios contactos (o números) posibles: la UI pregunta cuál. */
        data class ChooseContact(val command: DeviceCommand, val options: List<Contact>) : Outcome
        data class Failed(val message: String) : Outcome
        /** Falta un acceso especial que se da en una pantalla del sistema (No molestar, notificaciones). */
        data class NeedsAccess(val intent: Intent, val message: String) : Outcome
    }

    fun execute(context: Context, command: DeviceCommand, chosen: Contact? = null, aliases: ContactAliases? = null): Outcome = try {
        when (command) {
            is DeviceCommand.OpenApp -> openApp(context, command.name)
            is DeviceCommand.Alarm -> start(context, Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, command.hour).putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
                .apply { command.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            is DeviceCommand.Timer -> start(context, Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
                .apply { command.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            is DeviceCommand.Call -> call(context, command, chosen ?: aliases?.find(command.contact)?.let { Contact(it.name, it.number, "alias") })
            is DeviceCommand.Message -> message(context, command, chosen ?: aliases?.find(command.contact)?.let { Contact(it.name, it.number, "alias") })
            is DeviceCommand.PlayMusic -> music(context, command)
            is DeviceCommand.WebSearch -> start(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, command.query))
                .takeIf { it is Outcome.Done }
                ?: start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(command.query)}")))
            is DeviceCommand.OpenSettings -> start(context, when (command.panel) {
                DeviceCommand.SettingsPanel.WIFI -> Intent(Settings.Panel.ACTION_WIFI)
                DeviceCommand.SettingsPanel.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                DeviceCommand.SettingsPanel.VOLUME -> Intent(Settings.Panel.ACTION_VOLUME)
                DeviceCommand.SettingsPanel.GENERAL -> Intent(Settings.ACTION_SETTINGS)
                DeviceCommand.SettingsPanel.NOTIFICATION_ACCESS -> LumiNotificationListener.settingsIntent(context)
                DeviceCommand.SettingsPanel.DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            })
            is DeviceCommand.Flashlight -> flashlight(context, command.on)
            is DeviceCommand.DoNotDisturb -> doNotDisturb(context, command.on)
            is DeviceCommand.ReplyMessage ->
                if (LumiNotificationListener.reply(context, command.key, command.text)) Outcome.Done()
                // La notificación ya no está (se leyó en el móvil): se abre WhatsApp/SMS con el texto escrito
                else message(context, DeviceCommand.Message(command.contact, command.text, !command.app.contains("mensaje", true)), null)
        }
    } catch (e: SecurityException) {
        Outcome.Failed("Android no me deja hacerlo: ${e.message}")
    }

    // ── Llamar y escribir a contactos ──────────────────────────────────────

    private fun call(context: Context, command: DeviceCommand.Call, chosen: Contact?): Outcome {
        val number = chosen?.number ?: literalNumber(command.contact) ?: run {
            if (!granted(context, Manifest.permission.READ_CONTACTS)) {
                return Outcome.NeedsPermission(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE))
            }
            when (val found = pick(findContacts(context, command.contact))) {
                null -> return Outcome.Failed("No encuentro a «${command.contact}» en tus contactos.")
                is Pick.One -> found.contact.number
                is Pick.Many -> return Outcome.ChooseContact(command, found.options)
            }
        }
        // Con permiso de llamadas, llama directamente. Si falta, se pide UNA vez; si se deniega, se abre el marcador
        if (!granted(context, Manifest.permission.CALL_PHONE) && !askedCallPermission(context)) {
            markCallPermissionAsked(context)
            return Outcome.NeedsPermission(arrayOf(Manifest.permission.CALL_PHONE))
        }
        val direct = granted(context, Manifest.permission.CALL_PHONE)
        return start(context, Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
    }

    private fun message(context: Context, command: DeviceCommand.Message, chosen: Contact?): Outcome {
        val number = chosen?.number ?: literalNumber(command.contact) ?: run {
            if (!granted(context, Manifest.permission.READ_CONTACTS)) return Outcome.NeedsPermission(arrayOf(Manifest.permission.READ_CONTACTS))
            when (val found = pick(findContacts(context, command.contact))) {
                null -> return Outcome.Failed("No encuentro a «${command.contact}» en tus contactos.")
                is Pick.One -> found.contact.number
                is Pick.Many -> return Outcome.ChooseContact(command, found.options)
            }
        }
        return if (command.whatsapp) {
            // wa.me abre el chat con el texto escrito; el usuario solo pulsa enviar
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${internationalDigits(context, number)}?text=${Uri.encode(command.text)}")))
        } else {
            start(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).putExtra("sms_body", command.text))
        }
    }

    private fun askedCallPermission(context: Context) =
        context.getSharedPreferences("agent", Context.MODE_PRIVATE).getBoolean("asked_call_phone", false)

    private fun markCallPermissionAsked(context: Context) =
        context.getSharedPreferences("agent", Context.MODE_PRIVATE).edit().putBoolean("asked_call_phone", true).apply()

    private sealed interface Pick {
        data class One(val contact: Contact) : Pick
        data class Many(val options: List<Contact>) : Pick
    }

    private fun pick(found: List<Contact>): Pick? = when {
        found.isEmpty() -> null
        found.size == 1 -> Pick.One(found.first())
        else -> Pick.Many(found.take(5))
    }

    /**
     * Contactos cuyo nombre (o apodo) encaja, sin tildes ni mayúsculas y sin los prefijos de acceso rápido
     * («AA Mamá», «AAA Víctor», «★ Ana»): exacto > empieza por > contiene.
     */
    fun findContacts(context: Context, name: String): List<Contact> {
        val q = cleanName(plain(name).removePrefix("a ").removePrefix("al ").removePrefix("mi ").trim())
        if (q.isBlank()) return emptyList()
        val nicknames = nicknames(context)
        val out = mutableListOf<Pair<Int, Contact>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val display = c.getString(0).orEmpty()
                val names = listOf(cleanName(plain(display))) + nicknames[c.getLong(4)].orEmpty().map { cleanName(plain(it)) }
                val rank = names.minOf { n ->
                    when {
                        n == q -> 0
                        n.startsWith("$q ") || n.split(" ").any { it == q } -> 1
                        n.contains(q) -> 2
                        else -> 9
                    }
                }
                if (rank == 9) continue
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                out += rank to Contact(display, c.getString(1).orEmpty(), label)
            }
        }
        val best = out.minOfOrNull { it.first } ?: return emptyList()
        return out.filter { it.first == best }.map { it.second }
            .distinctBy { it.number.filter(Char::isDigit).takeLast(9) }
    }

    /** Prefijos para que un contacto salga el primero en la agenda: «aa», «aaa», «a.», «00», símbolos. */
    // Solo con espacio detrás: «aa mama» → «mama», pero «aaron» se queda como está
    private fun cleanName(n: String): String = n.replace(Regex("^(?:a{2,}|0{2,}|z{2,}|x{2,})\\s+"), "").trim()

    /** Apodos (campo «Apodo» del contacto) por id de contacto. */
    private fun nicknames(context: Context): Map<Long, List<String>> {
        val out = HashMap<Long, MutableList<String>>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Nickname.NAME),
                "${ContactsContract.Data.MIMETYPE} = ?", arrayOf(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE), null
            )?.use { c ->
                while (c.moveToNext()) c.getString(1)?.let { out.getOrPut(c.getLong(0)) { mutableListOf() } += it }
            }
        }
        return out
    }

    /** «llama al 600 123 456» → número tal cual. */
    private fun literalNumber(text: String): String? = text.filter { it.isDigit() || it == '+' }.takeIf { it.count(Char::isDigit) >= 6 }

    /** Número en formato internacional sin «+» (lo que pide wa.me). Sin prefijo → el del país de la SIM. */
    private fun internationalDigits(context: Context, number: String): String {
        val raw = number.filter { it.isDigit() || it == '+' }
        return when {
            raw.startsWith("+") -> raw.drop(1)
            raw.startsWith("00") -> raw.drop(2)
            else -> (countryCode(context) ?: "") + raw.filter(Char::isDigit)
        }
    }

    private fun countryCode(context: Context): String? {
        val iso = context.getSystemService(TelephonyManager::class.java)?.networkCountryIso?.uppercase()
        return mapOf("ES" to "34", "MX" to "52", "AR" to "54", "CO" to "57", "CL" to "56", "PE" to "51", "US" to "1", "GB" to "44", "FR" to "33", "DE" to "49", "IT" to "39", "PT" to "351")[iso]
    }

    // ── Apps, música, linterna ─────────────────────────────────────────────

    private fun openApp(context: Context, name: String): Outcome {
        val pm = context.packageManager
        val q = plain(name)
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to plain(it.loadLabel(pm).toString()) }
        val match = apps.firstOrNull { it.second == q }
            ?: apps.firstOrNull { it.second.startsWith(q) }
            ?: apps.firstOrNull { it.second.contains(q) }
            ?: apps.firstOrNull { editDistance(it.second, q) <= 2 }
            ?: return Outcome.Failed("No encuentro ninguna app llamada «$name».")
        val intent = pm.getLaunchIntentForPackage(match.first) ?: return Outcome.Failed("No puedo abrir «$name».")
        return start(context, intent)
    }

    private val MUSIC_APPS = mapOf(
        "spotify" to "com.spotify.music", "youtube music" to "com.google.android.apps.youtube.music",
        "youtube" to "com.google.android.youtube", "deezer" to "deezer.android.app", "amazon music" to "com.amazon.mp3",
        "apple music" to "com.apple.android.music"
    )

    private fun music(context: Context, command: DeviceCommand.PlayMusic): Outcome {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, command.query)
        command.app?.let { MUSIC_APPS[plain(it)] }?.let { intent.setPackage(it) }
        return start(context, intent)
    }

    /**
     * No molestar con el acceso «No molestar» (se concede una vez). En Android 15+ esto activa el modo propio de
     * Lumi, que el sistema muestra como «No molestar (Lumi)».
     */
    private fun doNotDisturb(context: Context, on: Boolean): Outcome {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            return Outcome.NeedsAccess(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                "Para activar No molestar necesito permiso: activa Lumi en la lista y vuelve a pedírmelo."
            )
        }
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return Outcome.Done()
    }

    private fun flashlight(context: Context, on: Boolean): Outcome {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return Outcome.Failed("Este móvil no tiene linterna.")
        cm.setTorchMode(id, on)
        return Outcome.Done()
    }

    // ── Utilidades ──────────────────────────────────────────────────────────

    private fun start(context: Context, intent: Intent): Outcome = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Outcome.Done()
    } catch (e: ActivityNotFoundException) {
        Outcome.Failed("No hay ninguna app instalada que pueda hacerlo.")
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun plain(text: String) = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9ñ ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun editDistance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }
}
