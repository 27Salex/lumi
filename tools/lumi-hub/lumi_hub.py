"""
Lumi Hub: connects Lumi (your phone) with the AI agents on your PC (Claude Code, Claude Desktop, Codex...).

  agent -> you   MCP server with tools any MCP client can call when it has something to say:
                 lumi_send, lumi_ask (blocks until you answer on the phone), lumi_create_task, lumi_notify.
                 Transports: streamable HTTP (POST /mcp, JSON responses) and stdio (`lumi_hub.py mcp`, a thin
                 proxy to the running hub for desktop apps that only speak stdio).
  you -> agent   Wake per message: Lumi posts a message to /chat and the hub runs ONE turn of Claude Code on the
                 same session (`claude -p --output-format stream-json --resume <id>`), streaming the reply back.
                 Nothing keeps an agent "listening" in a loop.
  relay          Server-Sent Events (GET /events) while Lumi is open; optional ntfy ping when it is not.

Security (see README.md):
  * Listens on 127.0.0.1 only. The phone reaches it through `tailscale serve` (HTTPS, tailnet only). Phone calls
    must carry the Tailscale-User-Login of this PC's owner (set by tailscale serve) AND the phone token.
  * MCP clients use their own token (one per client, so messages show their source). Local clients (no Tailscale
    header) are accepted; tailnet clients must be the owner.
  * Everything an agent sends is untrusted: sizes are capped, links must be http(s), task proposals are only
    proposals (Lumi asks you before adding them), and the hub never runs a shell or anything an agent sends.

Standard library only (Python 3.10+).
"""

import argparse
import hmac
import json
import os
import queue
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

CONFIG_PATH = Path.home() / ".lumi-hub.json"
PROTOCOL_VERSION = "2025-06-18"
SERVER_INFO = {"name": "lumi-hub", "version": "1.0.0"}

MAX_BODY = 64_000
MAX_TEXT = 4_000
MAX_QUESTION = 1_000
MAX_OPTION = 80
MAX_OPTIONS = 6
MAX_LINK = 2_000
MAX_TITLE = 200
MAX_NOTES = 1_000
MAX_NOTIFY_TITLE = 100
MAX_NOTIFY_TEXT = 500
MAX_SOURCE = 60
EVENTS_KEPT = 500
ASK_DEFAULT_TIMEOUT = 600
ASK_MAX_TIMEOUT = 3_600
EVENTS_PER_MINUTE = 30
TURN_TIMEOUT = 15 * 60
SEARCH_TIMEOUT = 140
MAX_TURNS_RUNNING = 2
SESSION_ID = re.compile(r"^[A-Za-z0-9_-]{8,80}$")
THREAD_ID = re.compile(r"^[A-Za-z0-9_.:-]{1,80}$")


class HubError(Exception):
    """A request the hub refuses (bad input, limits). [code] is the HTTP status."""

    def __init__(self, message, code=400):
        super().__init__(message)
        self.code = code


# ── Input hygiene (agent input is untrusted) ─────────────────────────────────────────────────────────────────


_CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f‪-‮⁦-⁩]")


def clean_text(value, limit, field, required=True):
    """Plain text: control characters and bidi overrides removed, capped at [limit] characters."""
    if value is None or (isinstance(value, str) and not value.strip()):
        if required:
            raise HubError(f"'{field}' is required")
        return None
    if not isinstance(value, str):
        raise HubError(f"'{field}' must be a string")
    text = _CONTROL.sub("", value).strip()
    if len(text) > limit:
        text = text[: limit - 1].rstrip() + "…"
    return text


def clean_link(value):
    """Only absolute http(s) links without credentials; anything else is refused."""
    if value is None or value == "":
        return None
    if not isinstance(value, str) or len(value) > MAX_LINK:
        raise HubError("'link' must be an http(s) URL up to 2000 characters")
    link = value.strip()
    if not re.match(r"^https?://[^\s/@]+(/[^\s]*)?$", link, re.IGNORECASE):
        raise HubError("'link' must be an http(s) URL without spaces or credentials")
    return link


