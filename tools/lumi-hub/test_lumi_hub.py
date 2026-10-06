"""Unit + loopback tests for Lumi Hub. Run: python -m unittest discover tools/lumi-hub"""

import json
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import lumi_hub as h


def rpc(method, params=None, msg_id=1):
    message = {"jsonrpc": "2.0", "method": method, "id": msg_id}
    if params is not None:
        message["params"] = params
    return message


class SanitizingTest(unittest.TestCase):
    def test_text_is_capped_and_cleaned(self):
        self.assertEqual("hi there", h.clean_text("  hi\x00 there‮ ", 100, "text"))
        self.assertEqual(10, len(h.clean_text("x" * 50, 10, "text")))
        with self.assertRaises(h.HubError):
            h.clean_text("   ", 10, "text")
        self.assertIsNone(h.clean_text(None, 10, "due", required=False))
        with self.assertRaises(h.HubError):
            h.clean_text(42, 10, "text")

    def test_only_plain_http_links(self):
        self.assertEqual("https://example.com/a?b=1", h.clean_link("https://example.com/a?b=1"))
        for bad in ["javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "https://user:pw@evil.com/",
                    "http://a b.com", "content://media/1"]:
            with self.assertRaises(h.HubError, msg=bad):
                h.clean_link(bad)
        self.assertIsNone(h.clean_link(""))

    def test_options_are_limited(self):
        options = h.clean_options(["a", "a", "b", 3, "", "c", "d", "e", "f", "g"])
        self.assertEqual(["a", "b", "c", "d", "e", "f"], options)  # 6 at most; duplicates and non-strings dropped
        with self.assertRaises(h.HubError):
            h.clean_options("yes,no")


class McpTest(unittest.TestCase):
    def setUp(self):
        self.hub = h.Hub()

    def test_initialize_and_list(self):
        init = h.handle_rpc(self.hub, "Claude Code", rpc("initialize", {"protocolVersion": "2025-06-18"}))
        self.assertEqual("lumi-hub", init["result"]["serverInfo"]["name"])
        self.assertIn("tools", init["result"]["capabilities"])
        self.assertIsNone(h.handle_rpc(self.hub, "x", {"jsonrpc": "2.0", "method": "notifications/initialized"}))
        names = [t["name"] for t in h.handle_rpc(self.hub, "x", rpc("tools/list"))["result"]["tools"]]
        self.assertEqual(["lumi_send", "lumi_ask", "lumi_create_task", "lumi_notify"], names)
        self.assertEqual(-32601, h.handle_rpc(self.hub, "x", rpc("nope"))["error"]["code"])
        self.assertEqual(-32600, h.handle_rpc(self.hub, "x", {"method": "ping", "id": 1})["error"]["code"])

    def test_send_publishes_with_its_source(self):
        result = h.handle_rpc(self.hub, "Claude Code · lumi", rpc("tools/call", {"name": "lumi_send", "arguments": {"text": "Build is green", "link": "https://ci.example.com/1"}}))
        self.assertFalse(result["result"]["isError"])
        event = self.hub.since(0)[0]
        self.assertEqual(("message", "Claude Code · lumi", "Build is green", "https://ci.example.com/1"),
                         (event["type"], event["source"], event["text"], event["link"]))

    def test_bad_input_is_a_tool_error_not_a_crash(self):
        result = h.handle_rpc(self.hub, "x", rpc("tools/call", {"name": "lumi_send", "arguments": {"text": "x", "link": "javascript:1"}}))
        self.assertTrue(result["result"]["isError"])
        self.assertEqual([], self.hub.since(0))

    def test_task_is_only_a_proposal(self):
        result = h.handle_rpc(self.hub, "x", rpc("tools/call", {"name": "lumi_create_task", "arguments": {"title": "Review PR", "due": "tomorrow at 5pm"}}))
        self.assertIn("confirm", result["result"]["content"][0]["text"])
        event = self.hub.since(0)[0]
        self.assertEqual(("task", "Review PR", "tomorrow at 5pm"), (event["type"], event["title"], event["due"]))

    def test_rate_limit(self):
        for _ in range(h.EVENTS_PER_MINUTE):
            h.call_tool(self.hub, "spammer", "lumi_notify", {"title": "t"})
        with self.assertRaises(h.HubError) as e:
            h.call_tool(self.hub, "spammer", "lumi_notify", {"title": "t"})
        self.assertEqual(429, e.exception.code)
        h.call_tool(self.hub, "other", "lumi_notify", {"title": "t"})  # per source

    def test_ask_blocks_until_the_phone_answers(self):
        out = {}
        worker = threading.Thread(target=lambda: out.update(r=h.call_tool(self.hub, "Claude", "lumi_ask", {"question": "Deploy?", "options": ["Yes", "No"]})))
        worker.start()
        for _ in range(100):
            asks = [e for e in self.hub.since(0) if e["type"] == "ask"]
            if asks:
                break
            time.sleep(0.01)
        self.assertEqual(["Yes", "No"], asks[0]["options"])
        # A reconnecting phone (last id past the question) still gets the open question
        self.assertTrue(any(e["type"] == "ask" for e in self.hub.since(10**15)))
        self.assertFalse(self.hub.answer("unknown", "Yes"))
        self.assertTrue(self.hub.answer(asks[0]["ask_id"], "Yes"))
        worker.join(2)
        self.assertEqual("The user answered: Yes", out["r"]["content"][0]["text"])
        self.assertFalse(self.hub.answer(asks[0]["ask_id"], "No"))  # only once
        self.assertEqual("ask_closed", self.hub.since(0)[-1]["type"])

    def test_ask_times_out(self):
        clock = [1000.0]
        hub = h.Hub(clock=lambda: clock[0])
        threading.Timer(0.05, lambda: clock.__setitem__(0, 5000.0)).start()
        self.assertIsNone(hub.ask("x", "q", [], 10))


