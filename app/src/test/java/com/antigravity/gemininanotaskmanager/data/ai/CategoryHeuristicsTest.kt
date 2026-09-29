package com.antigravity.gemininanotaskmanager.data.ai

import com.antigravity.gemininanotaskmanager.domain.model.TaskCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryHeuristicsTest {

    @Test
    fun `detecta trabajo ignorando tildes y mayúsculas`() {
        assertEquals(TaskCategory.WORK, CategoryHeuristics.infer("Reunión con el CLIENTE a las 10"))
    }

    @Test
    fun `detecta estudios`() {
        assertEquals(TaskCategory.STUDY, CategoryHeuristics.infer("Estudiar para el examen de física"))
    }

    @Test
    fun `workout es salud y no trabajo`() {
        assertEquals(TaskCategory.HEALTH, CategoryHeuristics.infer("30 min de workout"))
    }

    @Test
    fun `detecta personal`() {
        assertEquals(TaskCategory.PERSONAL, CategoryHeuristics.infer("Comprar leche en el supermercado"))
    }

    @Test
    fun `no confunde prefijos cortos con palabras más largas`() {
        // "calle" no debe activar WORK, "unión" no debe activar STUDY
        assertNull(CategoryHeuristics.infer("Cruzar la calle de la unión"))
    }

    @Test
    fun `texto vacío devuelve null`() {
        assertNull(CategoryHeuristics.infer("   "))
    }
}
