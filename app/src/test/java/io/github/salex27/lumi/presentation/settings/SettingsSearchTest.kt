package io.github.salex27.lumi.presentation.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    @Test fun `search matches words in any order, ignoring case and accents`() {
        assertTrue(settingsMatches("", "x"))
        assertTrue(settingsMatches("NOTIF", "Notifications & reminders", "Work hours"))
        assertTrue(settingsMatches("alarma inteligente", "Notificaciones", "recordatorio alarma inteligente"))
        assertTrue(settingsMatches("manana", "resumen de la mañana"))
        assertFalse(settingsMatches("tailscale zzz", "Servers", "tailscale hub"))
    }
}