def clean_options(value):
    if value is None:
        return []
    if not isinstance(value, list):
        raise HubError("'options' must be a list of strings")
    options = []
    for item in value[:MAX_OPTIONS * 4]:
        option = clean_text(item, MAX_OPTION, "options", required=False) if isinstance(item, str) else None
        if option and option not in options:
            options.append(option)
        if len(options) == MAX_OPTIONS:
            break
    return options


# ── Hub state: events for the phone, pending questions, rate limits ───────────────────────────────────────────


class Hub:
    def __init__(self, clock=time.time):
        self.clock = clock
        self.lock = threading.Condition()
        self.events = []
        # Ids keep growing across restarts (milliseconds at start), so a phone resuming with its last id never
        # skips the events of a restarted hub
        self.next_id = int(clock() * 1000)
        self.asks = {}  # ask_id -> {"event": dict, "answer": str|None, "done": bool}
        self.sent = {}  # source -> [timestamps]
        self.listeners = 0
        self.on_publish = None  # optional callback(event) (ntfy)

    def publish(self, event):
        with self.lock:
            event = dict(event)
            event["id"] = self.next_id
            event["at"] = int(self.clock() * 1000)
            self.next_id += 1
            self.events.append(event)
            del self.events[:-EVENTS_KEPT]
            self.lock.notify_all()
        if self.on_publish:
            try:
                self.on_publish(event)
            except Exception:  # a push failure must never break the agent's call
                pass
        return event

    def since(self, last_id):
        """Events after [last_id], plus every question still waiting (a reconnecting phone must see them)."""
        with self.lock:
            out = [e for e in self.events if e["id"] > last_id]
            seen = {e["id"] for e in out}
            for ask in self.asks.values():
                if not ask["done"] and ask["event"]["id"] not in seen:
                    out.append(ask["event"])
            return sorted(out, key=lambda e: e["id"])

    def wait_events(self, last_id, timeout):
        """New events after [last_id], waiting up to [timeout] seconds for one."""
        with self.lock:
            if not any(e["id"] > last_id for e in self.events):
                self.lock.wait(timeout)
            return [e for e in self.events if e["id"] > last_id]

    def listening(self, delta):
        with self.lock:
            self.listeners += delta

    def check_rate(self, source):
        now = self.clock()
        with self.lock:
            recent = [t for t in self.sent.get(source, []) if now - t < 60]
            if len(recent) >= EVENTS_PER_MINUTE:
                raise HubError("Too many messages to Lumi in the last minute; try again later", 429)
            recent.append(now)
            self.sent[source] = recent

    # Questions -----------------------------------------------------------------------------------------------

    def ask(self, source, question, options, timeout):
        ask_id = secrets.token_hex(8)
        with self.lock:
            # Registered under the same lock as the publish, so an instant answer can't arrive before it
            self.asks[ask_id] = {"event": None, "answer": None, "done": False}
            self.asks[ask_id]["event"] = self.publish(
                {"type": "ask", "source": source, "ask_id": ask_id, "question": question, "options": options})
            deadline = self.clock() + timeout
            while not self.asks[ask_id]["done"]:
                left = deadline - self.clock()
                if left <= 0:
                    break
                self.lock.wait(min(left, 5))
            ask = self.asks.pop(ask_id)
        self.publish({"type": "ask_closed", "source": source, "ask_id": ask_id, "answered": ask["done"]})
        return ask["answer"] if ask["done"] else None

    def answer(self, ask_id, answer):
        with self.lock:
            ask = self.asks.get(ask_id)
            if ask is None or ask["done"]:
                return False
            ask["answer"] = answer
            ask["done"] = True
            self.lock.notify_all()
            return True


# ── MCP (JSON-RPC 2.0) ──────────────────────────────────────────────────────────────────────────────────────

