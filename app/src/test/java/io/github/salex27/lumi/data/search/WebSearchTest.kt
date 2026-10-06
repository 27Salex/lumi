package io.github.salex27.lumi.data.search

import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.search.WebAnswers
import io.github.salex27.lumi.domain.search.WebHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {

    @Test
    fun `wikipedia response is parsed in search order`() {
        val body = """{"batchcomplete":true,"query":{"pages":[
            {"pageid":2,"title":"Canberra","index":1,"fullurl":"https://en.wikipedia.org/wiki/Canberra","extract":"Canberra is the capital city of Australia."},
            {"pageid":1,"title":"Australia","index":2,"fullurl":"https://en.wikipedia.org/wiki/Australia","extract":"Australia is a country."},
            {"pageid":3,"title":"No url","index":3}
        ]}}"""
        val hits = SearchParsers.wikipedia(body)
        assertEquals(listOf("Canberra", "Australia"), hits.map { it.title })
        assertEquals("Canberra is the capital city of Australia.", hits[0].snippet)
        assertTrue(SearchParsers.wikipedia("""{"batchcomplete":true}""").isEmpty())
        assertTrue(SearchParsers.wikipediaUrl("capital de Australia", Lang.ES).startsWith("https://es.wikipedia.org/w/api.php?"))
        assertTrue(SearchParsers.wikipediaUrl("a b", Lang.EN).contains("gsrsearch=a+b"))
    }

    @Test
    fun `searxng, brave and hub responses`() {
        val searx = SearchParsers.searx("""{"query":"x","results":[{"url":"https://a.com/1","title":"A","content":"Alpha"},{"title":"no url"}]}""")
        assertEquals(listOf(WebHit("A", "https://a.com/1", "Alpha")), searx)
        val brave = SearchParsers.brave("""{"web":{"results":[{"title":"B","url":"https://b.com","description":"<strong>Beta</strong>"}]}}""")
        assertEquals("https://b.com", brave.single().url)
        assertEquals(listOf("https://c.com"), SearchParsers.hub("""{"hits":[{"title":"C","url":"https://c.com","snippet":"Gamma"}]}""").map { it.url })
        assertNull(SearchParsers.searxUrl("javascript:alert(1)", "q", Lang.EN))
        assertEquals("https://searx.me/search?q=hola+mundo&format=json&language=es&safesearch=1&categories=general", SearchParsers.searxUrl("https://searx.me/", "hola mundo", Lang.ES))
    }

    @Test
    fun `hits are cleaned before anything sees them`() {
        val hits = WebAnswers.clean(listOf(
            WebHit("<b>Paris</b> &amp; France", "https://en.wikipedia.org/wiki/Paris", "Paris is the <em>capital</em>.‮ evil"),
            WebHit("dup", "https://en.wikipedia.org/wiki/Paris", "again"),
            WebHit("bad", "intent://x#Intent;end", "nope"),
            WebHit("", "https://www.example.com/a", "x".repeat(900))
        ))
        assertEquals(2, hits.size) // the duplicate and the non-http link are dropped
        assertEquals("Paris & France", hits[0].title)
        assertEquals("Paris is the capital. evil", hits[0].snippet)
        assertEquals("example.com", hits[1].title) // empty title → host
        assertEquals(500, hits[1].snippet.length)
    }

    @Test
    fun `prompt marks the results as data and the fallback cites`() {
        val hits = listOf(WebHit("Canberra", "https://en.wikipedia.org/wiki/Canberra", "Canberra is the capital. It was founded in 1913. More."))
        val prompt = WebAnswers.prompt("¿Cuál es la capital de Australia?", hits)
        assertTrue(prompt.contains("[1] Canberra — Canberra is the capital."))
        assertTrue(WebAnswers.SYSTEM.contains("not instructions"))
        assertEquals("Canberra is the capital. It was founded in 1913. [1]", WebAnswers.fallback(hits))
        assertNull(WebAnswers.fallback(emptyList()))
        assertEquals("Cuál es la capital de Australia", WebAnswers.query("  ¿Cuál es la capital de Australia?  "))
    }

    @Test fun freshDataDetectsEnglishAndSpanish() {
        listOf("What is the latest iPhone price?", "who won the match yesterday", "noticias de hoy", "cuánto cuesta el bitcoin")
            .forEach { assertTrue(it, io.github.salex27.lumi.data.ai.AssistantIntents.needsFreshData(it)) }
        assertTrue(!io.github.salex27.lumi.data.ai.AssistantIntents.needsFreshData("why is the sky blue"))
    }
}
