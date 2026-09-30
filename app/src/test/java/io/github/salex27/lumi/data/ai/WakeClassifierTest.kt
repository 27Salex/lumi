package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.service.wakeword.WakeClassifier
import io.github.salex27.lumi.service.wakeword.WakeTrigger
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin wake word detector ("Hey Lumi" / "Oye Lumi") must match PyTorch (cases exported by train.py). */
class WakeClassifierTest {

    private fun resource(path: String) = javaClass.classLoader!!.getResourceAsStream(path)!!

    @Test
    fun `same scores as the model trained in Python`() {
        val classifier = resource("oww/oye_lumi.bin").use { WakeClassifier.load(it) }
        val cases = JSONArray(resource("oww/parity.json").bufferedReader().readText())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val xs = c.getJSONArray("x")
            val x = FloatArray(xs.length()) { xs.getDouble(it).toFloat() }
            assertEquals("case $i", c.getDouble("y"), classifier.score(x).toDouble(), 1e-4)
        }
    }

    @Test
    fun `patience requires several windows in a row`() {
        val t = WakeTrigger(0.5f, 2)
        assertFalse(t.update(0.9f))
        assertTrue(t.update(0.8f))      // 2 in a row → triggers
        assertFalse(t.update(0.9f))     // resets after triggering
        assertFalse(t.update(0.2f))
        assertFalse(t.update(null))
    }
}
