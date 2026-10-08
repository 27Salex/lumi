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

import pcview

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
DELTAS_KEPT = 200
DENIED_READS = ("Read(~/.lumi-hub.json)", "Read(~/.lumi-hub.json.*)", "Read(~/.ssh/**)", "Read(~/.aws/**)", "Read(~/.claude/.credentials.json)")
ASK_DEFAULT_TIMEOUT = 600
ASK_MAX_TIMEOUT = 3_600
EVENTS_PER_MINUTE = 30
TURN_TIMEOUT = 15 * 60
START_TIME = time.time()
SEARCH_TIMEOUT = 140
MAX_TURNS_RUNNING = 2
MAX_PERSISTENT = 3          # long-lived claude processes kept warm (one per chat)
IDLE_TIMEOUT = 10 * 60      # a warm process nobody talks to is stopped after this
MODELS = [{"id": "sonnet", "label": "Sonnet (fast)"}, {"id": "opus", "label": "Opus (deepest)"},
          {"id": "haiku", "label": "Haiku (fastest)"}]
EFFORTS = ["low", "medium", "high", "max"]
DEFAULT_MODEL, DEFAULT_EFFORT = "sonnet", "medium"
MODEL_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._\[\]-]{0,63}$")
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
        self.deltas = []  # turn_delta events: live only, never replayed and never evicting the buffered events
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
            if event.get("type") == "turn_delta":
                self.deltas.append(event)
                del self.deltas[:-DELTAS_KEPT]
            else:
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
            if not any(e["id"] > last_id for e in self.events) and not any(e["id"] > last_id for e in self.deltas):
                self.lock.wait(timeout)
            return sorted([e for e in self.events + self.deltas if e["id"] > last_id], key=lambda e: e["id"])

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
    if kind == "stream_event" and not data.get("parent_tool_use_id"):
        event = data.get("event") or {}
        delta = event.get("delta") or {}
        if event.get("type") == "content_block_delta" and delta.get("type") == "text_delta" and delta.get("text"):
            return ("delta", delta["text"])
        return None
    if kind == "assistant":
        content = (data.get("message") or {}).get("content") or []
        text = "".join(block.get("text", "") for block in content if isinstance(block, dict) and block.get("type") == "text")
        return ("text", text) if text else None
    if kind == "result":
        return ("result", (data.get("result") or "", data.get("session_id"), bool(data.get("is_error"))))
    return None


def clean_model(value):
    value = str(value or "").strip()
    if not MODEL_ID.match(value):
        raise HubError("bad model")
    return value


def clean_effort(value):
    value = str(value or "").strip().lower()
    if value not in EFFORTS:
        raise HubError("bad effort")
    return value


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



class PersistentFailed(Exception):
    pass


