"""Tests for the My PC (view only) feature: flag, unlock, expiry, lockout, validation, HTTP routes, fake backend."""

import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import lumi_hub as h
import pcview as p


class Clock:
    def __init__(self):
        self.now = 1_000_000.0

    def __call__(self):
        return self.now


class Flags:
    def __init__(self, enabled=True, epoch=0.0):
        self.enabled, self.epoch = enabled, epoch

    def get(self):
        return self.enabled, self.epoch


class PcViewTest(unittest.TestCase):
    def setUp(self):
        self.clock, self.flags, self.backend = Clock(), Flags(), p.FakeBackend()
        self.audit = p.AuditLog(clock=self.clock)
        self.pc = p.PcView(self.flags, self.backend, self.audit, clock=self.clock, sleep=lambda s: None)

    def code(self, fn, *a):
        with self.assertRaises(p.PcError) as ctx:
            fn(*a)
        return ctx.exception.code, str(ctx.exception), ctx.exception.extra

    def test_off_by_default_and_flag_is_live(self):
        self.flags.enabled = False
        self.assertEqual("disabled", self.pc.status()["state"])
        self.assertEqual(403, self.code(self.pc.unlock)[0])
        self.flags.enabled = True
        token = self.pc.unlock()["token"]
        self.flags.enabled = False  # disabling on the PC kills a live session at once
        self.assertEqual(403, self.code(self.pc.monitors, token)[0])
        self.assertEqual(0, self.backend.captures)

    def test_unlock_then_frame_needs_token(self):
        self.assertEqual("locked", self.pc.status()["state"])
        self.assertEqual(401, self.code(self.pc.frame, "", 0, 800, 50)[0])
        token = self.pc.unlock()["token"]
        self.assertEqual("unlocked", self.pc.status()["state"])
        data, info = self.pc.frame(token, 0, 800, 50)
        self.assertTrue(data.startswith(b"\xff\xd8"))
        self.assertEqual(800, info["width"])
        self.assertEqual(1, self.pc.status()["viewers"])

    def test_expiry_and_extend_with_hard_cap(self):
        token = self.pc.unlock()["token"]
        self.clock.now += 500
        self.assertGreater(self.pc.extend(token)["expires_in"], 590)
        self.clock.now += 599
        self.pc.frame(token, 0, 800, 50)  # still valid thanks to the extension
        self.clock.now += 11
        code, _, extra = self.code(self.pc.frame, token, 0, 800, 50)
        self.assertEqual((401, "expired"), (code, extra["reason"]))
        token = self.pc.unlock()["token"]
        for _ in range(8):  # extending forever hits the absolute cap
            self.clock.now += 590
            try:
                self.pc.extend(token)
            except p.PcError:
                break
        self.clock.now += 700
        self.assertEqual(401, self.code(self.pc.frame, token, 0, 800, 50)[0])

    def test_lock_revokes_and_stops_viewers(self):
        token = self.pc.unlock()["token"]
        self.pc.frame(token, 0, 800, 50)
        self.pc.lock_now()
        self.assertEqual("revoked", self.code(self.pc.frame, token, 0, 800, 50)[2]["reason"])
        self.assertEqual("locked", self.pc.status()["state"])
        self.assertTrue(any("viewer_stop" in line for line in self.audit.entries))

    def test_cli_lock_epoch_revokes(self):
        token = self.pc.unlock()["token"]
        self.flags.epoch = self.clock.now + 1
        self.assertEqual(401, self.code(self.pc.monitors, token)[0])

    def test_lockout_after_failures(self):
        good = self.pc.unlock()["token"]
        for _ in range(p.FAIL_LIMIT):
            self.code(self.pc.monitors, "guess")
        code, name, extra = self.code(self.pc.monitors, good)
        self.assertEqual((429, "locked_out"), (code, name))
        self.assertGreater(extra["retry_after"], 0)
        self.assertEqual("locked_out", self.pc.status()["state"])
        self.clock.now += p.LOCKOUT_SECONDS + 1
        self.assertEqual("locked", self.pc.status()["state"])  # lockout also revoked the old token
        self.pc.monitors(self.pc.unlock()["token"])

    def test_unlock_rate_limit(self):
        for _ in range(p.UNLOCKS_PER_MINUTE):
            self.pc.unlock()
        self.assertEqual(429, self.code(self.pc.unlock)[0])
        self.clock.now += 61
        self.pc.unlock()

    def test_input_validation_and_limits(self):
        token = self.pc.unlock()["token"]
        self.assertEqual(404, self.code(self.pc.frame, token, 5, 800, 50)[0])
        _, info = self.pc.frame(token, 0, "junk", "junk")
        self.assertEqual((p.DEFAULT_WIDTH, p.DEFAULT_QUALITY), (info["width"], info["quality"]))
        _, info = self.pc.frame(token, 0, 99999, 500)
        self.assertEqual((p.MAX_WIDTH, p.MAX_QUALITY), (info["width"], info["quality"]))
        _, info = self.pc.frame(token, 0, 1, 1)
        self.assertEqual((p.MIN_WIDTH, p.MIN_QUALITY), (info["width"], info["quality"]))
        self.backend.size = p.MAX_FRAME_BYTES + 10  # never fits: refused, not streamed
        self.assertEqual(413, self.code(self.pc.frame, token, 0, 800, 50)[0])

    def test_unchanged_frame_is_not_resent(self):
        token = self.pc.unlock()["token"]
        _, info = self.pc.frame(token, 0, 800, 50)
        data, _ = self.pc.frame(token, 0, 800, 50, info["etag"])
        self.assertIsNone(data)

    def test_no_capture_without_a_valid_token(self):
        self.code(self.pc.frame, "bad", 0, 800, 50)
        self.assertEqual(0, self.backend.captures)

    def test_audit_has_events_but_never_tokens(self):
        token = self.pc.unlock()["token"]
        self.pc.frame(token, 0, 800, 50)
        self.code(self.pc.monitors, "secret-guess")
        self.pc.lock_now()
        text = "\n".join(self.audit.entries)
        for word in ("unlock", "viewer_start", "auth_failed", "lock"):
            self.assertIn(word, text)
        self.assertNotIn(token, text)
        self.assertNotIn("secret-guess", text)


