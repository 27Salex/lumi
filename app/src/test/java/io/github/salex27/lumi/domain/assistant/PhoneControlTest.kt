package io.github.salex27.lumi.domain.assistant

import io.github.salex27.lumi.domain.assistant.DeviceCommand as D
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControlTest {

    private val now = LocalDateTime.of(2026, 10, 8, 12, 0)
    private fun p(s: String) = PhoneControl.parse(s, now)
    private fun alarms(s: String) = (p(s) as? D.AlarmSet)

    // ── Alarm sets ──

    @Test fun scheduleByCount() {
        assertEquals(listOf(420, 430, 440), AlarmSetParser.schedule(420, 10, 3, null))
    }

    @Test fun scheduleByEndIsInclusiveAndCapped() {
        assertEquals(listOf(420, 435, 450, 465, 480), AlarmSetParser.schedule(420, 15, null, 480))
        assertEquals(AlarmSetParser.MAX_ALARMS, AlarmSetParser.schedule(0, 1, 99, null).size)
    }

    @Test fun scheduleWrapsPastMidnight() {
        assertEquals(listOf(23 * 60 + 50, 0), AlarmSetParser.schedule(23 * 60 + 50, 10, null, 0))
    }

    @Test fun spanishCountAndInterval() {
        val a = alarms("me levanto a las 7, pon 3 alarmas cada 10 minutos")!!
        assertEquals(listOf(420, 430, 440), a.times)
        assertNull(a.snooze)
    }

    @Test fun spanishFromUntil() {
        assertEquals(listOf(420, 435, 450, 465, 480), alarms("pon alarmas desde las 7 cada 15 minutos hasta las 8")!!.times)
    }

    @Test fun englishNoSnooze() {
        val a = alarms("wake me at 6:30 with 4 alarms every 5 minutes, no snooze")!!
        assertEquals(listOf(390, 395, 400, 405), a.times)
        assertEquals(false, a.snooze)
    }

    @Test fun englishWithSnoozeAndTypo() {
        val a = alarms("wake me at 7 am with 2 alarmss every ten minutes with snooze")!!
        assertEquals(listOf(420, 430), a.times)
        assertEquals(true, a.snooze)
    }

    @Test fun spanishWordsAndHalfHour() {
        assertEquals(listOf(420, 450, 480), alarms("ponme tres alarmas a las 7 cada media hora")!!.times)
        assertEquals(listOf(360, 365), alarms("pon dos alarmas a las 6:00 cada cinco minutos sin snooze")!!.times)
    }

    @Test fun singleAlarmIsNotASet() {
        assertNull(alarms("pon una alarma a las 7"))
        assertNull(alarms("set an alarm for 7"))
    }

    @Test fun cancelAlarms() {
        assertEquals(D.CancelAlarms, p("quita las alarmas de mañana"))
        assertEquals(D.CancelAlarms, p("cancel the alarms"))
        assertEquals(D.CancelAlarms, p("borra mis alarmas"))
        assertNull(p("borra la tarea de la alarma"))
    }

    // ── Bedtime ──

    @Test fun bedtimeSpanish() {
        assertEquals(D.BedtimeReminder(23 * 60 + 30, 127), p("recuérdame cada día a las 23:30 que me acueste"))
    }

    @Test fun bedtimeEnglishPmAndWeekdays() {
        assertEquals(D.BedtimeReminder(23 * 60 + 30, 127), p("remind me to go to bed at 11:30 every day"))
        assertEquals(D.BedtimeReminder(22 * 60, 0b0011111), p("remind me to go to bed at 10 pm on weekdays"))
        assertEquals(D.BedtimeReminder(23 * 60, 0b0000011), p("recuérdame ir a dormir a las 11 todos los lunes y martes"))
    }

    @Test fun bedtimeNeedsRecurrence() {
        assertNull(p("recuérdame acostarme a las 23:30"))
    }

    @Test fun bedtimeNextDay() {
        // Monday 22:00, reminder at 23:00 on Mon..Fri -> today; after 23:00 -> tomorrow; Friday late -> Monday
        assertEquals(0, BedtimeParser.nextDayOffset(0b0011111, 0, 22 * 60, 23 * 60))
        assertEquals(1, BedtimeParser.nextDayOffset(0b0011111, 0, 23 * 60 + 1, 23 * 60))
        assertEquals(3, BedtimeParser.nextDayOffset(0b0011111, 4, 23 * 60 + 1, 23 * 60))
        assertNull(BedtimeParser.nextDayOffset(0, 0, 0, 60))
    }

    // ── Volume, brightness, media ──

    @Test fun volume() {
        assertEquals(D.Volume(D.VolumeAction.UP, null), p("sube el volumen"))
        assertEquals(D.Volume(D.VolumeAction.DOWN, null), p("turn down the volume"))
        assertEquals(D.Volume(D.VolumeAction.SET, 50), p("pon el volumen al 50%"))
        assertEquals(D.Volume(D.VolumeAction.SET, 30), p("set the volume to 30"))
        assertEquals(D.Volume(D.VolumeAction.SET, 100), p("volumen al máximo"))
        assertEquals(D.Volume(D.VolumeAction.MUTE, null), p("silencia el sonido"))
        assertEquals(D.Volume(D.VolumeAction.VIBRATE, null), p("pon el móvil en vibración"))
        assertEquals(D.Volume(D.VolumeAction.SILENT, null), p("pon el móvil en silencio"))
        assertEquals(D.Volume(D.VolumeAction.UP, null), p("sube el volumne"))
    }

    @Test fun existingSilencePhrasesStayDoNotDisturb() {
        assertNull(p("silencia el móvil"))
        assertNull(p("silence my phone"))
        assertEquals(D.DoNotDisturb(true), DeviceCommandParser.parse("silencia el móvil"))
    }

    @Test fun brightness() {
        assertEquals(D.Brightness(D.VolumeAction.UP, null), p("sube el brillo"))
        assertEquals(D.Brightness(D.VolumeAction.DOWN, null), p("turn down the brightness"))
        assertEquals(D.Brightness(D.VolumeAction.SET, 40), p("pon el brillo al 40%"))
        assertEquals(D.Brightness(D.VolumeAction.SET, 100), p("brightness to max"))
    }

    @Test fun media() {
        assertEquals(D.Media(D.MediaAction.PAUSE), p("pausa la música"))
        assertEquals(D.Media(D.MediaAction.PAUSE), p("pause"))
        assertEquals(D.Media(D.MediaAction.NEXT), p("siguiente canción"))
        assertEquals(D.Media(D.MediaAction.NEXT), p("skip the song"))
        assertEquals(D.Media(D.MediaAction.PREVIOUS), p("canción anterior"))
        assertEquals(D.Media(D.MediaAction.PLAY), p("reanuda la música"))
        assertNull(p("pon música de Queen"))
    }

    // ── Info, settings, others ──

    @Test fun info() {
        assertEquals(D.PhoneInfo(D.InfoKind.BATTERY), p("cuánta batería me queda"))
        assertEquals(D.PhoneInfo(D.InfoKind.BATTERY), p("how much battery do I have"))
        assertEquals(D.PhoneInfo(D.InfoKind.BATTERY), p("cuanta bateira tengo"))
        assertEquals(D.PhoneInfo(D.InfoKind.STORAGE), p("cuánto espacio libre me queda"))
        assertEquals(D.PhoneInfo(D.InfoKind.STATUS), p("what's my phone doing"))
        assertNull(p("recuérdame cargar la batería"))
    }

    @Test fun panels() {
        assertEquals(D.OpenSettings(D.SettingsPanel.AIRPLANE), p("activa el modo avión"))
        assertEquals(D.OpenSettings(D.SettingsPanel.HOTSPOT), p("turn on the hotspot"))
        assertEquals(D.OpenSettings(D.SettingsPanel.LOCATION), p("desactiva la ubicación"))
        assertEquals(D.OpenSettings(D.SettingsPanel.NFC), p("enable NFC"))
        assertEquals(D.OpenSettings(D.SettingsPanel.WIFI), p("enciende el wifi"))
        assertEquals(D.OpenSettings(D.SettingsPanel.BLUETOOTH), p("turn off bluetooth"))
        assertEquals(D.OpenSettings(D.SettingsPanel.MOBILE_DATA), p("open mobile data"))
    }

    @Test fun shareLocationStopwatchAndCalendar() {
        assertEquals(D.ShareLocation(false), p("comparte mi ubicación"))
        assertEquals(D.ShareLocation(false), p("share my location"))
        assertEquals(D.Stopwatch, p("pon el cronómetro"))
        val e = p("crea un evento mañana a las 17:00 con Ana en el calendario") as D.CalendarEvent
        assertEquals("2026-10-09T17:00", e.startIso)
        assertTrue(e.title.contains("Ana"))
        val en = p("add an event to my calendar tomorrow at 3pm called dentist") as D.CalendarEvent
        assertEquals("2026-10-09T15:00", en.startIso)
    }

    @Test fun serializeRoundTrip() {
        listOf(
            D.AlarmSet(listOf(420, 430), false), D.AlarmSet(listOf(60), null), D.CancelAlarms, D.BedtimeReminder(1410, 31),
            D.Volume(D.VolumeAction.SET, 40), D.Brightness(D.VolumeAction.UP, null), D.Media(D.MediaAction.NEXT),
            D.PhoneInfo(D.InfoKind.STORAGE), D.CalendarEvent("Dentist | 5", "2026-10-09T17:00"), D.Stopwatch, D.ShareLocation(true),
            D.OpenSettings(D.SettingsPanel.NFC)
        ).forEach { assertEquals(it, D.parse(it.serialize())) }
    }

    @Test fun alarmLog() {
        val list = listOf(AlarmLog.Entry(420, 1_000L), AlarmLog.Entry(430, 90_000_000L))
        assertEquals(list, AlarmLog.decode(AlarmLog.encode(list)))
        assertEquals(listOf(AlarmLog.Entry(430, 90_000_000L)), AlarmLog.live(list, 1_000L + AlarmLog.KEEP_MS + 1))
        assertEquals("07:00, 07:10", AlarmLog.summary(listOf(420, 430)))
    }
}