TOOLS = [
    {
        "name": "lumi_send",
        "description": "Send a message to the user's phone (Lumi). Use it when you have something worth telling them: "
                       "a result, a finished job, a link. Plain text, optional http(s) link.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Message text (up to 4000 characters)."},
                "link": {"type": "string", "description": "Optional http(s) URL."},
            },
            "required": ["text"],
        },
    },
    {
        "name": "lumi_ask",
        "description": "Ask the user a question on their phone and wait for the answer (the call blocks until they "
                       "reply or the timeout passes; waiting costs nothing). Offer short options when possible.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "question": {"type": "string"},
                "options": {"type": "array", "items": {"type": "string"}, "description": "Up to 6 short choices."},
                "timeout_seconds": {"type": "integer", "description": "Default 600, max 3600."},
            },
            "required": ["question"],
        },
    },
    {
        "name": "lumi_create_task",
        "description": "Propose a task or reminder for the user's task list in Lumi. The user confirms it on the "
                       "phone before it is added.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "title": {"type": "string"},
                "due": {"type": "string", "description": "Optional, natural language: 'tomorrow at 5pm'."},
                "notes": {"type": "string"},
            },
            "required": ["title"],
        },
    },
    {
        "name": "lumi_notify",
        "description": "Show a short notification on the user's phone (no reply expected).",
        "inputSchema": {
            "type": "object",
            "properties": {"title": {"type": "string"}, "text": {"type": "string"}},
            "required": ["title"],
        },
    },
]


def tool_result(text, is_error=False):
    return {"content": [{"type": "text", "text": text}], "isError": is_error}


def call_tool(hub, source, name, args):
    if not isinstance(args, dict):
        raise HubError("arguments must be an object")
    if name == "lumi_send":
        text = clean_text(args.get("text"), MAX_TEXT, "text")
        link = clean_link(args.get("link"))
        hub.check_rate(source)
        hub.publish({"type": "message", "source": source, "text": text, "link": link})
        return tool_result("Delivered to Lumi.")
    if name == "lumi_ask":
        question = clean_text(args.get("question"), MAX_QUESTION, "question")
        options = clean_options(args.get("options"))
        try:
            timeout = int(args.get("timeout_seconds") or ASK_DEFAULT_TIMEOUT)
        except (TypeError, ValueError):
            raise HubError("'timeout_seconds' must be an integer")
        timeout = max(10, min(timeout, ASK_MAX_TIMEOUT))
        hub.check_rate(source)
        answer = hub.ask(source, question, options, timeout)
        if answer is None:
            return tool_result("The user did not answer in time.")
        return tool_result(f"The user answered: {answer}")
    if name == "lumi_create_task":
        title = clean_text(args.get("title"), MAX_TITLE, "title")
        due = clean_text(args.get("due"), 60, "due", required=False)
        notes = clean_text(args.get("notes"), MAX_NOTES, "notes", required=False)
        hub.check_rate(source)
        hub.publish({"type": "task", "source": source, "title": title, "due": due, "notes": notes})
        return tool_result("Proposed to the user. Lumi adds it only if they confirm it.")
    if name == "lumi_notify":
        title = clean_text(args.get("title"), MAX_NOTIFY_TITLE, "title")
        text = clean_text(args.get("text"), MAX_NOTIFY_TEXT, "text", required=False)
        hub.check_rate(source)
        hub.publish({"type": "notify", "source": source, "title": title, "text": text})
        return tool_result("Notification sent.")
    raise HubError(f"Unknown tool: {name}", 404)


def handle_rpc(hub, source, message):
    """One JSON-RPC message → the response dict, or None for notifications."""
    if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
        return {"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid Request"}}
    method = message.get("method")
    msg_id = message.get("id")
    if msg_id is None:  # notification (notifications/initialized, cancelled...)
        return None

    def ok(result):
        return {"jsonrpc": "2.0", "id": msg_id, "result": result}

    if method == "initialize":
        return ok({
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": SERVER_INFO,
            "instructions": "Use these tools to reach the user on their phone through Lumi. Only send messages "
                            "worth an interruption. lumi_ask blocks until they answer.",
        })
    if method == "ping":
        return ok({})
    if method == "tools/list":
        return ok({"tools": TOOLS})
    if method == "tools/call":
        params = message.get("params") or {}
        try:
            return ok(call_tool(hub, source, params.get("name"), params.get("arguments") or {}))
        except HubError as e:
            return ok(tool_result(str(e), is_error=True))
    return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": -32601, "message": f"Method not found: {method}"}}


