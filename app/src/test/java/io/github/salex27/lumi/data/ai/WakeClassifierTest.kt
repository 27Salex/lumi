package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.service.wakeword.WakeClassifier
import io.github.salex27.lumi.service.wakeword.WakeTrigger
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El detector «Oye Lumi» en Kotlin debe dar lo mismo que en PyTorch (casos exportados por train.py). */
class WakeClassifierTest {

    private fun resource(path: String) = javaClass.classLoader!!.getResourceAsStream(path)!!

    @Test
    fun `mismas puntuaciones que el modelo entrenado en Python`() {
        val classifier = resource("oww/oye_lumi.bin").use { WakeClassifier.load(it) }
        val cases = JSONArray(resource("oww/parity.json").bufferedReader().readText())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val xs = c.getJSONArray("x")
            val x = FloatArray(xs.length()) { xs.getDouble(it).toFloat() }
            assertEquals("caso $i", c.getDouble("y"), classifier.score(x).toDouble(), 1e-4)
        }
    }

    @Test
    fun `la paciencia exige varias ventanas seguidas`() {
        val t = WakeTrigger(0.5f, 2)
        assertFalse(t.update(0.9f))
        assertTrue(t.update(0.8f))      // 2 seguidas → activa
        assertFalse(t.update(0.9f))     // se reinicia tras activar
        assertFalse(t.update(0.2f))
        assertFalse(t.update(null))
    }
}