class FlagsFileTest(unittest.TestCase):
    def test_cli_flags_survive_hub_saves(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "hub.json"
            config = h.Config(path)
            self.assertEqual((False, 0.0), p.read_flags(path))
            h.main(["enable-pc-view", "--config", str(path)])
            config.data["threads"]["x"] = "y"
            config.save()  # the running hub saves its own data without losing the CLI's flag
            self.assertTrue(p.read_flags(path)[0])
            self.assertEqual("y", json.loads(path.read_text())["threads"]["x"])
            h.main(["pc-lock", "--config", str(path)])
            self.assertGreater(p.read_flags(path)[1], 0)
            h.main(["disable-pc-view", "--config", str(path)])
            self.assertFalse(p.FileFlags(path).get()[0])


class PcHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.tmp.name) / "hub.json"
        cls.config = h.Config(cls.path)
        cls.backend = p.FakeBackend()
        h.Handler.hub, h.Handler.config, h.Handler.owner = h.Hub(), cls.config, "me@example.com"
        h.Handler.agents = h.Agents(h.Handler.hub, cls.config.data, cls.config.save, cls.tmp.name, None)
        h.Handler.pc = p.PcView(p.FileFlags(cls.path), cls.backend, p.AuditLog(), sleep=lambda s: None)
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

    def setUp(self):
        p.write_flags(self.path, enabled=True)
        h.Handler.pc.locked_until = 0
        h.Handler.pc.failures.clear()
        h.Handler.pc.unlocks.clear()

    def headers(self, token=None, login="me@example.com", phone=None):
        out = {"Tailscale-User-Login": login, "Authorization": "Bearer " + (phone or self.config.data["phone_token"])}
        if token:
            out["X-Lumi-Unlock"] = token
        return out

    def call(self, method, path, headers):
        request = urllib.request.Request(self.base + path, data=b"{}" if method == "POST" else None, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=5) as r:
                return r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers

    def test_full_flow(self):
        self.assertEqual(200, self.call("GET", "/pc/status", self.headers())[0])
        status, body, _ = self.call("POST", "/pc/unlock", self.headers())
        token = json.loads(body)["token"]
        status, body, _ = self.call("GET", "/pc/monitors", self.headers(token))
        self.assertEqual(2, len(json.loads(body)["monitors"]))
        status, body, hdr = self.call("GET", "/pc/frame?monitor=1&w=640&q=40", self.headers(token))
        self.assertEqual((200, "image/jpeg", "640"), (status, hdr["Content-Type"], hdr["X-Lumi-Width"]))
        self.assertTrue(body.startswith(b"\xff\xd8"))
        again = self.call("GET", "/pc/frame?monitor=1&w=640&q=40", dict(self.headers(token), **{"If-None-Match": hdr["ETag"]}))
        self.assertEqual(304, again[0])
        self.assertEqual(200, self.call("POST", "/pc/lock", self.headers())[0])
        self.assertEqual(401, self.call("GET", "/pc/frame?monitor=0", self.headers(token))[0])

    def test_owner_and_phone_token_required(self):
        self.assertEqual(401, self.call("GET", "/pc/status", self.headers(login="x@y.com"))[0])
        self.assertEqual(401, self.call("POST", "/pc/unlock", self.headers(phone="wrong"))[0])
        self.assertEqual(401, self.call("GET", "/pc/status", {})[0])

    def test_disabled_by_default_flag(self):
        p.write_flags(self.path, enabled=False)
        self.assertEqual("disabled", json.loads(self.call("GET", "/pc/status", self.headers())[1])["state"])
        self.assertEqual(403, self.call("POST", "/pc/unlock", self.headers())[0])

    def test_only_bad_unlock_tokens_from_the_phone_lock_out(self):
        # strangers (wrong phone token / wrong owner) can never lock the real phone out
        for _ in range(p.FAIL_LIMIT * 2):
            self.call("POST", "/pc/unlock", self.headers(phone="wrong"))
            self.call("GET", "/pc/status", self.headers(login="x@y.com"))
        self.assertEqual(200, self.call("POST", "/pc/unlock", self.headers())[0])
        # the phone itself with bad unlock tokens does
        for _ in range(p.FAIL_LIMIT):
            self.call("GET", "/pc/monitors", self.headers("bogus"))
        status, body, _ = self.call("POST", "/pc/unlock", self.headers())
        self.assertEqual((429, "locked_out"), (status, json.loads(body)["error"]))
        self.assertEqual(200, self.call("POST", "/pc/lock", self.headers())[0])  # locking is always possible

    def test_bad_content_length_is_refused(self):
        h = dict(self.headers(), **{"Content-Length": "abc"})
        self.assertEqual(400, self.call("POST", "/pc/lock", h)[0])


if __name__ == "__main__":
    unittest.main()
