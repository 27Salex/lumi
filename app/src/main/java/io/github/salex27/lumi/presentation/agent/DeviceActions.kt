package io.github.salex27.lumi.presentation.agent

import io.github.salex27.lumi.domain.assistant.ReplyLanguage

import android.Manifest
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.view.KeyEvent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.CalendarContract
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import io.github.salex27.lumi.domain.assistant.DeviceCommand
import io.github.salex27.lumi.service.notify.LumiNotificationListener
import java.text.Normalizer

/**
 * Runs the phone actions the user asks for ("call mum", "set an alarm at 7", "open Spotify"…) with standard Android
 * intents. Calling and texting by name use the address book (READ_CONTACTS); if the permission is missing, it is
 * requested and retried.
 */
object DeviceActions {

    data class Contact(val name: String, val number: String, val label: String)

    sealed interface Outcome {
        data class Done(val note: String? = null) : Outcome
        data class NeedsPermission(val permissions: Array<String>) : Outcome
        /** Several possible contacts (or numbers): the UI asks which one. */
        data class ChooseContact(val command: DeviceCommand, val options: List<Contact>) : Outcome
        data class Failed(val message: String) : Outcome
        /** A special access granted on a system screen is missing (Do Not Disturb, notifications). */
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
            is DeviceCommand.MapsSearch -> start(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(command.query)}")))
                .takeIf { it is Outcome.Done }
                ?: start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/${Uri.encode(command.query)}")))
            is DeviceCommand.OpenSettings -> start(context, when (command.panel) {
                DeviceCommand.SettingsPanel.WIFI -> Intent(Settings.Panel.ACTION_WIFI)
                DeviceCommand.SettingsPanel.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                DeviceCommand.SettingsPanel.VOLUME -> Intent(Settings.Panel.ACTION_VOLUME)
                DeviceCommand.SettingsPanel.GENERAL -> Intent(Settings.ACTION_SETTINGS)
                DeviceCommand.SettingsPanel.NOTIFICATION_ACCESS -> LumiNotificationListener.settingsIntent(context)
                DeviceCommand.SettingsPanel.DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                DeviceCommand.SettingsPanel.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                DeviceCommand.SettingsPanel.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                DeviceCommand.SettingsPanel.NFC -> Intent(Settings.Panel.ACTION_NFC)
                DeviceCommand.SettingsPanel.HOTSPOT -> Intent("android.settings.TETHER_SETTINGS")
                DeviceCommand.SettingsPanel.MOBILE_DATA -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            })
            is DeviceCommand.Flashlight -> flashlight(context, command.on)
            is DeviceCommand.DoNotDisturb -> doNotDisturb(context, command.on)
            is DeviceCommand.AlarmSet -> alarmSet(context, command)
            DeviceCommand.CancelAlarms -> cancelAlarms(context)
            is DeviceCommand.BedtimeReminder -> {
                val app = context.applicationContext as io.github.salex27.lumi.TaskManagerApplication
                app.settings.update { it.copy(bedtimeEnabled = true, bedtimeMinutes = command.minutes, bedtimeDays = command.days) }
                app.bedtime.schedule()
                Outcome.Done()
            }
            is DeviceCommand.Volume -> volume(context, command)
            is DeviceCommand.Brightness -> brightness(context, command)
            is DeviceCommand.Media -> media(context, command.action)
            is DeviceCommand.PhoneInfo -> Outcome.Done(phoneInfo(context, command.kind))
            is DeviceCommand.CalendarEvent -> start(context, Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, command.title)
                .apply {
                    command.startIso?.let {
                        val begin = java.time.LocalDateTime.parse(it).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 3_600_000L)
                    }
                })
            DeviceCommand.Stopwatch -> start(context, Intent(AlarmClock.ACTION_SHOW_ALARMS)) // Android has no stopwatch intent: the clock opens
            is DeviceCommand.ShareLocation -> shareLocation(context)
            is DeviceCommand.ReplyMessage ->
                if (LumiNotificationListener.reply(context, command.key, command.text)) Outcome.Done()
                // The notification is gone (read on the phone): WhatsApp/SMS opens with the text written
                else message(context, DeviceCommand.Message(command.contact, command.text, !command.app.contains("mensaje", true)), null)
        }
    } catch (e: SecurityException) {
        Outcome.Failed(ReplyLanguage.t("Android no me deja hacerlo: ", "Android won't let me do it: ") + e.message)
    }

    // ── Calling and texting contacts ───────────────────────────────────────

    private fun call(context: Context, command: DeviceCommand.Call, chosen: Contact?): Outcome {
        val number = chosen?.number ?: literalNumber(command.contact) ?: run {
            if (!granted(context, Manifest.permission.READ_CONTACTS)) {
                return Outcome.NeedsPermission(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE))
            }
            when (val found = pick(findContacts(context, command.contact))) {
                null -> return Outcome.Failed(ReplyLanguage.t("No encuentro a «${command.contact}» en tus contactos.", "I can't find «${command.contact}» in your contacts."))
                is Pick.One -> found.contact.number
                is Pick.Many -> return Outcome.ChooseContact(command, found.options)
            }
        }
        // With the call permission, it calls directly. If missing, it is asked ONCE; if denied, the dialer opens
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
                null -> return Outcome.Failed(ReplyLanguage.t("No encuentro a «${command.contact}» en tus contactos.", "I can't find «${command.contact}» in your contacts."))
                is Pick.One -> found.contact.number
                is Pick.Many -> return Outcome.ChooseContact(command, found.options)
            }
        }
        return if (command.whatsapp) {
            // wa.me opens the chat with the text written; the user just taps send
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
     * Contacts whose name (or nickname) matches, ignoring accents and case and the quick-access prefixes
     * ("AA Mamá", "AAA Víctor", "★ Ana"): exact > starts with > contains.
     */
    fun findContacts(context: Context, name: String): List<Contact> {
        val q = cleanName(plain(name).removePrefix("a ").removePrefix("al ").removePrefix("mi ").removePrefix("to ").removePrefix("my ").trim())
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

    /** Prefixes that put a contact first in the address book: "aa", "aaa", "a.", "00", symbols. */
    // Only with a space after: "aa mama" → "mama", but "aaron" stays as it is
    private fun cleanName(n: String): String = n.replace(Regex("^(?:a{2,}|0{2,}|z{2,}|x{2,})\\s+"), "").trim()

    /** Nicknames (the contact's "Nickname" field) by contact id. */
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

    /** "call 600 123 456" → the number as is. */
    private fun literalNumber(text: String): String? = text.filter { it.isDigit() || it == '+' }.takeIf { it.count(Char::isDigit) >= 6 }

    /** Number in international format without "+" (what wa.me wants). No prefix → the SIM country's. */
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

    // ── Apps, music, flashlight ────────────────────────────────────────────

    private fun openApp(context: Context, name: String): Outcome {
        val pm = context.packageManager
        val q = plain(name)
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to plain(it.loadLabel(pm).toString()) }
        val match = apps.firstOrNull { it.second == q }
            ?: apps.firstOrNull { it.second.startsWith(q) }
            ?: apps.firstOrNull { it.second.contains(q) }
            ?: apps.firstOrNull { editDistance(it.second, q) <= 2 }
            ?: return Outcome.Failed(ReplyLanguage.t("No encuentro ninguna app llamada «$name».", "I can't find any app called «$name»."))
        val intent = pm.getLaunchIntentForPackage(match.first) ?: return Outcome.Failed(ReplyLanguage.t("No puedo abrir «$name».", "I can't open «$name»."))
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
     * Do Not Disturb through the "Do Not Disturb access" (granted once). On Android 15+ this turns on Lumi's own mode,
     * which the system shows as "Do Not Disturb (Lumi)".
     */
    private fun doNotDisturb(context: Context, on: Boolean): Outcome {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            return Outcome.NeedsAccess(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                ReplyLanguage.t("Para activar No molestar necesito permiso: activa Lumi en la lista y vuelve a pedírmelo.", "To turn on Do Not Disturb I need permission: enable Lumi in the list and ask me again.")
            )
        }
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return Outcome.Done()
    }

    private fun flashlight(context: Context, on: Boolean): Outcome {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return Outcome.Failed(ReplyLanguage.t("Este móvil no tiene linterna.", "This phone has no flashlight."))
        cm.setTorchMode(id, on)
        return Outcome.Done()
    }

    // ── Alarm sets (the clock app keeps them: it rings through Doze, reboot and Do Not Disturb rules) ──

    private fun alarmSet(context: Context, command: DeviceCommand.AlarmSet): Outcome {
        val app = context.applicationContext as io.github.salex27.lumi.TaskManagerApplication
        val done = mutableListOf<Int>()
        for (minutes in command.times) {
            val r = start(context, io.github.salex27.lumi.service.checkin.AlarmSetStore.setIntent(minutes, "Lumi"))
            if (r is Outcome.Failed) { if (done.isNotEmpty()) app.alarmSets.add(done); return r }
            done += minutes
            Thread.sleep(150) // the clock app handles them in order
        }
        app.alarmSets.add(done)
        return Outcome.Done()
    }

    private fun cancelAlarms(context: Context): Outcome {
        val app = context.applicationContext as io.github.salex27.lumi.TaskManagerApplication
        val entries = app.alarmSets.live()
        if (entries.isEmpty()) return Outcome.Done(ReplyLanguage.t("No tengo alarmas mías pendientes que quitar.", "I have no alarms of mine to remove."))
        val removed = mutableListOf<Int>()
        val kept = mutableListOf<Int>()
        for (e in entries) {
            if (io.github.salex27.lumi.service.checkin.AlarmSetStore.delete(context, e.minutes)) {
                app.alarmSets.remove(e.minutes); removed += e.minutes
            } else kept += e.minutes
            Thread.sleep(150)
        }
        val log = io.github.salex27.lumi.domain.assistant.AlarmLog
        val head = if (removed.isEmpty()) "" else ReplyLanguage.t("Quitadas: ", "Removed: ") + log.summary(removed) + ". "
        val tail = if (kept.isEmpty()) ReplyLanguage.t("Si alguna sigue en el reloj, bórrala allí.", "If one is still in the clock app, delete it there.")
        else ReplyLanguage.t("No he podido quitar ", "I couldn't remove ") + log.summary(kept) + ReplyLanguage.t(": bórralas en el reloj.", ": delete them in the clock app.")
        return Outcome.Done(head + tail)
    }

    // ── Volume, brightness, media, info ──────────────────────────────────────

    private fun volume(context: Context, c: DeviceCommand.Volume): Outcome {
        val am = context.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        val flags = AudioManager.FLAG_SHOW_UI
        when (c.action) {
            DeviceCommand.VolumeAction.UP -> am.setStreamVolume(stream, (am.getStreamVolume(stream) + maxOf(1, max / 7)).coerceAtMost(max), flags)
            DeviceCommand.VolumeAction.DOWN -> am.setStreamVolume(stream, (am.getStreamVolume(stream) - maxOf(1, max / 7)).coerceAtLeast(0), flags)
            DeviceCommand.VolumeAction.SET -> am.setStreamVolume(stream, Math.round(max * (c.percent ?: 50) / 100f), flags)
            DeviceCommand.VolumeAction.MUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
            DeviceCommand.VolumeAction.UNMUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
            DeviceCommand.VolumeAction.VIBRATE -> am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            DeviceCommand.VolumeAction.RINGER -> am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            DeviceCommand.VolumeAction.SILENT -> {
                // Silent needs the Do Not Disturb access on Android 7+
                if (!context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) {
                    return Outcome.NeedsAccess(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                        ReplyLanguage.t("Para silenciar el móvil necesito el acceso a No molestar: activa Lumi y vuelve a pedírmelo.", "To silence the phone I need Do Not Disturb access: enable Lumi and ask me again."))
                }
                am.ringerMode = AudioManager.RINGER_MODE_SILENT
            }
        }
        return Outcome.Done()
    }

    private fun brightness(context: Context, c: DeviceCommand.Brightness): Outcome {
        if (!Settings.System.canWrite(context)) {
            return Outcome.NeedsAccess(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")),
                ReplyLanguage.t("Para cambiar el brillo necesito el permiso «Modificar ajustes del sistema»: actívalo para Lumi y vuelve a pedírmelo.", "To change the brightness I need the «Modify system settings» permission: enable it for Lumi and ask me again.")
            )
        }
        val resolver = context.contentResolver
        val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = when (c.action) {
            DeviceCommand.VolumeAction.UP -> current + 40
            DeviceCommand.VolumeAction.DOWN -> current - 40
            else -> Math.round(255 * (c.percent ?: 50) / 100f)
        }.coerceIn(5, 255)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)
        return Outcome.Done(ReplyLanguage.t("Brillo al ${target * 100 / 255} %.", "Brightness at ${target * 100 / 255}%."))
    }

    private fun media(context: Context, action: DeviceCommand.MediaAction): Outcome {
        val am = context.getSystemService(AudioManager::class.java)
        val key = when (action) {
            DeviceCommand.MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            DeviceCommand.MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            DeviceCommand.MediaAction.TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            DeviceCommand.MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            DeviceCommand.MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return Outcome.Done()
    }

    private fun batteryLine(context: Context): String {
        val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0) return ReplyLanguage.t("No puedo leer la batería.", "I can't read the battery.")
        val pct = level * 100 / scale
        return ReplyLanguage.t("Batería al $pct %" + if (charging) ", cargando." else ", sin cargar.", "Battery at $pct%" + if (charging) ", charging." else ", not charging.")
    }

    private fun storageLine(): String {
        val st = StatFs(Environment.getDataDirectory().path)
        val free = st.availableBytes / 1e9
        val total = st.totalBytes / 1e9
        return ReplyLanguage.t("Almacenamiento: %.1f GB libres de %.0f GB.", "Storage: %.1f GB free of %.0f GB.").format(free, total)
    }

    private fun phoneInfo(context: Context, kind: DeviceCommand.InfoKind): String = when (kind) {
        DeviceCommand.InfoKind.BATTERY -> batteryLine(context)
        DeviceCommand.InfoKind.STORAGE -> storageLine()
        DeviceCommand.InfoKind.STATUS -> {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            val net = when {
                caps == null -> ReplyLanguage.t("sin conexión", "offline")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ReplyLanguage.t("datos móviles", "mobile data")
                else -> ReplyLanguage.t("conectado", "connected")
            }
            val dnd = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            batteryLine(context) + " " + ReplyLanguage.t("Red: $net. ", "Network: $net. ") + storageLine() + " " +
                ReplyLanguage.t(if (dnd) "No molestar activado." else "No molestar desactivado.", if (dnd) "Do Not Disturb is on." else "Do Not Disturb is off.")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun shareLocation(context: Context): Outcome {
        if (!granted(context, Manifest.permission.ACCESS_FINE_LOCATION) && !granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return Outcome.NeedsPermission(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        val lm = context.getSystemService(LocationManager::class.java)
        val loc = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            ?: return Outcome.Failed(ReplyLanguage.t("Aún no sé dónde estás: abre un mapa un momento y vuelve a pedírmelo.", "I don't know where you are yet: open a map for a moment and ask me again."))
        // a cached fix from hours ago would send a contact the wrong place
        if (System.currentTimeMillis() - loc.time > 10 * 60_000L) {
            return Outcome.Failed(ReplyLanguage.t("Mi última ubicación es antigua: abre un mapa un momento y vuelve a pedírmelo.", "My last location is out of date: open a map for a moment and ask me again."))
        }
        val link = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ReplyLanguage.t("Estoy aquí: ", "I am here: ") + link)
        return start(context, Intent.createChooser(send, null))
    }

    // ── Utilities ───────────────────────────────────────────────────────────

    private fun start(context: Context, intent: Intent): Outcome = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Outcome.Done()
    } catch (e: ActivityNotFoundException) {
        Outcome.Failed(ReplyLanguage.t("No hay ninguna app instalada que pueda hacerlo.", "There's no installed app that can do that."))
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
