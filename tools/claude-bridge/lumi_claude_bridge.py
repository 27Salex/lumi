"""
Lumi -> Claude Code bridge (runs on your PC).

Lumi sends a task ("Claude, fix the failing build") and this bridge opens a new terminal window running an
interactive Claude Code session with Remote Control on, so you can pick it up from the Claude app on your phone.

Security (all three must pass):
  1. The bridge listens on 127.0.0.1 only. It is reachable from outside solely through `tailscale serve`
     (HTTPS with a real *.ts.net certificate, tailnet only, never the internet).
  2. `tailscale serve` adds the caller's identity; only requests from your own Tailscale login are accepted
     (shared devices of other people in the tailnet are rejected).
  3. A random token (Authorization: Bearer ...) that you copy into Lumi -> Settings -> Claude on your PC.

Standard library only. Usage:
    python lumi_claude_bridge.py [--dir C:\\path\\to\\project] [--port 8765] [--https-port 8443]
Setup and details: docs/CLAUDE_BRIDGE_SETUP.md
"""

import argparse
import hmac
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

CONFIG_PATH = Path.home() / ".lumi-claude-bridge.json"
MAX_PROMPT = 4000
# At most this many sessions per minute (a stuck retry loop in the app can't flood the PC with terminals)
MAX_PER_MINUTE = 3


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")


def load_config():
    if CONFIG_PATH.exists():
        return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    config = {"token": secrets.token_urlsafe(32)}
    CONFIG_PATH.write_text(json.dumps(config, indent=2), encoding="utf-8")
    return config


def tailscale_self():
    """(login name, MagicDNS name) of this machine, e.g. ("you@gmail.com", "laptop.tailnet.ts.net")."""
    result = run(["tailscale", "status", "--json"])
    if result.returncode != 0:
        sys.exit("Tailscale isn't running on this PC (tailscale status failed).")
    status = json.loads(result.stdout)
    me = status["Self"]
    login = status.get("User", {}).get(str(me["UserID"]), {}).get("LoginName", "")
    return login, me.get("DNSName", "").rstrip(".")


class Bridge(BaseHTTPRequestHandler):
    token = ""
    owner = ""
    workdir = ""
    claude = ""
    recent = []

    def log_message(self, fmt, *args):
        print(time.strftime("%H:%M:%S"), fmt % args, flush=True)

    def reply(self, code, body):
        data = json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authorized(self):
        # Set by `tailscale serve` for tailnet callers; absent for local processes talking to 127.0.0.1 directly
        caller = self.headers.get("Tailscale-User-Login", "")
        if not caller or caller.lower() != self.owner.lower():
            self.reply(403, {"error": "not_owner"})
            return False
        given = self.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        if not hmac.compare_digest(given.encode(), self.token.encode()):
            self.reply(401, {"error": "bad_token"})
            return False
        return True

    def do_GET(self):
        if self.path != "/health":
            return self.reply(404, {"error": "not_found"})
        if self.authorized():
            self.reply(200, {"ok": True, "host": socket.gethostname()})

    def do_POST(self):
        if self.path != "/session":
            return self.reply(404, {"error": "not_found"})
        if not self.authorized():
            return
        try:
            length = min(int(self.headers.get("Content-Length", "0")), 64_000)
            body = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            return self.reply(400, {"error": "bad_json"})
        # A leading "-" would be read as a claude option
        prompt = str(body.get("prompt", "")).strip().lstrip("-").strip()[:MAX_PROMPT]
        name = (str(body.get("name", "")).strip().lstrip("-").strip() or "Lumi")[:60]

        now = time.time()
        Bridge.recent = [t for t in Bridge.recent if now - t < 60]
        if len(Bridge.recent) >= MAX_PER_MINUTE:
            return self.reply(429, {"error": "too_many"})
        Bridge.recent.append(now)

        # Argument list, no shell: the prompt can't inject commands. claude.exe is a native binary (not a .cmd),
        # so Windows doesn't route it through cmd.exe either.
        args = [self.claude]
        if prompt:
            args.append(prompt)
        args += ["--remote-control", name]
        flags = subprocess.CREATE_NEW_CONSOLE if os.name == "nt" else 0
        try:
            subprocess.Popen(args, cwd=self.workdir, creationflags=flags)
        except OSError as e:
            return self.reply(500, {"error": "launch_failed", "detail": str(e)})
        self.log_message("session started: %s", name)
        self.reply(200, {"ok": True, "name": name, "host": socket.gethostname(), "dir": self.workdir})


def main():
    parser = argparse.ArgumentParser(description="Lets Lumi start Claude Code Remote Control sessions on this PC.")
    parser.add_argument("--dir", default=str(Path.home()), help="Folder the sessions start in (default: your home)")
    parser.add_argument("--port", type=int, default=8765, help="Local port (127.0.0.1 only)")
    parser.add_argument("--https-port", type=int, default=8443, help="Tailnet HTTPS port for tailscale serve (443, 8443 or 10000)")
    parser.add_argument("--new-token", action="store_true", help="Generate a new token (the old one stops working)")
    args = parser.parse_args()

    claude = shutil.which("claude")
    if not claude:
        sys.exit("Claude Code isn't installed or not on PATH.")
    if not Path(args.dir).is_dir():
        sys.exit(f"Folder not found: {args.dir}")
    if args.new_token and CONFIG_PATH.exists():
        CONFIG_PATH.unlink()
    config = load_config()
    owner, dns = tailscale_self()

    Bridge.token, Bridge.owner, Bridge.workdir, Bridge.claude = config["token"], owner, args.dir, claude
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Bridge)

    serve = run(["tailscale", "serve", "--bg", f"--https={args.https_port}", f"http://127.0.0.1:{args.port}"])
    if serve.returncode != 0:
        sys.exit("tailscale serve failed:\n" + serve.stderr + serve.stdout)

    print("Lumi -> Claude bridge ready")
    print(f"  Address (Lumi -> Settings): https://{dns}:{args.https_port}")
    print(f"  Token:                      {config['token']}")
    print(f"  Only accepts:               {owner}")
    print(f"  Sessions start in:          {args.dir}")
    print("Ctrl+C to stop.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        run(["tailscale", "serve", f"--https={args.https_port}", "off"])
        print("Bridge stopped.")


if __name__ == "__main__":
    main()