# ── Wake per message: one Claude Code turn per phone message ──────────────────────────────────────────────────


def parse_stream_line(line):
    """One line of `claude -p --output-format stream-json` → (kind, value) or None.

    kinds: ("session", id) from the init message, ("text", str) for each assistant text block,
    ("result", (text, session_id, is_error)) at the end.
    """
    try:
        data = json.loads(line)
    except (ValueError, TypeError):
        return None
    if not isinstance(data, dict):
        return None
    kind = data.get("type")
    if kind == "system" and data.get("subtype") == "init" and data.get("session_id"):
        return ("session", data["session_id"])
    if kind == "assistant":
        content = (data.get("message") or {}).get("content") or []
        text = "".join(block.get("text", "") for block in content if isinstance(block, dict) and block.get("type") == "text")
        return ("text", text) if text else None
    if kind == "result":
        return ("result", (data.get("result") or "", data.get("session_id"), bool(data.get("is_error"))))
    return None


def parse_search_result(stdout):
    """`claude -p --output-format json` output → cleaned hits (the JSON array inside the result text)."""
    try:
        text = json.loads(stdout).get("result", "")
    except (ValueError, AttributeError):
        text = stdout or ""
    start, end = text.find("["), text.rfind("]")
    if start < 0 or end <= start:
        return []
    try:
        items = json.loads(text[start:end + 1])
    except ValueError:
        return []
    hits = []
    for item in items if isinstance(items, list) else []:
        if not isinstance(item, dict):
            continue
        try:
            link = clean_link(item.get("url"))
        except HubError:
            continue
        if not link:
            continue
        hits.append({
            "title": clean_text(item.get("title") or link, MAX_OPTION * 2, "title", required=False) or link,
            "url": link,
            "snippet": clean_text(item.get("snippet") or "", 600, "snippet", required=False) or "",
        })
    return hits[:4]


AGENT_FILE = Path(__file__).resolve().parent / "agents" / "lumi.md"


def load_agent_file(path):
    """Reads a Claude Code agent file (--- frontmatter ---, then the prompt). Returns (name, definition) or None."""
    try:
        text = Path(path).read_text(encoding="utf-8")
    except OSError:
        return None
    match = re.match(r"\A---\r?\n(.*?)\r?\n---\r?\n(.*)\Z", text, re.S)
    if not match:
        return None
    meta = {}
    for line in match.group(1).splitlines():
        key, sep, value = line.partition(":")
        if sep:
            meta[key.strip()] = value.strip()
    name, prompt = meta.get("name", ""), match.group(2).strip()
    if not name or not prompt:
        return None
    definition = {"description": meta.get("description", name), "prompt": prompt}
    return name, definition


def lumi_agent_args(path=AGENT_FILE):
    """Flags that start every Lumi session with the `lumi` agent (how to act, how to reply). Empty if the file is missing."""
    loaded = load_agent_file(path)
    if not loaded:
        return []
    name, definition = loaded
    return ["--agents", json.dumps({name: definition}), "--agent", name]