class Worker:
    """One long-lived `claude -p --input-format stream-json` process, owned by one chat thread."""

    def __init__(self, proc, key):
        self.proc, self.key = proc, key
        self.last_used = time.time()
        self.busy = False

    def alive(self):
        return self.proc.poll() is None

    def kill(self):
        try:
            self.proc.kill()
        except OSError:
            pass
        try:
            self.proc.stdin.close()
        except (OSError, ValueError):
            pass


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
        self.persistent = True
        self.workers = {}
        self.plock = threading.RLock()
        self.reaper = None
        self.search_slot = threading.Semaphore(1)

    def status(self):
        """Health details for the phone and for `lumi status`: what is running right now."""
        with self.lock:
            running = len(self.running)
        return {"claude": bool(self.claude), "running_turns": running, "max_turns": MAX_TURNS_RUNNING,
                "turn_timeout_seconds": TURN_TIMEOUT}

    def available(self):
        return [{"id": "claude", "name": "Claude Code", "host": socket.gethostname()}] if self.claude else []

    def command_for(self, agent, session, model=None, effort=None, persistent=False):
        if agent != "claude" or not self.claude:
            raise HubError(f"Agent not available on this PC: {agent}", 404)
        # The prompt goes through stdin, never through the argument list
        args = [self.claude, "-p"]
        if persistent:
            args += ["--input-format", "stream-json"]
        args += ["--output-format", "stream-json", "--verbose", "--include-partial-messages"]
        if model:
            args += ["--model", model]
        if effort:
            args += ["--effort", effort]
        args += lumi_agent_args()
        # Non-interactive turns can't ask for permission, so read-only web tools must be pre-approved
        args += ["--allowedTools", "WebSearch", "WebFetch"]
        # Defence in depth against prompt injection (the agent file also forbids it): never read the hub config (phone
        # token) or ssh keys. Read rules also cover Grep/Glob. MCP and PC-control tools are untouched.
        args += ["--disallowedTools", *DENIED_READS]
        if session:
            args += ["--resume", session]
        return args

    # ── Options (model + reasoning level) ──

    def defaults(self, agent="claude"):
        saved = (self.config.get("agent_defaults") or {}).get(agent) or {}
        model, effort = saved.get("model"), saved.get("effort")
        return {"model": model if model and MODEL_ID.match(model) else DEFAULT_MODEL,
                "effort": effort if effort in EFFORTS else DEFAULT_EFFORT}

    def options(self):
        """GET /agents/options: what the phone can choose per turn, plus the saved defaults."""
        return {"models": [dict(m) for m in MODELS], "efforts": list(EFFORTS), "defaults": self.defaults()}

    def set_defaults(self, model=None, effort=None, agent="claude"):
        current = self.defaults(agent)
        if model is not None:
            current["model"] = clean_model(model)
        if effort is not None:
            current["effort"] = clean_effort(effort)
        self.config.setdefault("agent_defaults", {})[agent] = current
        self.save_config()
        return current

    # ── Turns ──

    def start_turn(self, thread, agent, text, model=None, effort=None):
        if not THREAD_ID.match(thread or ""):
            raise HubError("bad thread id")
        text = clean_text(text, MAX_TEXT, "text")
        defaults = self.defaults(agent)
        model = clean_model(model) if model else defaults["model"]
        effort = clean_effort(effort) if effort else defaults["effort"]
        if agent != "claude" or not self.claude:
            raise HubError(f"Agent not available on this PC: {agent}", 404)
        with self.lock:
            if thread in self.running:
                raise HubError("This chat is still answering", 409)
            if len(self.running) >= MAX_TURNS_RUNNING:
                raise HubError("Too many agent turns running", 429)
            turn = secrets.token_hex(6)
            self.running[thread] = turn
        threading.Thread(target=self._run, args=(thread, agent, turn, text, model, effort), daemon=True).start()
        return turn

    def _session_of(self, thread):
        session = (self.config.setdefault("threads", {}).get(thread) or {}).get("session")
        return session if session and SESSION_ID.match(session) else None

    def _spawn(self, args):
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        return subprocess.Popen(
            args, cwd=self.workdir, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding="utf-8", errors="replace", creationflags=flags,
        )

    def _run(self, thread, agent, turn, text, model=None, effort=None):
        state = {"reply": "", "session": None, "error": None, "seen": False}

        def handle(line):
            """Applies one stream-json line to [state]; returns True when the turn's result arrived."""
            parsed = parse_stream_line(line)
            if not parsed:
                return False
            state["seen"] = True
            kind, value = parsed
            if kind == "session":
                state["session"] = value
            elif kind == "delta":
                self.hub.publish({"type": "turn_delta", "thread": thread, "turn": turn, "text": value})
            elif kind == "text":
                reply = state["reply"]
                state["reply"] = (reply + "\n\n" + value).strip() if reply else value
                self.hub.publish({"type": "reply", "thread": thread, "agent": agent, "turn": turn,
                                  "text": state["reply"][-MAX_TEXT * 4:], "done": False})
            elif kind == "result":
                final, sid, is_error = value
                state["session"] = sid or state["session"]
                if final:
                    state["reply"] = final
                if is_error:
                    state["error"] = final or "error"
                return True
            return False

        try:
            done = False
            if self.persistent:
                try:
                    done = self._turn_persistent(thread, agent, text, model, effort, handle, state)
                except PersistentFailed:
                    if state["seen"]:
                        # It already streamed or ran tools: running the message again would repeat its effects
                        state["error"] = state["error"] or "Claude stopped before finishing; send the message again if you want it re-run"
                        done = True
                    else:
                        state.update(reply="", error=None)  # nothing came out at all: retry the old way
            if not done:
                self._turn_oneshot(thread, agent, text, model, effort, handle, state)
        except OSError as e:
            state["error"] = f"could not start Claude on the PC: {e}"
        finally:
            session, reply, error = state["session"], state["reply"], state["error"]
            # The chat must never stay on "still answering": free it and publish the reply BEFORE saving, and a failing
            # save (Windows file lock, concurrent edit) is only logged
            with self.lock:
                self.running.pop(thread, None)
            self.hub.publish({"type": "reply", "thread": thread, "agent": agent, "turn": turn,
                              "text": reply[-MAX_TEXT * 4:], "done": True, "error": error})
            if session and SESSION_ID.match(session):
                try:
                    with getattr(self.config, "lock", threading.RLock()):
                        self.config.setdefault("threads", {})[thread] = {"agent": agent, "session": session}
                    self.save_config()
                except Exception as e:  # noqa: BLE001
                    print("could not save the session id:", e, flush=True)

    def _turn_oneshot(self, thread, agent, text, model, effort, handle, state):
        args = self.command_for(agent, self._session_of(thread), model, effort)
        timed_out = threading.Event()
        proc = self._spawn(args)
        def on_timeout():
            timed_out.set()
            proc.kill()
        timer = threading.Timer(TURN_TIMEOUT, on_timeout)
        timer.start()
        try:
            proc.stdin.write(text)
            proc.stdin.close()
            for line in proc.stdout:
                handle(line)
            proc.wait()
        finally:
            timer.cancel()
            proc.stdout.close()
        if timed_out.is_set():
            state["error"] = f"Claude took longer than {TURN_TIMEOUT // 60} minutes and was stopped"
        elif proc.returncode not in (0, None) and not state["reply"]:
            state["error"] = state["error"] or f"Claude stopped unexpectedly (exit code {proc.returncode})"
        elif not state["reply"] and not state["error"]:
            state["error"] = "Claude finished without an answer"

    def _turn_persistent(self, thread, agent, text, model, effort, handle, state):
        """Feeds [text] to the thread's long-lived process. Returns True when the turn is finished (answer or a
        reported error). Raises PersistentFailed when the process produced nothing usable (caller falls back)."""
        worker = self._worker(thread, agent, model, effort)
        timed_out = threading.Event()
        def on_timeout():
            timed_out.set()
            worker.kill()
        timer = threading.Timer(TURN_TIMEOUT, on_timeout)
        timer.start()
        finished = False
        try:
            try:
                worker.proc.stdin.write(json.dumps({"type": "user", "message": {"role": "user", "content": text}}) + "\n")
                worker.proc.stdin.flush()
            except (OSError, ValueError):
                worker.kill()
                raise PersistentFailed("stdin closed")
            for line in worker.proc.stdout:
                if handle(line):
                    finished = True
                    break
        finally:
            timer.cancel()
            worker.last_used = time.time()
            worker.busy = False
        if not finished:  # the process ended before the result
            worker.kill()
            if timed_out.is_set():
                state["error"] = f"Claude took longer than {TURN_TIMEOUT // 60} minutes and was stopped"
                return True
            raise PersistentFailed("process exited")
        if not state["reply"] and not state["error"]:
            state["error"] = "Claude finished without an answer"
        return True

    # ── Persistent processes ──

    def _worker(self, thread, agent, model, effort):
        key = (agent, model, effort)
        with self.plock:
            current = self.workers.get(thread)
            if current and current.alive() and current.key == key:
                current.busy = True
                return current
            if current:
                current.kill()  # model/effort changed, or it crashed: restart (with --resume)
                self.workers.pop(thread, None)
            while len(self.workers) >= MAX_PERSISTENT:
                idle = [(w.last_used, t) for t, w in self.workers.items() if not w.busy]
                if not idle:
                    raise PersistentFailed("too many persistent processes")
                self.workers.pop(min(idle)[1]).kill()
            try:
                proc = self._spawn(self.command_for(agent, self._session_of(thread), model, effort, persistent=True))
            except OSError as e:
                raise PersistentFailed(str(e))
            worker = Worker(proc, key)
            worker.busy = True
            self.workers[thread] = worker
            if not self.reaper:
                self.reaper = threading.Thread(target=self._reap_loop, daemon=True)
                self.reaper.start()
            return worker

    def reap_idle(self, now=None):
        now = now or time.time()
        with self.plock:
            for thread in list(self.workers):
                w = self.workers[thread]
                if not w.alive() or (not w.busy and now - w.last_used > IDLE_TIMEOUT):
                    w.kill()
                    del self.workers[thread]

    def _reap_loop(self):
        while True:
            time.sleep(30)
            self.reap_idle()

    def shutdown(self):
        with self.plock:
            for w in self.workers.values():
                w.kill()
            self.workers.clear()

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
        with self.plock:
            worker = self.workers.pop(thread, None)
            if worker:
                worker.kill()
        if self.config.get("threads", {}).pop(thread, None) is not None:
            self.save_config()


