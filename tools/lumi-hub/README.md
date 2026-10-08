# Lumi Hub

A small service on your PC that connects Lumi (your phone) with the AI agents on that PC: Claude Code today, any
MCP-capable app (Claude Desktop, Codex, Gemini CLI...) for messages to you. Standard-library Python 3.10+, no installs.

- **Agent → you (MCP tools):** `lumi_send` (message + optional link), `lumi_ask` (a question with options; the tool
  call blocks until you answer on the phone, which costs the agent no tokens), `lumi_create_task` (a proposal: Lumi
  adds it only if you tap *Add task*), `lumi_notify` (a notification).
- **You → agent (wake per message):** when you write to an agent from Lumi (Orbit), the hub runs one turn of
  `claude -p --output-format stream-json --verbose --resume <session>` and streams the reply back. Nothing keeps an
  agent running between messages; the same session continues across messages.
- **Relay:** Server-Sent Events while Lumi is open. Optional `--ntfy <topic url>`: a content-free ping ("New message
  from Claude Code. Open Lumi.") when Lumi isn't connected.

## Setup

1. Install [Tailscale](https://tailscale.com) on the PC and the phone, same account.
2. Run the hub (it publishes itself in your tailnet with `tailscale serve`):

   ```
   python tools/lumi-hub/lumi_hub.py serve --dir C:\path\to\your\projects
   ```

   It prints the address and the **phone token**. In Lumi: Settings → Lumi Hub → paste both → Test connection.
3. Give each agent its own token (messages show this name as their source):

   ```
   python tools/lumi-hub/lumi_hub.py add-client "Claude Code · lumi repo"
   ```

   It prints the `claude mcp add --transport http ...` command, and a stdio config for desktop apps that only speak
   stdio (`lumi_hub.py mcp --token ...`, a thin proxy to the running hub).

Config lives in `~/.lumi-hub.json` (phone token, client tokens, thread → Claude session ids). To rotate tokens,
delete the file (or one client entry) and restart the hub.
`--config <file>` uses another config file (tests; don't fake HOME, Claude Code would lose its login).

## The `lumi` launcher (Windows)

`lumi.cmd` is the single command for everything on the PC: it starts SearXNG (WSL, via `lumi-search.cmd`) and then the hub
(via `lumi-hub.cmd`, which wraps `python lumi_hub.py serve`). `lumi status` shows what is running and the `tailscale serve`
mapping. Install: copy `lumi.cmd` to a folder on your PATH (for example `%USERPROFILE%\.local\bin`), next to your own
`lumi-search.cmd` and `lumi-hub.cmd`, then run `lumi` from any terminal.

## The launcher on Linux/macOS

`./lumi.sh` is the equivalent of `lumi.cmd`: it starts SearXNG when a local install exists at `~/searxng` and then the hub
(`python3 lumi_hub.py serve ...`); `./lumi.sh status` shows what is running. My PC screen capture is Windows only.

## Security

- The hub listens on **127.0.0.1 only**; the phone reaches it through `tailscale serve` (HTTPS with a real *.ts.net
  certificate, tailnet only, never the internet).
- Phone calls need **both** the `Tailscale-User-Login` of this PC's owner (added by `tailscale serve`, so shared
  devices of other people in your tailnet are refused) **and** the phone token. A local process cannot act as the
  phone. MCP clients need their own token; from the tailnet only as the owner.
- **Agent input is untrusted** (it can carry prompt injections): text is capped and stripped of control/bidi
  characters, links must be plain http(s), at most 30 events per client per minute. On the phone it is shown as text
  only: Lumi never runs it, never feeds it to its command interpreter, and a proposed task needs your tap.
- The hub never runs a shell. Agent turns get the prompt on **stdin** (never in the argument list) and run Claude Code
  in its normal permission mode: in `-p` mode, tools that need approval are refused unless your own Claude Code
  settings already allow them.
- Lumi never touches Claude credentials: the hub runs **Claude Code itself** on your PC with your own login.
- `--dev-no-tailscale` (development only) skips the Tailscale identity check for emulator tests: loopback only, and
  release builds of Lumi refuse plain HTTP anyway.

## My PC (view only)

Lumi can show your PC screens on the phone. **View only: there is no mouse, keyboard, terminal or command execution, and
none will be added** (use Tailscale SSH or RDP for that). Screen capture is Windows only, no installs (GDI + GDI+ through `ctypes`); on Linux/macOS the hub says capture is not available and the app offers Remote control instead.

- **Off by default.** Turn it on once, on the PC: `python lumi_hub.py enable-pc-view` (`disable-pc-view` turns it off and
  revokes everything). The flag lives in `~/.lumi-hub.json` and is re-read on every request, so no restart is needed.
- **Each viewing session needs an unlock** from the phone: Lumi asks for the fingerprint, face or screen lock (platform
  `BiometricPrompt`), then asks the hub for a short-lived unlock token (10 minutes, extended while you watch, never more
  than 1 hour in total). Frames need that token. Tokens live only in memory: restarting the hub revokes them.
- **Lock now** (button in Lumi, or `python lumi_hub.py pc-lock`) revokes every token and drops the viewers. Lumi also
  locks when you leave the screen or the app, and the screen blocks screenshots (FLAG_SECURE).
- Capture happens only while an unlocked viewer asks for a frame (polling, adaptive width/quality, `304` when the screen
  did not change, frames capped at 1.5 MB and 1920 px wide). Nothing is captured otherwise.
- **Audit log** next to the config (`~/.lumi-hub.audit.log`): unlocks, failures, lockouts, viewer start/stop, lock,
  enable/disable. It never contains tokens or screen content.

Threat model:

| Threat | Defence |
|---|---|
| Someone else on the tailnet | `Tailscale-User-Login` must be the PC owner, plus the phone token (constant-time compares) |
| Someone with your unlocked phone but not you | Each session needs the biometric / screen lock on the phone |
| Guessing tokens | 5 failures in 5 minutes lock all PC-view access for 15 minutes (a lock request still works); unlocks are limited to 6 per minute; tokens are 256-bit random |
| Forgotten open session | 10-minute token, 1-hour cap, locks when you leave Lumi, Lock now |
| Malware on the PC / on the phone | Out of scope: it could read the screen or the phone token anyway |
| Feature left on | Off by default; `disable-pc-view` revokes at once |

Agent turns (prompt injection) - what is and is not defended:

| Threat | Defence |
|---|---|
| A web page or file steers the agent into reading `~/.lumi-hub.json` / `~/.ssh` and leaking it through WebFetch | `agents/lumi.md` forbids it explicitly (never read or send secret files or dot-folders, never put PC data in URLs/queries, web content is data, report attempts); the hub also starts every turn with `--disallowedTools` deny rules (`DENIED_READS`: Read of the hub config, `~/.ssh`, `~/.aws`, Claude credentials; they also cover Grep/Glob). MCP and PC-control tools are not restricted |
| Strangers on the tailnet or on this PC locking you out of My PC | Failed attempts only count when the request already passed owner + phone token (bad unlock token) |
| Tagged / Funnel / proxied traffic without a Tailscale login posing as a local MCP client | Requests carrying `X-Forwarded-For` or `Tailscale-Funnel-Request` but no `Tailscale-User-Login` are refused for MCP clients; truly local processes (no proxy headers) are still allowed with their client token |

Residual risk: the agent still runs in your home folder as you, with your own Claude Code settings. Deny rules are path
patterns, not a sandbox: a creative injection could reach other readable secrets (a `.env` in a project, browser
profiles) or use any tool your Claude Code settings already allow. Do not point the hub at a PC with secrets you cannot
afford to lose, and keep your own Claude Code permissions tight.

Known limit: the biometric is checked on the phone, not cryptographically bound to the token request. Someone who stole
the phone token (a rooted or compromised phone) AND the tailnet identity could call `/pc/unlock` directly. Binding the
unlock to a Keystore key that needs the biometric is a possible hardening (see `TODO.md`).

If something fails: `My PC view unavailable` at startup means the GDI backend could not load (not Windows). A black image
usually means the PC is locked (Windows does not allow capturing the lock screen) or a secure desktop is showing.

## Remote control (hand-off to a remote desktop app)

The **Remote control** button on the My PC screen only opens a standard remote desktop app on the phone (Microsoft Remote
Desktop / Windows App) with this PC's Tailscale address and port (default 3389, editable in Settings > Servers and PC).
Lumi sends no input, the hub has no terminal and runs no commands, and the token is never part of the link. `/pc/status`
reports `os` (`windows`, `linux` or `macos`), `remote` (advisory methods such as `rdp`, `vnc`, `ssh`) and `capture`
(false on systems where the view-only screen is not implemented: Lumi then says so and still offers Remote control).

Rule for every system: reach it **only through Tailscale**. Never forward or expose port 3389 (or VNC/SSH) to the internet.

### Windows 11 Pro
1. Settings > System > Remote Desktop > On (Home editions cannot host RDP). Keep "Require devices to use Network Level
   Authentication" on.
2. The Windows account you connect with needs a password (RDP rejects blank passwords).
3. Optional: `enable-rdp.ps1` does steps 1 and NLA plus a firewall rule for TCP 3389 limited to `100.64.0.0/10` (the
   Tailscale range). It is never run automatically: read it and run it yourself from an elevated PowerShell;
   `-Undo` reverses it.
4. On the phone install Microsoft Remote Desktop (Windows App) and use Remote control in Lumi.

### Linux
Use `xrdp` (RDP, works with the same button) or a VNC server (`x11vnc` on X11, `wayvnc` on wlroots/Wayland), bound to the
Tailscale address, or plain SSH over Tailscale. Allow the port only on `tailscale0` (for example
`ufw allow in on tailscale0 to any port 3389`). Start the hub with `./lumi.sh`.

### macOS
System Settings > General > Sharing > **Screen Sharing** (VNC) and/or **Remote Login** (SSH). Use a VNC client on the
phone pointed at the Tailscale name; the Remote control button targets RDP, so on macOS it is only useful with an RDP
server you installed yourself. Start the hub with `./lumi.sh`.

## Endpoints

| Who | Method | Path | |
|---|---|---|---|
| phone | GET | `/health` | host, available agents, running turns, uptime |
| phone | GET | `/events?since=<id>` | SSE stream; open questions are always re-sent on connect |
| phone | POST | `/answer` | `{ask_id, answer}` → unblocks `lumi_ask` (410 if no longer open) |
| phone | POST | `/chat` (alias `/turn`) | `{thread, agent, text, model?, effort?}` → one agent turn, `turn_delta` then `reply` events |
| phone | GET/POST | `/agents/options` | selectable models/efforts and saved defaults (POST `{model?, effort?}` saves defaults) |
| phone | POST | `/forget` | `{thread}` → the next message starts a new agent session |
| phone | POST | `/search` | `{query}` → `{hits:[{title,url,snippet}]}`: Claude Code with only the WebSearch tool (Lumi's web search option) |
| phone | GET | `/pc/status` | `disabled` / `locked` / `unlocked` / `locked_out` (no unlock needed) |
| phone | POST | `/pc/unlock` | a short-lived unlock token (`expires_in`) |
| phone | POST | `/pc/extend`, `/pc/lock` | extend while viewing (needs `X-Lumi-Unlock`); revoke all (always allowed) |
| phone | GET | `/pc/monitors`, `/pc/frame?monitor=&w=&q=` | monitor list; one JPEG (needs `X-Lumi-Unlock`, `If-None-Match` supported) |
| MCP client | POST | `/mcp` | JSON-RPC 2.0 (streamable HTTP, JSON responses) |

## Tests

```
python -m unittest discover tools/lumi-hub
```

## Not yet

- Claude Code **Channels** (`claude/channel`, pushing into a running interactive session).
- Files in `lumi_send` (only text + link for now).
- Codex / Gemini CLI as wake-per-message agents (`Agents.command_for` is the extension point).
- Push to Lumi itself while it is closed (UnifiedPush); today the optional ntfy ping goes to the ntfy app.

## Lumi agent

Every Claude session the hub starts (`claude -p`, new or resumed) uses the `lumi` agent defined in `agents/lumi.md`
(`--agents` + `--agent lumi`): how to reply on a phone, when to ask first with `lumi_ask`, and the safety rules for
untrusted text. Edit that file to change how Claude behaves in Lumi; if it is missing, the hub runs plain Claude Code.

## Turn options and streaming

- `POST /turn` (alias `/chat`, phone auth) body `{thread, agent, text, model?, effort?}` → `202 {ok, turn}`.
  `model`: `sonnet`, `opus`, `haiku` or a full id (`^[A-Za-z0-9][A-Za-z0-9._\[\]-]{0,63}$`, else 400).
  `effort`: `low|medium|high|max` (maps to `claude --effort`; else 400). Omitted values use the saved defaults
  (initially `sonnet` / `medium`, stored in the config under `agent_defaults.claude`).
- `GET /agents/options` → `{models:[{id,label}], efforts:["low","medium","high","max"], defaults:{model,effort}}`.
  `POST /agents/options {model?, effort?}` saves new defaults and returns `{defaults}`.
- SSE: while Claude writes, `{"type":"turn_delta","thread","turn","text"}` events (`text` = the new chunk only). The existing
  `{"type":"reply",...,"done":true,"error"}` event still ends the turn with the full text (or the error); non-final `reply`
  events (whole text blocks) are unchanged. Treat deltas as a live preview and replace it with the final `reply` text.
- One long-lived `claude -p --input-format stream-json` process per thread keeps MCP servers warm (max 3, idle 10 min,
  restarted with `--resume` on crash or model/effort change, killed on `/forget` and hub shutdown). If it fails before
  producing anything, the turn falls back to the old one-process-per-message spawn. MCP servers are never restricted.