class Agents:
    """Runs agent turns. Only Claude Code for now; other CLIs plug in through [command_for]."""

    def __init__(self, hub, config, save_config, workdir, claude_path):
        self.hub = hub
        self.config = config
        self.save_config = save_config
        self.workdir = workdir
        self.claude = claude_path
        self.running = {}
        self.lock = threading.Lock()
        self.search_slot = threading.Semaphore(1)

    def available(self):
        return [{"id": "claude", "name": "Claude Code", "host": socket.gethostname()}] if self.claude else []

    def command_for(self, agent, session):
        if agent != "claude" or not self.claude:
            raise HubError(f"Agent not available on this PC: {agent}", 404)
        # The prompt goes through stdin, never through the argument list
        args = [self.claude, "-p", "--output-format", "stream-json", "--verbose"]
        args += lumi_agent_args()
        # Non-interactive turns can't ask for permission, so read-only web tools must be pre-approved
        args += ["--allowedTools", "WebSearch", "WebFetch"]
        if session:
            args += ["--resume", session]
        return args

    def start_turn(self, thread, agent, text):
        if not THREAD_ID.match(thread or ""):
            raise HubError("bad thread id")
        text = clean_text(text, MAX_TEXT, "text")
        threads = self.config.setdefault("threads", {})
        session = (threads.get(thread) or {}).get("session")
        if session and not SESSION_ID.match(session):
            session = None
        args = self.command_for(agent, session)
        with self.lock:
            if thread in self.running:
                raise HubError("This chat is still answering", 409)
            if len(self.running) >= MAX_TURNS_RUNNING:
                raise HubError("Too many agent turns running", 429)
            turn = secrets.token_hex(6)
            self.running[thread] = turn
        threading.Thread(target=self._run, args=(thread, agent, turn, args, text), daemon=True).start()
        return turn

    def _run(self, thread, agent, turn, args, text):
        reply, session, error = "", None, None
        try:
            flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
            proc = subprocess.Popen(
                args, cwd=self.workdir, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                text=True, encoding="utf-8", errors="replace", creationflags=flags,
            )
            timer = threading.Timer(TURN_TIMEOUT, proc.kill)
            timer.start()
            try:
                proc.stdin.write(text)
                proc.stdin.close()
                for line in proc.stdout:
                    parsed = parse_stream_line(line)
                    if not parsed:
                        continue
                    kind, value = parsed
                    if kind == "session":
                        session = value
                    elif kind == "text":
                        reply = (reply + "\n\n" + value).strip() if reply else value
                        self.hub.publish({"type": "reply", "thread": thread, "agent": agent, "turn": turn,
                                          "text": reply[-MAX_TEXT * 4:], "done": False})
                    elif kind == "result":
                        final, sid, is_error = value
                        session = sid or session
                        if final:
                            reply = final
                        if is_error:
                            error = final or "error"
                proc.wait()
            finally:
                timer.cancel()
                proc.stdout.close()
            if proc.returncode not in (0, None) and not reply:
                error = error or f"exit code {proc.returncode}"
        except OSError as e:
            error = f"could not start the agent: {e}"
        finally:
            if session and SESSION_ID.match(session):
                self.config.setdefault("threads", {})[thread] = {"agent": agent, "session": session}
                self.save_config()
            with self.lock:
                self.running.pop(thread, None)
            self.hub.publish({"type": "reply", "thread": thread, "agent": agent, "turn": turn,
                              "text": reply[-MAX_TEXT * 4:], "done": True, "error": error})

    def search(self, query):
        """Web search by Claude Code (WebSearch tool only), for Lumi's general questions. Returns [{title,url,snippet}]."""
        query = clean_text(query, 200, "query")
        if not self.claude:
            raise HubError("Claude Code is not available on this PC", 404)
        if not self.search_slot.acquire(blocking=False):
            raise HubError("A search is already running", 429)
        try:
            flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
            prompt = ("Search the web for the query below and reply ONLY with a JSON array of up to 4 objects "
                      '{"title": ..., "url": ..., "snippet": ...} (snippet = 1-3 factual sentences from the page that '
                      "answer the query). No prose.\n\nQUERY: " + query)
            proc = subprocess.run(
                [self.claude, "-p", "--output-format", "json", "--allowedTools", "WebSearch"],
                input=prompt, capture_output=True, text=True, encoding="utf-8", errors="replace",
                cwd=self.workdir, timeout=SEARCH_TIMEOUT, creationflags=flags,
            )
            return parse_search_result(proc.stdout)
        except subprocess.TimeoutExpired:
            raise HubError("The search took too long", 504)
        finally:
            self.search_slot.release()

    def forget(self, thread):
        """Starts the thread over (a new Claude session on the next message)."""
        if self.config.get("threads", {}).pop(thread, None) is not None:
            self.save_config()


