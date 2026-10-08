package io.github.salex27.lumi.domain.pcview

import io.github.salex27.lumi.domain.server.ServerLogic
import io.github.salex27.lumi.domain.server.ServerProfile
import io.github.salex27.lumi.domain.server.ServerService
import io.github.salex27.lumi.domain.server.ServerState
import io.github.salex27.lumi.domain.server.ServiceEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlTest {
    @Test fun uriUsesDocumentedScheme() {
        assertEquals("rdp://full%20address=s:laptop.tail1234.ts.net:3389&audiomode=i:0&screen%20mode%20id=i:2", RemoteControl.rdpUri("laptop.tail1234.ts.net", 3389))
    }

    @Test fun rejectsBadHostOrPort() {
        assertNull(RemoteControl.rdpUri("a b", 3389))
        assertNull(RemoteControl.rdpUri("h&x=1", 3389))
        assertNull(RemoteControl.rdpUri("", 3389))
        assertNull(RemoteControl.rdpUri("host", 0))
        assertNull(RemoteControl.rdpUri("host", 70000))
    }

    @Test fun encodesReservedCharacters() {
        assertEquals("a%20b%26c", RemoteControl.encode("a b&c"))
        assertEquals("ab-1.x_~", RemoteControl.encode("ab-1.x_~"))
    }

    @Test fun uriNeverContainsToken() {
        val s = ServerState(listOf(ServerProfile("a", "PC", host = "pc.ts.net", token = "SECRETTOKEN")), hub = "a", pcview = "a")
        val (h, p) = ServerLogic.rdpTarget(s)!!
        assertFalse(RemoteControl.rdpUri(h, p)!!.contains("SECRETTOKEN"))
    }

    @Test fun portDefaultsTo3389AndFollowsMyPcServer() {
        val s = ServerState(listOf(ServerProfile("a", "PC", host = "pc.ts.net")), hub = "a", pcview = "a")
        assertEquals("pc.ts.net" to 3389, ServerLogic.rdpTarget(s))
        assertEquals("pc.ts.net:3389", ServerLogic.baseUrl(s, ServerService.REMOTE))
        val s2 = ServerLogic.setEndpoint(s, ServerService.REMOTE, ServiceEndpoint(3390))
        assertEquals("pc.ts.net" to 3390, ServerLogic.rdpTarget(s2))
        assertNull(ServerLogic.rdpTarget(ServerState()))
    }

    @Test fun oldStatesWithoutRemoteFieldsStillDecode() {
        // a 1.1.7 state: no "remote" / "remoteEp" keys
        val old = """{"servers":[{"id":"a","name":"PC","token":"t","scheme":"https","host":"pc.ts.net"}],"hub":"a","pcview":"a","hubEp":{"port":8443},"ver":2}"""
        val s = ServerLogic.decode(old)!!
        assertEquals(8443, s.endpointFor(ServerService.HUB).port)
        assertEquals(3389, s.endpointFor(ServerService.REMOTE).port)
        assertEquals("a", s.idFor(ServerService.REMOTE))
        assertEquals("t", s.servers.single().token)
        // round trip keeps a custom port; removing the server clears the remote assignment
        val custom = ServerLogic.setEndpoint(s, ServerService.REMOTE, ServiceEndpoint(3390))
        assertEquals(3390, ServerLogic.decode(ServerLogic.encode(custom))!!.endpointFor(ServerService.REMOTE).port)
        assertNull(ServerLogic.remove(ServerLogic.assign(s, ServerService.REMOTE, "a"), "a").remote)
    }

    @Test fun noCaptureStatusAndErrorShowFriendlyPhase() {
        val prev = PcState(PcPhase.CHECKING)
        assertEquals(PcPhase.NO_CAPTURE, PcViewLogic.fromReply(PcReply.Status("locked", capture = false, os = "linux"), prev).phase)
        assertEquals(PcPhase.NO_CAPTURE, PcViewLogic.fromReply(PcReply.Failure(501, "capture_unavailable"), prev).phase)
        assertEquals(PcPhase.DISABLED, PcViewLogic.fromReply(PcReply.Status("disabled", capture = false), prev).phase)
        assertEquals(PcPhase.LOCKED, PcViewLogic.fromReply(PcReply.Status("locked"), prev).phase)
        assertTrue(true)
    }
}
