package io.github.salex27.lumi.domain.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ServerLogicTest {
    private fun migrate(addr: String = "pc.tail.ts.net", token: String = "tok", searx: String = "", chosen: Boolean = false) =
        ServerLogic.migrate(addr, token, searx, chosen, "My PC", "SearXNG")

    @Test fun `old hub settings become the default server used for hub and my pc`() {
        val s = migrate()
        assertEquals(listOf(ServerProfile("default", "My PC", "pc.tail.ts.net", "tok")), s.servers)
        assertEquals("default", s.hub)
        assertEquals("default", s.pcview)
        assertNull(s.search) // web search stays on the phone
    }

    @Test fun `a searxng address the user had chosen becomes the search server`() {
        val s = migrate(searx = "https://searx.example.org", chosen = true)
        assertEquals("searxng", s.search)
        assertEquals("https://searx.example.org", s.serverFor(ServerService.SEARCH)?.url)
        assertEquals("default", s.hub)
    }

    @Test fun `an unused searxng address is not assigned`() {
        assertNull(migrate(searx = "https://x.org", chosen = false).search)
    }

    @Test fun `nothing configured migrates to nothing`() {
        val s = migrate("", "")
        assertTrue(s.servers.isEmpty()); assertNull(s.hub); assertNull(s.pcview)
    }

    @Test fun `assign ignores unknown ids and use for all points every service at one server`() {
        val s = ServerLogic.upsert(migrate(), ServerProfile("b", "Laptop", "laptop.ts.net", "t2"))
        assertNull(ServerLogic.assign(s, ServerService.SEARCH, "nope").search)
        val all = ServerLogic.useForAll(s, "b")
        assertEquals(listOf("b", "b", "b"), listOf(all.hub, all.search, all.pcview))
        assertEquals(s, ServerLogic.useForAll(s, "missing"))
    }

    @Test fun `removing a server unassigns its services`() {
        val s = ServerLogic.useForAll(ServerLogic.upsert(migrate(), ServerProfile("b", "L", "l.ts.net")), "b")
        val r = ServerLogic.remove(s, "b")
        assertEquals(listOf("default"), r.servers.map { it.id })
        assertEquals(listOf(null, null, null), listOf(r.hub, r.search, r.pcview))
    }

    @Test fun `the first server added becomes hub and my pc, later ones do not`() {
        val first = ServerLogic.upsert(ServerState(), ServerProfile("s1", "A", "a"))
        assertEquals("s1", first.hub); assertEquals("s1", first.pcview); assertNull(first.search)
        val second = ServerLogic.upsert(first, ServerProfile("s2", "B", "b"))
        assertEquals("s1", second.hub)
        assertEquals("s3", ServerLogic.newId(second))
    }

    @Test fun `state round trips through json`() {
        val s = migrate(searx = "https://x.org", chosen = true)
        assertEquals(s, ServerLogic.decode(ServerLogic.encode(s)))
        assertNull(ServerLogic.decode("not json"))
    }

    @Test fun `search urls accept lan http but not junk`() {
        assertEquals("https://searx.example.org", ServerLogic.normalizeSearchUrl(" searx.example.org/ "))
        assertEquals("http://192.168.1.5:8080", ServerLogic.normalizeSearchUrl("http://192.168.1.5:8080"))
        assertNull(ServerLogic.normalizeSearchUrl("ftp://x")); assertNull(ServerLogic.normalizeSearchUrl("https://a b")); assertNull(ServerLogic.normalizeSearchUrl(""))
    }

    @Test fun `test errors are classified`() {
        assertEquals(ServerTestError.UNAUTHORIZED, ServerTestErrors.classify(401, null))
        assertEquals(ServerTestError.FORBIDDEN, ServerTestErrors.classify(403, null))
        assertEquals(ServerTestError.NOT_FOUND, ServerTestErrors.classify(404, null))
        assertEquals(ServerTestError.SERVER_ERROR, ServerTestErrors.classify(502, null))
        assertEquals(ServerTestError.TIMEOUT, ServerTestErrors.classify(null, SocketTimeoutException()))
        assertEquals(ServerTestError.UNREACHABLE, ServerTestErrors.classify(null, UnknownHostException("x")))
        assertEquals(ServerTestError.UNREACHABLE, ServerTestErrors.classify(null, ConnectException()))
        assertEquals(ServerTestError.TLS, ServerTestErrors.classify(null, SSLHandshakeException("x")))
        assertEquals(ServerTestError.BAD_ANSWER, ServerTestErrors.classify(null, IllegalArgumentException()))
        assertNotEquals(ServerTestError.OTHER, ServerTestErrors.classify(null, UnknownHostException()))
    }

    @Test fun `backoff doubles up to the cap and jitter only shortens`() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 8000L), (0..4).map { Backoff.delayMs(it, 1000, 8000) })
        assertEquals(30_000L, Backoff.delayMs(50))
        assertEquals(750L, Backoff.delayMs(0, jitter = 1.0))
        assertTrue(Backoff.delayMs(3, jitter = 0.5) in 6_000L..8_000L)
    }

    @Test fun `turn streams join chunks keep spaces and are replaced by finish`() {
        val t = TurnStreams(maxTurns = 2, maxChars = 20)
        assertEquals("Hello", t.append("a", "Hello"))
        assertEquals("Hello world", t.append("a", " world"))
        assertEquals("Hello world", t.current("a"))
        assertEquals("12345678901234567890", t.append("b", "1234567890123456789012345")) // capped
        t.append("c", "x") // evicts the oldest turn
        assertNull(t.current("a"))
        t.finish("b"); assertNull(t.current("b"))
    }
}