# ── Config ─────────────────────────────────────────────────────────────────────────────────────────────────


class Config:
    def __init__(self, path=CONFIG_PATH):
        self.path = Path(path)
        self.lock = threading.Lock()
        if self.path.exists():
            self.data = json.loads(self.path.read_text(encoding="utf-8"))
        else:
            self.data = {}
        changed = False
        if not self.data.get("phone_token"):
            self.data["phone_token"] = secrets.token_urlsafe(32)
            changed = True
        self.data.setdefault("clients", {})
        self.data.setdefault("threads", {})
        if changed:
            self.save()

    def save(self):
        with self.lock:
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.data, indent=2), encoding="utf-8")
            os.replace(tmp, self.path)

    def add_client(self, name):
        name = clean_text(name, MAX_SOURCE, "name")
        token = "lh_" + secrets.token_urlsafe(32)
        self.data["clients"][token] = name
        self.save()
        return token

    def client_for(self, token):
        """Name of the MCP client with [token] (constant-time compare), or None."""
        found = None
        for known, name in self.data["clients"].items():
            if hmac.compare_digest(known.encode(), (token or "").encode()):
                found = name
        return found


# ── HTTP ───────────────────────────────────────────────────────────────────────────────────────────────────


def bearer(headers):
    return headers.get("Authorization", "").removeprefix("Bearer ").strip()


def phone_allowed(headers, owner, token):
    """The phone: through tailscale serve as the PC's owner (header set by Tailscale) AND with the phone token.
    [owner] None = development mode without Tailscale (loopback only; the token is still required)."""
    if owner is not None:
        caller = headers.get("Tailscale-User-Login", "")
        if not caller or caller.lower() != owner.lower():
            return False
    return hmac.compare_digest(bearer(headers).encode(), token.encode())


def client_allowed(headers, owner, config):
    """An MCP client: its own token; from the tailnet only as the owner (local processes have no header)."""
    caller = headers.get("Tailscale-User-Login")
    if caller is not None and owner is not None and caller.lower() != owner.lower():
        return None
    return config.client_for(bearer(headers))


class Handler(BaseHTTPRequestHandler):
    hub: Hub = None
    config: Config = None
    agents: Agents = None
    owner = None
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print(time.strftime("%H:%M:%S"), fmt % args, flush=True)

    def send_json(self, code, body):
        data = json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length > MAX_BODY:
            raise HubError("body too large", 413)
        try:
            return json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, UnicodeDecodeError):
            raise HubError("bad json")

    def phone(self):
        if phone_allowed(self.headers, self.owner, self.config.data["phone_token"]):
            return True
        self.send_json(401, {"error": "unauthorized"})
        return False

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if path == "/health":
            if self.phone():
                self.send_json(200, {"ok": True, "host": socket.gethostname(), "agents": self.agents.available()})
        elif path == "/events":
            if self.phone():
                self.stream_events(query)
        else:
            self.send_json(404, {"error": "not_found"})

    def do_POST(self):
        try:
            if self.path == "/mcp":
                return self.mcp()
            if self.path not in ("/answer", "/chat", "/forget", "/search"):
                return self.send_json(404, {"error": "not_found"})
            if not self.phone():
                return
            body = self.read_json()
            if self.path == "/answer":
                answer = clean_text(body.get("answer"), MAX_OPTION * 4, "answer")
                ok = self.hub.answer(str(body.get("ask_id", "")), answer)
                return self.send_json(200 if ok else 410, {"ok": ok})
            if self.path == "/search":
                return self.send_json(200, {"hits": self.agents.search(body.get("query"))})
            if self.path == "/chat":
                turn = self.agents.start_turn(str(body.get("thread", "")), str(body.get("agent", "claude")), body.get("text"))
                return self.send_json(202, {"ok": True, "turn": turn})
            self.agents.forget(str(body.get("thread", "")))
            return self.send_json(200, {"ok": True})
        except HubError as e:
            self.send_json(e.code, {"error": str(e)})

    def mcp(self):
        source = client_allowed(self.headers, self.owner, self.config)
        if not source:
            return self.send_json(401, {"error": "unauthorized"})
        body = self.read_json()
        if isinstance(body, list):
            responses = [r for r in (handle_rpc(self.hub, source, m) for m in body) if r is not None]
            if not responses:
                return self.send_empty(202)
            return self.send_json(200, responses)
        response = handle_rpc(self.hub, source, body)
        if response is None:
            return self.send_empty(202)
        self.send_json(200, response)

    def send_empty(self, code):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def stream_events(self, query):
        last = self.headers.get("Last-Event-ID") or ""
        match = re.search(r"(?:^|&)since=(\d+)", query)
        try:
            last_id = int(last) if last.isdigit() else int(match.group(1)) if match else 0
        except ValueError:
            last_id = 0
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        self.hub.listening(1)
        try:
            self.wfile.write(b": connected\n\n")
            self.wfile.flush()
            events = self.hub.since(last_id)  # the first batch includes questions still waiting for an answer
            while True:
                if not events:
                    self.wfile.write(b": keepalive\n\n")
                for event in events:
                    payload = json.dumps(event, ensure_ascii=False)
                    self.wfile.write(f"id: {event['id']}\nevent: {event['type']}\ndata: {payload}\n\n".encode("utf-8"))
                    last_id = max(last_id, event["id"])
                self.wfile.flush()
                events = self.hub.wait_events(last_id, 20)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass
        finally:
            self.hub.listening(-1)


