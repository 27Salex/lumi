package io.github.salex27.lumi.data.search

import io.github.salex27.lumi.domain.assistant.Lang
import io.github.salex27.lumi.domain.search.WebAnswers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-device search parsers, against responses saved from the real services (src/test/resources/search). */
class DeviceSearchParsersTest {
    private fun resource(name: String) = DeviceSearchParsersTest::class.java.getResourceAsStream("/search/$name")!!.bufferedReader().readText()

    @Test fun `duckduckgo html results are unwrapped from the redirect with snippets`() {
        val hits = SearchParsers.ddgHtml(resource("ddg_html_sample.html"))
        assertEquals(3, hits.size)
        assertEquals("https://en.wikipedia.org/wiki/2025_Tour_de_France", hits[0].url)
        assertEquals("2025 Tour de France - Wikipedia", hits[0].title)
        assertTrue(hits[0].snippet.startsWith("The 2025 Tour de France was the 112th edition"))
        assertTrue(hits[1].title.endsWith("Records & History")) // entities decoded
        assertTrue(hits[1].url.startsWith("https://cyclingarchives.com/tour-de-france-winners"))
        assertTrue(hits.none { it.url.contains("duckduckgo.com") || it.snippet.contains("<b>") })
        assertEquals(3, WebAnswers.clean(hits).size)
    }

    @Test fun `ads, captcha pages and junk give no hits instead of crashing`() {
        val ad = """<a class="result__a" href="//duckduckgo.com/y.js?ad_provider=x&u3=https%3A%2F%2Fad.example">Ad</a><a class="result__snippet" href="x">ad</a>"""
        assertTrue(SearchParsers.ddgHtml(ad).isEmpty())
        assertTrue(SearchParsers.ddgHtml("<html><body>Unfortunately, bots use DuckDuckGo too.</body></html>").isEmpty())
        assertTrue(SearchParsers.ddgHtml("").isEmpty())
        assertEquals("https://example.org/a?b=1", SearchParsers.ddgTarget("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fa%3Fb%3D1&amp;rut=zz"))
        assertEquals("https://direct.example/", SearchParsers.ddgTarget("https://direct.example/"))
        assertNull(SearchParsers.ddgTarget("javascript:alert(1)"))
    }

    @Test fun `duckduckgo instant answer gives the abstract first`() {
        val hits = SearchParsers.ddgInstant(resource("ddg_instant_sample.json"))
        assertTrue(hits.isNotEmpty())
        assertEquals("Python (programming language)", hits[0].title)
        assertEquals("https://en.wikipedia.org/wiki/Python_(programming_language)", hits[0].url)
        assertTrue(hits[0].snippet.startsWith("Python is a high-level"))
        assertTrue(SearchParsers.ddgInstant("""{"AbstractText":"","RelatedTopics":[]}""").isEmpty())
    }

    @Test fun `urls carry the query and the language`() {
        assertTrue(SearchParsers.ddgHtmlUrl("a b", Lang.ES).contains("q=a+b&kl=es-es"))
        assertTrue(SearchParsers.ddgInstantUrl("x").startsWith("https://api.duckduckgo.com/?q=x&format=json"))
    }
}
