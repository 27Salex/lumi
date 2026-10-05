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

## Endpoints

| Who | Method | Path | |
|---|---|---|---|
| phone | GET | `/health` | host + available agents |
| phone | GET | `/events?since=<id>` | SSE stream; open questions are always re-sent on connect |
| phone | POST | `/answer` | `{ask_id, answer}` → unblocks `lumi_ask` (410 if no longer open) |
| phone | POST | `/chat` | `{thread, agent, text}` → one agent turn, reply as `reply` events |
| phone | POST | `/forget` | `{thread}` → the next message starts a new agent session |
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