# ── ntfy (optional): a content-free ping when Lumi isn't connected ───────────────────────────────────────────


def ntfy_pusher(hub, url):
    def push(event):
        if hub.listeners > 0 or event["type"] not in ("message", "ask", "task", "notify"):
            return
        # Content stays on the PC: the push server only learns that something arrived
        body = f"New {event['type']} from {event.get('source', 'an agent')}. Open Lumi.".encode("utf-8")
        request = urllib.request.Request(url, data=body, method="POST", headers={"Title": "Lumi Hub"})
        threading.Thread(target=lambda: urllib.request.urlopen(request, timeout=10).close(), daemon=True).start()
    return push


# ── Commands ───────────────────────────────────────────────────────────────────────────────────────────────


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")


def tailscale_self():
    result = run(["tailscale", "status", "--json"])
    if result.returncode != 0:
        sys.exit("Tailscale isn't running on this PC (tailscale status failed).")
    status = json.loads(result.stdout)
    me = status["Self"]
    login = status.get("User", {}).get(str(me["UserID"]), {}).get("LoginName", "")
    return login, me.get("DNSName", "").rstrip(".")


def cmd_serve(args):
    config = Config(args.config)
    if not Path(args.dir).is_dir():
        sys.exit(f"Folder not found: {args.dir}")
    hub = Hub()
    if args.ntfy:
        hub.on_publish = ntfy_pusher(hub, args.ntfy)
    agents = Agents(hub, config.data, config.save, args.dir, shutil.which("claude"))
    Handler.hub, Handler.config, Handler.agents = hub, config, agents
    if args.dev_no_tailscale:
        Handler.owner, address = None, f"http://10.0.2.2:{args.port} (emulator) / http://127.0.0.1:{args.port}"
        print("DEVELOPMENT MODE: no Tailscale identity check. Loopback only; never expose this port.")
    else:
        owner, dns = tailscale_self()
        Handler.owner, address = owner, f"https://{dns}:{args.https_port}"
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    if not args.dev_no_tailscale:
        serve = run(["tailscale", "serve", "--bg", f"--https={args.https_port}", f"http://127.0.0.1:{args.port}"])
        if serve.returncode != 0:
            sys.exit("tailscale serve failed:\n" + serve.stderr + serve.stdout)
    print("Lumi Hub ready")
    print(f"  Address (Lumi -> Settings -> Lumi Hub): {address}")
    print(f"  Phone token:                            {config.data['phone_token']}")
    print(f"  Only accepts:                           {Handler.owner or '(development mode)'}")
    print(f"  Agents:                                 {', '.join(a['name'] for a in agents.available()) or 'none (claude not on PATH)'}")
    print(f"  Agent turns run in:                     {args.dir}")
    print(f"  MCP clients:                            {len(config.data['clients'])} (add one: lumi_hub.py add-client NAME)")
    print("Ctrl+C to stop.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        if not args.dev_no_tailscale:
            run(["tailscale", "serve", f"--https={args.https_port}", "off"])
        print("Lumi Hub stopped.")


def cmd_add_client(args):
    config = Config(args.config)
    token = config.add_client(args.name)
    script = Path(__file__).resolve()
    print(f"Client '{args.name}' added. Its token (keep it secret):\n  {token}\n")
    print("Claude Code (HTTP):")
    print(f"  claude mcp add --transport http lumi http://127.0.0.1:{args.port}/mcp --header \"Authorization: Bearer {token}\"")
    print("Desktop apps that only speak stdio (e.g. claude_desktop_config.json):")
    print(json.dumps({"mcpServers": {"lumi": {"command": sys.executable, "args": [str(script), "mcp", "--token", token,
                                                                                    "--hub", f"http://127.0.0.1:{args.port}"]}}}, indent=2))


def cmd_mcp(args):
    """stdio transport: newline-delimited JSON-RPC on stdin/stdout, forwarded to the running hub."""
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError:
            continue
        request = urllib.request.Request(
            args.hub.rstrip("/") + "/mcp", data=json.dumps(message).encode("utf-8"), method="POST",
            headers={"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                     "Authorization": f"Bearer {args.token}"},
        )
        try:
            with urllib.request.urlopen(request, timeout=ASK_MAX_TIMEOUT + 60) as response:
                data = response.read()
            if data:
                sys.stdout.write(data.decode("utf-8") + "\n")
                sys.stdout.flush()
        except Exception as e:  # hub down or token refused: answer the request so the client doesn't hang
            if isinstance(message, dict) and message.get("id") is not None:
                error = {"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32000, "message": f"Lumi Hub: {e}"}}
                sys.stdout.write(json.dumps(error) + "\n")
                sys.stdout.flush()


def main(argv=None):
    parser = argparse.ArgumentParser(description="Lumi Hub: AI agents on this PC <-> Lumi on your phone.")
    sub = parser.add_subparsers(dest="command", required=True)
    serve = sub.add_parser("serve", help="Run the hub")
    serve.add_argument("--dir", default=str(Path.home()), help="Folder agent turns run in (default: your home)")
    serve.add_argument("--port", type=int, default=8766, help="Local port (127.0.0.1 only)")
    serve.add_argument("--https-port", type=int, default=8443, help="Tailnet HTTPS port for tailscale serve")
    serve.add_argument("--ntfy", help="Optional ntfy topic URL for content-free pings while Lumi is closed")
    serve.add_argument("--dev-no-tailscale", action="store_true", help="Development only: skip Tailscale (emulator tests)")
    serve.add_argument("--config", default=str(CONFIG_PATH), help="Config file (default ~/.lumi-hub.json)")
    add = sub.add_parser("add-client", help="Create a token for an MCP client")
    add.add_argument("name", help="Shown in Lumi as the source, e.g. 'Claude Code · lumi repo'")
    add.add_argument("--port", type=int, default=8766)
    add.add_argument("--config", default=str(CONFIG_PATH), help="Config file (default ~/.lumi-hub.json)")
    mcp = sub.add_parser("mcp", help="stdio MCP transport (proxy to the running hub)")
    mcp.add_argument("--token", required=True)
    mcp.add_argument("--hub", default="http://127.0.0.1:8766")
    args = parser.parse_args(argv)
    {"serve": cmd_serve, "add-client": cmd_add_client, "mcp": cmd_mcp}[args.command](args)


if __name__ == "__main__":
    main()
