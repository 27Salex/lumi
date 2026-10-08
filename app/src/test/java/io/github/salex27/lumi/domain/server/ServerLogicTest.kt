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
        assertEquals(listOf(ServerProfile("default", "My PC", "pc.tail.ts.net", "tok", "https", "pc.tail.ts.net")), s.servers)
        assertEquals("default", s.hub)
        assertEquals("default", s.pcview)
        assertNull(s.search) // web search stays on the phone
    }

    @Test fun `a searxng address the user had chosen becomes the search server`() {
        val s = migrate(searx = "https://searx.example.org", chosen = true)
        assertEquals("searxng", s.search)
        assertEquals("https://searx.example.org:443", ServerLogic.baseUrl(s, ServerService.SEARCH))
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

    @Test fun `splitUrl accepts host, host and port, and full urls with a path`() {
        assertEquals(ParsedUrl("https", "a.ts.net", null, ""), ServerLogic.splitUrl("a.ts.net"))
        assertEquals(ParsedUrl("https", "a.ts.net", 8443, ""), ServerLogic.splitUrl(" https://a.ts.net:8443/ "))
        assertEquals(ParsedUrl("http", "10.0.0.2", 8444, "/s"), ServerLogic.splitUrl("http://10.0.0.2:8444/s/"))
        assertNull(ServerLogic.splitUrl("ftp://a.ts.net")); assertNull(ServerLogic.splitUrl("a b")); assertNull(ServerLogic.splitUrl("a:99999"))
    }

    @Test fun `1_1_4 servers with a port migrate to host plus per service port`() {
        val old = ServerState(
            listOf(ServerProfile("default", "PC", "https://laptop.ts.net:8443", "tok"), ServerProfile("searxng", "S", "https://laptop.ts.net:8444")),
            hub = "default", search = "searxng", pcview = "default"
        )
        val s = ServerLogic.decode(ServerLogic.encode(old).replace("\"ver\":2", "\"ver\":1"))!!
        assertEquals("laptop.ts.net", s.servers[0].host)
        assertEquals("https://laptop.ts.net:8443", ServerLogic.baseUrl(s, ServerService.HUB))
        assertEquals("https://laptop.ts.net:8443", ServerLogic.baseUrl(s, ServerService.PCVIEW))
        assertEquals("https://laptop.ts.net:8444", ServerLogic.baseUrl(s, ServerService.SEARCH))
        assertEquals("https://laptop.ts.net:8443", old.servers[0].url.let { ServerLogic.baseUrl(ServerLogic.upgrade(old), ServerService.HUB) })
        assertEquals("https://laptop.ts.net:8443", s.servers[0].url) // legacy url kept: no data loss
    }

    @Test fun `an old address without a port keeps meaning 443 and defaults apply to new servers`() {
        val s = ServerLogic.upgrade(ServerState(listOf(ServerProfile("d", "PC", "pc.ts.net", "t")), hub = "d", pcview = "d"))
        assertEquals("https://pc.ts.net:443", ServerLogic.baseUrl(s, ServerService.HUB))
        val fresh = ServerLogic.useForAll(ServerState(listOf(ServerProfile("a", "A", host = "h.ts.net"))), "a")
        assertEquals("https://h.ts.net:8443", ServerLogic.baseUrl(fresh, ServerService.HUB))
        assertEquals("https://h.ts.net:8443", ServerLogic.baseUrl(fresh, ServerService.PCVIEW)) // My PC follows the hub
        assertEquals("https://h.ts.net:8444", ServerLogic.baseUrl(fresh, ServerService.SEARCH))
    }

    @Test fun `search path is used only by search and each service keeps its own port`() {
        var s = ServerLogic.useForAll(ServerState(listOf(ServerProfile("a", "A", host = "h.ts.net"))), "a")
        s = ServerLogic.setEndpoint(s, ServerService.SEARCH, ServiceEndpoint(9000, "searx"))
        s = ServerLogic.setEndpoint(s, ServerService.HUB, ServiceEndpoint(9443, "ignored"))
        assertEquals("https://h.ts.net:9000/searx", ServerLogic.baseUrl(s, ServerService.SEARCH))
        assertEquals("https://h.ts.net:9443", ServerLogic.baseUrl(s, ServerService.HUB))
        assertEquals("https://h.ts.net:9443", ServerLogic.baseUrl(s, ServerService.PCVIEW))
        assertNull(ServerLogic.baseUrl(ServerState(), ServerService.HUB))
    }
}
