package io.github.salex27.lumi.data.ai

import io.github.salex27.lumi.domain.model.TaskCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryHeuristicsTest {

    @Test
    fun `detects work ignoring accents and case`() {
        assertEquals(TaskCategory.WORK, CategoryHeuristics.infer("Reunión con el CLIENTE a las 10"))
    }

    @Test
    fun `detects study`() {
        assertEquals(TaskCategory.STUDY, CategoryHeuristics.infer("Estudiar para el examen de física"))
    }

    @Test
    fun `workout is health, not work`() {
        assertEquals(TaskCategory.HEALTH, CategoryHeuristics.infer("30 min de workout"))
    }

    @Test
    fun `detects personal`() {
        assertEquals(TaskCategory.PERSONAL, CategoryHeuristics.infer("Comprar leche en el supermercado"))
    }

    @Test
    fun `doesn't confuse short prefixes with longer words`() {
        // "calle" must not trigger WORK, "unión" must not trigger STUDY
        assertNull(CategoryHeuristics.infer("Cruzar la calle de la unión"))
    }

    @Test
    fun `empty text returns null`() {
        assertNull(CategoryHeuristics.infer("   "))
    }
}