class StreamJsonTest(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(("session", "abc-123"), h.parse_stream_line('{"type":"system","subtype":"init","session_id":"abc-123"}'))
        self.assertEqual(("text", "Hello"), h.parse_stream_line(json.dumps(
            {"type": "assistant", "message": {"content": [{"type": "tool_use", "name": "Read"}, {"type": "text", "text": "Hello"}]}})))
        self.assertIsNone(h.parse_stream_line(json.dumps({"type": "assistant", "message": {"content": [{"type": "tool_use"}]}})))
        self.assertEqual(("result", ("Done", "s1", False)), h.parse_stream_line('{"type":"result","result":"Done","session_id":"s1","is_error":false}'))
        self.assertIsNone(h.parse_stream_line("not json"))
        self.assertIsNone(h.parse_stream_line('{"type":"user"}'))


class SearchParseTest(unittest.TestCase):
    def test_hits_come_from_the_json_inside_the_result(self):
        result = ('Here you go:\n[{"title":"Paris","url":"https://en.wikipedia.org/wiki/Paris","snippet":"Capital of France."},'
                  '{"title":"bad","url":"javascript:alert(1)"},{"title":"x","url":"https://a.com/‮exe"}]')
        hits = h.parse_search_result(json.dumps({"type": "result", "result": result}))
        self.assertEqual(["https://en.wikipedia.org/wiki/Paris"], [x["url"] for x in hits][:1])
        self.assertTrue(all(x["url"].startswith("https://") for x in hits))
        self.assertEqual([], h.parse_search_result("not json"))
        self.assertEqual([], h.parse_search_result(json.dumps({"result": "no results"})))


class FakeAgents(h.Agents):
    """Runs a tiny Python script instead of claude, printing stream-json (exercises the real turn runner)."""

    def __init__(self, hub, config, script):
        super().__init__(hub, config, lambda: None, tempfile.gettempdir(), "fake")
        self.script = script
        self.seen = []

    def command_for(self, agent, session):
        self.seen.append(session)
        import sys
        return [sys.executable, "-c", self.script]


FAKE_CLAUDE = r"""
import json, sys
prompt = sys.stdin.read()
print(json.dumps({"type": "system", "subtype": "init", "session_id": "sess-12345678"}))
print(json.dumps({"type": "assistant", "message": {"content": [{"type": "text", "text": "You said: " + prompt}]}}))
print(json.dumps({"type": "result", "result": "You said: " + prompt, "session_id": "sess-12345678", "is_error": False}))
"""


class TurnTest(unittest.TestCase):
    def test_turn_streams_and_resumes_the_session(self):
        hub, config = h.Hub(), {"threads": {}}
        agents = FakeAgents(hub, config, FAKE_CLAUDE)
        agents.start_turn("orbit-1", "claude", "--dangerous hello")
        done = self._wait_done(hub, 0)
        self.assertEqual("You said: --dangerous hello", done["text"])  # stdin, never argv
        self.assertIsNone(done["error"])
        self.assertEqual("sess-12345678", config["threads"]["orbit-1"]["session"])
        agents.start_turn("orbit-1", "claude", "again")
        self._wait_done(hub, done["id"])
        self.assertEqual([None, "sess-12345678"], agents.seen)
        with self.assertRaises(h.HubError):
            agents.start_turn("bad thread id!", "claude", "x")

    def test_failed_and_timed_out_turns_report_a_clear_error(self):
        hub = h.Hub()
        agents = FakeAgents(hub, {"threads": {}}, "import sys; sys.stdin.read(); sys.exit(3)")
        agents.start_turn("orbit-2", "claude", "hi")
        self.assertIn("exit code 3", self._wait_done(hub, 0)["error"])
        old, h.TURN_TIMEOUT = h.TURN_TIMEOUT, 0.3
        try:
            agents.script = "import time; time.sleep(30)"
            last = hub.since(0)[-1]["id"]
            agents.start_turn("orbit-3", "claude", "hi")
            self.assertIn("took longer", self._wait_done(hub, last)["error"])
        finally:
            h.TURN_TIMEOUT = old

    def test_status_reports_running_turns(self):
        st = h.Agents(None, {}, lambda: None, ".", "claude").status()
        self.assertTrue(st["claude"])
        self.assertEqual(0, st["running_turns"])

    def _wait_done(self, hub, after):
        for _ in range(3000):
            done = [e for e in hub.since(after) if e["type"] == "reply" and e["done"]]
            if done:
                return done[0]
            time.sleep(0.01)
        self.fail("turn did not finish")


class HttpTest(unittest.TestCase):
    """The real HTTP handler on a loopback port."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.config = h.Config(Path(cls.tmp.name) / "hub.json")
        cls.client_token = cls.config.add_client("Claude Code · test")
        cls.hub = h.Hub()
        h.Handler.hub, h.Handler.config, h.Handler.owner = cls.hub, cls.config, "me@example.com"
        h.Handler.agents = h.Agents(cls.hub, cls.config.data, cls.config.save, cls.tmp.name, None)
        h.Handler.log_message = lambda *a: None
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), h.Handler)
        cls.server.daemon_threads = True
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.tmp.cleanup()

    def call(self, path, body=None, headers=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, headers=headers or {}, method="POST" if data else "GET")
        try:
            with urllib.request.urlopen(request, timeout=5) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            return e.code, None

    def phone_headers(self, login="me@example.com", token=None):
        return {"Tailscale-User-Login": login, "Authorization": "Bearer " + (token or self.config.data["phone_token"])}

    def test_phone_needs_owner_and_token(self):
        self.assertEqual(200, self.call("/health", headers=self.phone_headers())[0])
        self.assertEqual(401, self.call("/health", headers=self.phone_headers(login="someone@else.com"))[0])
        self.assertEqual(401, self.call("/health", headers=self.phone_headers(token="wrong"))[0])
        # A local process without Tailscale identity can't act as the phone, even with the token
        self.assertEqual(401, self.call("/health", headers={"Authorization": "Bearer " + self.config.data["phone_token"]})[0])

    def test_mcp_client_token(self):
        local = {"Authorization": "Bearer " + self.client_token, "Content-Type": "application/json"}
        status, body = self.call("/mcp", rpc("tools/call", {"name": "lumi_notify", "arguments": {"title": "Hi"}}), local)
        self.assertEqual(200, status)
        self.assertEqual("Claude Code · test", self.hub.since(0)[-1]["source"])
        self.assertEqual(401, self.call("/mcp", rpc("ping"), {"Authorization": "Bearer nope"})[0])
        # From the tailnet only as the owner
        self.assertEqual(401, self.call("/mcp", rpc("ping"), dict(local, **{"Tailscale-User-Login": "guest@x.com"}))[0])
        self.assertEqual(202, self.call("/mcp", {"jsonrpc": "2.0", "method": "notifications/initialized"}, local)[0])
        # The phone token is not an MCP client token
        self.assertEqual(401, self.call("/mcp", rpc("ping"), {"Authorization": "Bearer " + self.config.data["phone_token"]})[0])

    def test_events_stream_and_answer(self):
        h.call_tool(self.hub, "Claude", "lumi_send", {"text": "über ✓"})
        last = self.hub.since(0)[-1]["id"]
        request = urllib.request.Request(self.base + f"/events?since={last - 1}", headers=self.phone_headers())
        with urllib.request.urlopen(request, timeout=5) as stream:
            lines = []
            while not any(l.startswith("data:") for l in lines):
                lines.append(stream.readline().decode("utf-8").strip())
        data = json.loads(next(l for l in lines if l.startswith("data:"))[5:])
        self.assertEqual("über ✓", data["text"])
        self.assertIn(f"id: {last}", lines)
        self.assertEqual(410, self.call("/answer", {"ask_id": "nope", "answer": "Yes"}, self.phone_headers())[0])
        self.assertEqual(404, self.call("/chat", {"thread": "t1", "agent": "claude", "text": "hi"}, self.phone_headers())[0])


if __name__ == "__main__":
    unittest.main()


class LumiAgentTest(unittest.TestCase):
    def test_agent_file_loads_and_builds_flags(self):
        flags = h.lumi_agent_args()
        self.assertEqual(flags[0], "--agents")
        self.assertEqual(flags[2:], ["--agent", "lumi"])
        definition = json.loads(flags[1])["lumi"]
        self.assertIn("prompt", definition)
        self.assertIn("lumi_ask", definition["prompt"])

    def test_missing_agent_file_adds_no_flags(self):
        self.assertEqual(h.lumi_agent_args(Path("does-not-exist.md")), [])

    def test_every_session_starts_with_the_agent(self):
        agents = h.Agents(None, {}, lambda: None, ".", "claude")
        args = agents.command_for("claude", "")
        self.assertIn("--agent", args)
        resumed = agents.command_for("claude", "11111111-1111-1111-1111-111111111111")
        self.assertIn("--agent", resumed)
