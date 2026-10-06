package io.github.salex27.lumi.domain.places

import io.github.salex27.lumi.data.ai.RuleBasedEngine
import io.github.salex27.lumi.domain.model.TaskAICommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NearbyIntentTest {

    private fun cat(s: String) = NearbyIntent.parse(s)?.category

    @Test fun spanishPhrases() {
        assertEquals(NearbyCategory.FOOD, cat("donde puedo comer barato"))
        assertEquals(NearbyCategory.FOOD, cat("¿Dónde puedo comer barato?"))
        assertEquals(NearbyCategory.PHARMACY, cat("una farmacia cerca"))
        assertEquals(NearbyCategory.PHARMACY, cat("busca una farmacia cerca"))
        assertEquals(NearbyCategory.GAS, cat("dónde hay una gasolinera"))
        assertTrue(NearbyIntent.parse("donde puedo comer barato")!!.cheap)
    }

    @Test fun englishPhrases() {
        assertEquals(NearbyCategory.FOOD, cat("where can i eat cheap near me"))
        assertEquals(NearbyCategory.PHARMACY, cat("find a pharmacy nearby"))
        assertEquals(NearbyCategory.SUPERMARKET, cat("where can I buy groceries"))
        assertEquals(NearbyCategory.COFFEE, cat("closest coffee"))
        assertTrue(NearbyIntent.parse("where can i eat cheap near me")!!.cheap)
    }

    @Test fun typosAreTolerated() {
        assertEquals(NearbyCategory.FOOD, cat("donde pudo comer barato"))
        assertEquals(NearbyCategory.FOOD, cat("dnde puedo comeer barato"))
        assertEquals(NearbyCategory.FOOD, cat("donde puedo comer baraot"))
        assertEquals(NearbyCategory.PHARMACY, cat("buscame una farmcia serca".replace("serca", "cerca")))
        assertEquals(NearbyCategory.PHARMACY, cat("find a pharmcy nerby".replace("nerby", "nearby")))
        assertEquals(NearbyCategory.FOOD, cat("wheer can i eat"))
    }

    @Test fun notPlaceRequests() {
        assertNull(NearbyIntent.parse("recuérdame comprar pan mañana"))
        assertNull(NearbyIntent.parse("dónde puedo comprar un coche"))
        assertNull(NearbyIntent.parse("remind me to call the pharmacy"))
        assertNull(NearbyIntent.parse("cuál es la mejor empresa de trenes de España"))
        assertNull(NearbyIntent.parse("what is the best bar chart library"))
    }

    @Test fun rulesEngineUnderstandsBothLanguages() {
        val rules = RuleBasedEngine()
        val now = LocalDateTime.of(2026, 10, 6, 12, 0)
        for (s in listOf("donde pudo comer barato", "donde puedo comer barato", "where can i eat cheap near me", "una farmacia cerca")) {
            val c = rules.parse(s, now)
            assertEquals(s, TaskAICommand.NEARBY, c.action)
            assertNotNull(c.targetTitle)
        }
    }

    @Test fun overpassParsingSortsByDistanceAndSanitizes() {
        val json = """{"elements":[
          {"type":"node","lat":41.0010,"lon":2.0,"tags":{"name":"Far","amenity":"restaurant","cuisine":"pizza"}},
          {"type":"way","center":{"lat":41.0001,"lon":2.0},"tags":{"name":"Near\u0007 One","amenity":"fast_food","opening_hours":"24/7"}},
          {"type":"node","lat":41.0002,"lon":2.0,"tags":{"amenity":"cafe"}}]}"""
        val places = NearbyIntent.parseOverpass(json, 41.0, 2.0)
        assertEquals(listOf("Near One", "Far"), places.map { it.name })
        assertEquals(true, places[0].open)
        assertTrue(places[0].distanceM in 5..20)
        assertTrue(NearbyIntent.parseOverpass("not json", 0.0, 0.0).isEmpty())
    }

    @Test fun overpassQueryTargetsCategory() {
        val q = NearbyIntent.parse("donde puedo comer barato")!!
        val ql = NearbyIntent.overpass(q, 41.5, 2.1, 1500)
        assertTrue(ql.contains("around:1500,41.50000,2.10000") && ql.contains("fast_food"))
    }
}
