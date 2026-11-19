# Lumi relay (Claude connector)

A remote MCP server on Cloudflare Workers (free plan) so Claude (mobile, web or desktop app) can read and edit Lumi's
tasks, reminders and schedule **without the PC**. Claude's custom connectors are called from Anthropic's cloud, so the
server must be public HTTPS; the phone can't host it (CGNAT, sleep), hence a relay.

```
Claude ──POST /mcp/<secret>──► Worker + Durable Object ◄──/api/* (device token)── Lumi
```

- **Reads** (`list_items`) come from the snapshot Lumi uploads after each change, so they work with the phone asleep.
- **Writes** (`create_items`, `update_items`, `remove_items`) are queued. While Lumi is on screen it long-polls and
  applies them in about a second (Claude gets the result). Otherwise Claude gets "queued" and Lumi applies them on the
  next sync: app start or the 15-minute background job. `remove_items` cancels (restorable), never deletes.
- Items have a `kind`: `task` (to-do), `reminder` (a nudge at a time), `block` (schedule slot with start and end).
  Reminders and blocks never go to the phone calendar or Google Tasks.

## Deploy (once)

```powershell
cd tools/lumi-relay
npm install
npx wrangler login     # approve in the browser (free Cloudflare account)
npx wrangler deploy    # prints https://lumi-relay.<your-subdomain>.workers.dev
```

Then in Lumi: **Settings › Claude**, paste that address, **Connect**, copy the "Address for Claude" and add it in the
Claude app: **Settings › Connectors › Add custom connector** (name Lumi).

## Security

- Single tenant: the first phone that calls `/api/claim` owns the relay (only that device token may claim it again).
  To reset ownership: `npx wrangler secret put ADMIN_KEY` once, then
  `curl -X POST https://<relay>/api/admin/reset -H "x-admin-key: <key>"` (wipes claim, snapshot and queue).
- The Claude address is a capability URL (`/mcp/<43-char secret>`); the relay only stores SHA-256 hashes of both
  secrets. "New link" in Lumi rotates it.
- Tool arguments are validated here and again in Lumi (`RelayItems`); they only become task fields, never commands,
  intents or prompts (AGENTS.md rule 9). A prompt-injected Claude can at worst add/edit/cancel tasks.

## Local test

`npx wrangler dev` then curl `POST /api/claim`, `PUT /api/snapshot`, `POST /mcp/<secret>` (JSON-RPC
`initialize`, `tools/list`, `tools/call`).
