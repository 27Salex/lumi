package io.github.salex27.lumi.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPrefsValidatorTest {
    @Test
    fun `known settings with the right type pass`() {
        assertTrue(BackupPrefsValidator.accept("assistant_settings", "work_start_hour", "i", "9"))
        assertTrue(BackupPrefsValidator.accept("assistant_settings", "theme_mode", "s", "DARK"))
    }

    @Test
    fun `wrong type, unknown key, secrets and odd values are rejected`() {
        assertFalse(BackupPrefsValidator.accept("assistant_settings", "work_start_hour", "s", "9"))
        assertFalse(BackupPrefsValidator.accept("assistant_settings", "evil_key", "b", "true"))
        assertFalse(BackupPrefsValidator.accept("assistant_settings", "cloud_api_key", "s", "x"))
        assertFalse(BackupPrefsValidator.accept("assistant_settings", "theme_mode", "s", "PINK"))
        assertFalse(BackupPrefsValidator.accept("other_file", "list", "s", "[]"))
    }

    @Test
    fun `routines must decode and stay small`() {
        val ok = """[{"id":"r1","name":"Night","triggers":["good night"],"steps":["turn on do not disturb"]}]"""
        assertTrue(BackupPrefsValidator.accept("routines", "list", "s", ok))
        assertFalse(BackupPrefsValidator.accept("routines", "list", "s", """{"a":1}"""))
        assertFalse(BackupPrefsValidator.accept("routines", "list", "i", "3"))
        val longStep = "x".repeat(400)
        assertFalse(BackupPrefsValidator.accept("routines", "list", "s", """[{"id":"r","name":"n","triggers":[],"steps":["$longStep"]}]"""))
    }

    @Test
    fun `places and aliases must be a json array`() {
        assertTrue(BackupPrefsValidator.accept("places", "list", "s", "[]"))
        assertFalse(BackupPrefsValidator.accept("places", "list", "s", "not json"))
    }
}