# ── Config ─────────────────────────────────────────────────────────────────────────────────────────────────


class Config:
    def __init__(self, path=CONFIG_PATH):
        self.path = Path(path)
        self.lock = threading.RLock()  # every mutation of [data] from other threads takes it too
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
            # the CLI (enable-pc-view / pc-lock) edits these two keys from another process: never overwrite them
            enabled, epoch = pcview.read_flags(self.path)
            self.data["pc_view_enabled"], self.data["pc_lock_epoch"] = enabled, epoch
            text = json.dumps(self.data, indent=2)  # a copy taken under the lock; mutators take it as well
            pcview.write_atomic(self.path, text)

    def add_client(self, name):
        name = clean_text(name, MAX_SOURCE, "name")
        token = "lh_" + secrets.token_urlsafe(32)
        with self.lock:
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
    # Traffic that came through tailscale serve / Funnel but has no login (tagged nodes, Funnel) is NOT local
    if caller is None and owner is not None and (headers.get("X-Forwarded-For") or headers.get("Tailscale-Funnel-Request")):
        return None
    return config.client_for(bearer(headers))


class Handler(BaseHTTPRequestHandler):
    hub: Hub = None
    config: Config = None
    agents: Agents = None
    pc: "pcview.PcView" = None
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

    def content_length(self):
        """The validated Content-Length (0..MAX_BODY); anything else is refused before a byte is read."""
        raw = (self.headers.get("Content-Length", "0") or "0").strip()
        if not raw.isdigit():
            raise HubError("bad Content-Length", 400)
        length = int(raw)
        if length > MAX_BODY:
            raise HubError("body too large", 413)
        return length

    def read_json(self):
        length = self.content_length()
        try:
            return json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, UnicodeDecodeError):
            raise HubError("bad json")

    def phone(self):
        if phone_allowed(self.headers, self.owner, self.config.data["phone_token"]):
            return True
        self.send_json(401, {"error": "unauthorized"})
        return False

    def send_bytes(self, code, data, content_type, headers=None):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for key, value in (headers or {}).items():
            self.send_header(key, str(value))
        self.end_headers()
        self.wfile.write(data)

    def pc_route(self, method, path, query):
        """My PC (view only). Owner + phone token first (failures are counted and can lock out), then the unlock token."""
        pc = self.pc
        try:
            if method == "POST":  # the endpoints take no body: drain it so the keep-alive connection stays clean
                self.rfile.read(self.content_length())
            if path == "/pc/lock" and method == "POST":  # always allowed to an authorised phone, even locked out
                if not phone_allowed(self.headers, self.owner, self.config.data["phone_token"]):
                    return self.send_json(401, {"error": "unauthorized"})
                pc.lock_now("phone")
                return self.send_json(200, {"ok": True})
            if pc.enabled():
                left = pc.status().get("retry_after")
                if left:
                    return self.send_json(429, {"error": "locked_out", "retry_after": left})
            # Only requests that pass owner + phone token can count towards (and trigger) the lockout: anyone else on the
            # tailnet or on this PC must not be able to keep the real phone locked out
            if not phone_allowed(self.headers, self.owner, self.config.data["phone_token"]):
                return self.send_json(401, {"error": "unauthorized"})
            token = self.headers.get("X-Lumi-Unlock", "")
            params = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
            if path == "/pc/status" and method == "GET":
                return self.send_json(200, pc.status())
            if path == "/pc/unlock" and method == "POST":
                return self.send_json(200, pc.unlock())
            if path == "/pc/extend" and method == "POST":
                return self.send_json(200, pc.extend(token))
            if path == "/pc/monitors" and method == "GET":
                return self.send_json(200, {"monitors": pc.monitors(token)})
            if path == "/pc/frame" and method == "GET":
                data, info = pc.frame(token, pcview.clamp(params.get("monitor"), 0, 99, 0), params.get("w"), params.get("q"),
                                      self.headers.get("If-None-Match", "").strip('"'))
                headers = {"ETag": f'"{info["etag"]}"', "X-Lumi-Width": info["width"], "X-Lumi-Height": info["height"],
                           "X-Lumi-Quality": info["quality"], "X-Lumi-Delay-Ms": info["delay_ms"]}
                if data is None:
                    return self.send_bytes(304, b"", "image/jpeg", headers)
                return self.send_bytes(200, data, "image/jpeg", headers)
            return self.send_json(404, {"error": "not_found"})
        except pcview.PcError as e:
            self.send_json(e.code, {"error": str(e), **e.extra})
        except HubError as e:
            self.close_connection = True  # an unread body would corrupt the keep-alive connection
            self.send_json(e.code, {"error": str(e)})

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if path.startswith("/pc/"):
            return self.pc_route("GET", path, query)
        if path == "/health":
            if self.phone():
                self.send_json(200, {"ok": True, "host": socket.gethostname(), "agents": self.agents.available(),
                                     "status": self.agents.status(), "uptime_seconds": int(time.time() - START_TIME)})
        elif path == "/agents/options":
            if self.phone():
                self.send_json(200, self.agents.options())
        elif path == "/events":
            if self.phone():
                self.stream_events(query)
        else:
            self.send_json(404, {"error": "not_found"})

    def do_POST(self):
        if self.path.startswith("/pc/"):
            return self.pc_route("POST", self.path.partition("?")[0], "")
        try:
            if self.path == "/mcp":
                return self.mcp()
            if self.path not in ("/answer", "/chat", "/turn", "/forget", "/search", "/agents/options"):
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
            if self.path == "/agents/options":
                return self.send_json(200, {"defaults": self.agents.set_defaults(body.get("model"), body.get("effort"))})
            if self.path in ("/chat", "/turn"):
                turn = self.agents.start_turn(str(body.get("thread", "")), str(body.get("agent", "claude")), body.get("text"),
                                              body.get("model"), body.get("effort"))
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
    try:
        backend = pcview.default_backend()
    except pcview.BackendUnavailable as e:
        print(f"My PC view unavailable: {e}")
        backend = pcview.FakeBackend(monitors=0)
        backend.name = "unavailable"
    Handler.pc = pcview.PcView(pcview.FileFlags(config.path), backend, pcview.AuditLog(Path(config.path).with_suffix(".audit.log")))
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
    print(f"  My PC view (view only):                 {'ENABLED' if Handler.pc.enabled() else 'off (enable: lumi_hub.py enable-pc-view)'}")
    print("Ctrl+C to stop.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        agents.shutdown()
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


def cmd_pc_flag(args):
    enable = args.command == "enable-pc-view"
    path = Path(args.config)
    pcview.write_flags(path, enabled=enable, lock_now=not enable)
    pcview.AuditLog(path.with_suffix(".audit.log")).write("pc_view_enabled" if enable else "pc_view_disabled", "cli")
    if enable:
        print("My PC view is ENABLED (view only: no mouse, keyboard or terminal). Each viewing session still needs an unlock")
        print("from your phone's fingerprint / screen lock. Turn it off again with: lumi_hub.py disable-pc-view")
    else:
        print("My PC view is DISABLED and every unlock was revoked.")


def cmd_pc_lock(args):
    pcview.write_flags(args.config, lock_now=True)
    pcview.AuditLog(Path(args.config).with_suffix(".audit.log")).write("lock", "cli")
    print("Every PC view unlock was revoked; viewers are dropped within seconds.")


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
    for name, text in (("enable-pc-view", "Allow viewing this PC's screens from Lumi (view only, off by default)"),
                       ("disable-pc-view", "Turn My PC view off and revoke every unlock"),
                       ("pc-lock", "Revoke every active PC view unlock now")):
        cmd = sub.add_parser(name, help=text)
        cmd.add_argument("--config", default=str(CONFIG_PATH), help="Config file (default ~/.lumi-hub.json)")
    args = parser.parse_args(argv)
    {"serve": cmd_serve, "add-client": cmd_add_client, "mcp": cmd_mcp, "enable-pc-view": cmd_pc_flag,
     "disable-pc-view": cmd_pc_flag, "pc-lock": cmd_pc_lock}[args.command](args)


if __name__ == "__main__":
    main()
