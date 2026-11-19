/**
 * Lumi relay: a remote MCP server (custom connector for Claude) in front of the tasks that live on the phone.
 *
 *   Claude ──POST /mcp/<secret> (JSON-RPC)──► Relay (one Durable Object) ◄──/api/* (Bearer device token)── Lumi
 *
 * - Reads come from the snapshot Lumi uploads after every change (works while the phone is offline).
 * - Writes are queued as operations. Lumi applies them through its own repository (reminders, recurrence, widget…)
 *   and posts the result. While Lumi is open it long-polls, so the change lands in about a second; otherwise it is
 *   applied the next time Lumi syncs (app start, every 15 minutes in the background).
 * - Single tenant: the first Lumi that claims the relay owns it. The MCP secret is a capability in the URL path, chosen
 *   by Lumi and only stored here as a SHA-256.
 *
 * Tool arguments come from an LLM: they are validated here and only ever become structured task fields on the phone
 * (never commands, intents or prompts).
 */

export interface Env {
  RELAY: DurableObjectNamespace
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url)
    if (url.pathname === "/" && request.method === "GET") {
      return text("Lumi relay. Add <this address>/mcp/<secret> as a custom connector in Claude; the secret is in Lumi > Settings > Claude.")
    }
    const stub = env.RELAY.get(env.RELAY.idFromName("main"))
    return stub.fetch(request)
  },
}

// ── Types ──────────────────────────────────────────────────────────────────────────────────────────────────────────

type Kind = "task" | "reminder" | "block"

interface Claim { device: string; mcp: string; at: number }

/** One task as Lumi uploads it. Dates are local to [Snapshot.zone]. */
interface SnapTask {
  id: number
  title: string
  notes?: string
  kind: Kind
  status: string
  category: string
  priority: string
  date?: string | null
  time?: string | null
  end_time?: string | null
  repeat?: string | null
  place?: string | null
  completed_at?: string | null
}

interface Snapshot { tasks: SnapTask[]; zone: string; today: string; at: number; app_version?: string }

interface Op {
  id: string
  type: "create" | "update" | "remove"
  args: unknown
  created: number
  status: "pending" | "done" | "failed"
  result?: unknown
  done?: number
}

const MAX_OPS_KEPT = 200
const WRITE_WAIT_MS = 9000
const LONG_POLL_MAX_S = 25

// ── Durable Object ─────────────────────────────────────────────────────────────────────────────────────────────────

export class Relay {
  private waiters = new Map<string, (op: Op) => void>()
  private pollers = new Set<() => void>()

  constructor(private state: DurableObjectState, _env: Env) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url)
    const path = url.pathname
    try {
      if (path.startsWith("/mcp/")) return await this.mcp(request, path.slice(5).replace(/\/+$/, ""))
      if (path === "/api/claim" && request.method === "POST") return await this.claim(request)
      if (path.startsWith("/api/")) {
        if (!(await this.deviceOk(request))) return json({ error: "unauthorized" }, 401)
        await this.state.storage.put("lastSeen", Date.now())
        if (path === "/api/snapshot" && request.method === "PUT") return await this.putSnapshot(request)
        if (path === "/api/ops" && request.method === "GET") return await this.pendingOps(url)
        const m = path.match(/^\/api\/ops\/([\w-]+)\/result$/)
        if (m && request.method === "POST") return await this.opResult(m[1], request)
        if (path === "/api/status" && request.method === "GET") return json({ ok: true, ops: (await this.ops()).filter(o => o.status === "pending").length })
      }
      return json({ error: "not found" }, 404)
    } catch (e) {
      return json({ error: String((e as Error)?.message ?? e) }, 500)
    }
  }

  // ── Phone side ──

  private async claim(request: Request): Promise<Response> {
    const body = (await request.json()) as { device_token?: string; mcp_secret?: string }
    const device = str(body.device_token), mcp = str(body.mcp_secret)
    if (device.length < 32 || mcp.length < 32) return json({ error: "tokens too short" }, 400)
    const existing = await this.state.storage.get<Claim>("claim")
    // Already claimed: only the same phone may claim again (to rotate the Claude secret)
    if (existing && existing.device !== (await sha256(device))) return json({ error: "already claimed by another device" }, 409)
    await this.state.storage.put("claim", { device: await sha256(device), mcp: await sha256(mcp), at: Date.now() } satisfies Claim)
    await this.state.storage.put("lastSeen", Date.now())
    return json({ ok: true })
  }

  private async deviceOk(request: Request): Promise<boolean> {
    const claim = await this.state.storage.get<Claim>("claim")
    const token = (request.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "")
    return !!claim && token.length >= 32 && safeEqual(claim.device, await sha256(token))
  }

  private async putSnapshot(request: Request): Promise<Response> {
    const snap = (await request.json()) as Snapshot
    if (!Array.isArray(snap.tasks)) return json({ error: "bad snapshot" }, 400)
    snap.at = Date.now()
    await this.state.storage.put("snapshot", snap)
    return json({ ok: true })
  }

  private async pendingOps(url: URL): Promise<Response> {
    const wait = Math.min(Number(url.searchParams.get("wait") ?? 0) || 0, LONG_POLL_MAX_S)
    let pending = (await this.ops()).filter(o => o.status === "pending")
    if (pending.length === 0 && wait > 0) {
      await new Promise<void>(resolve => {
        const done = () => { clearTimeout(timer); this.pollers.delete(done); resolve() }
        const timer = setTimeout(done, wait * 1000)
        this.pollers.add(done)
      })
      pending = (await this.ops()).filter(o => o.status === "pending")
    }
    return json({ ops: pending.map(o => ({ id: o.id, type: o.type, args: o.args })) })
  }

  private async opResult(id: string, request: Request): Promise<Response> {
    const body = (await request.json()) as { ok?: boolean; result?: unknown }
    const ops = await this.ops()
    const op = ops.find(o => o.id === id)
    if (!op) return json({ error: "unknown op" }, 404)
    op.status = body.ok === false ? "failed" : "done"
    op.result = body.result ?? null
    op.done = Date.now()
    await this.saveOps(ops)
    this.waiters.get(id)?.(op)
    return json({ ok: true })
  }

  // ── Ops queue ──

  private async ops(): Promise<Op[]> {
    return (await this.state.storage.get<Op[]>("ops")) ?? []
  }

  private async saveOps(ops: Op[]) {
    // Keep every pending op, and only the most recent finished ones
    const pending = ops.filter(o => o.status === "pending")
    const finished = ops.filter(o => o.status !== "pending").slice(-Math.max(0, MAX_OPS_KEPT - pending.length))
    await this.state.storage.put("ops", [...finished, ...pending].sort((a, b) => a.created - b.created))
  }

  /** Queues [type] and waits a few seconds for Lumi to apply it. */
  private async enqueue(type: Op["type"], args: unknown): Promise<{ op: Op; applied: boolean }> {
    const op: Op = { id: crypto.randomUUID(), type, args, created: Date.now(), status: "pending" }
    const ops = await this.ops()
    ops.push(op)
    await this.saveOps(ops)
    const finished = new Promise<Op>(resolve => this.waiters.set(op.id, resolve))
    this.pollers.forEach(wake => wake())
    const result = await Promise.race([finished, sleep(WRITE_WAIT_MS).then(() => null)])
    this.waiters.delete(op.id)
    return result ? { op: result, applied: true } : { op, applied: false }
  }

  // ── MCP (streamable HTTP, JSON responses) ──

  private async mcp(request: Request, secret: string): Promise<Response> {
    const claim = await this.state.storage.get<Claim>("claim")
    if (!claim || secret.length < 32 || !safeEqual(claim.mcp, await sha256(secret))) return json({ error: "unknown connector" }, 404)
    if (request.method === "GET") return new Response(null, { status: 405, headers: { allow: "POST" } })
    if (request.method === "DELETE") return new Response(null, { status: 204 })
    if (request.method !== "POST") return new Response(null, { status: 405 })

    const body = await request.json().catch(() => null)
    if (body === null) return json(rpcError(null, -32700, "Parse error"), 400)
    if (Array.isArray(body)) {
      const out = (await Promise.all(body.map(m => this.rpc(m)))).filter(Boolean)
      return out.length ? json(out) : new Response(null, { status: 202 })
    }
    const out = await this.rpc(body)
    return out ? json(out) : new Response(null, { status: 202 })
  }

  private async rpc(msg: any): Promise<unknown | null> {
    const id = msg?.id ?? null
    const isNotification = msg?.id === undefined
    switch (msg?.method) {
      case "initialize": {
        const asked = str(msg.params?.protocolVersion)
        return rpcResult(id, {
          protocolVersion: PROTOCOLS.includes(asked) ? asked : PROTOCOLS[0],
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: "lumi", title: "Lumi", version: "1.0.0" },
          instructions: INSTRUCTIONS,
        })
      }
      case "ping":
        return rpcResult(id, {})
      case "tools/list":
        return rpcResult(id, { tools: TOOLS })
      case "tools/call":
        try {
          const content = await this.callTool(str(msg.params?.name), msg.params?.arguments ?? {})
          return rpcResult(id, { content: [{ type: "text", text: content }] })
        } catch (e) {
          return rpcResult(id, { content: [{ type: "text", text: `Error: ${(e as Error).message}` }], isError: true })
        }
      default:
        if (isNotification) return null
        return rpcError(id, -32601, `Method not found: ${msg?.method}`)
    }
  }

  private async callTool(name: string, args: any): Promise<string> {
    switch (name) {
      case "list_items": return this.listItems(args)
      case "create_items": {
        const items = asArray(args.items, "items").map((it, i) => cleanItem(it, `items[${i}]`, true))
        if (items.length === 0) throw new Error("items is empty")
        return this.write("create", { items })
      }
      case "update_items": {
        const items = asArray(args.items, "items").map((it, i) => {
          const id = Number((it as any)?.id)
          if (!Number.isInteger(id) || id <= 0) throw new Error(`items[${i}].id must be the numeric id from list_items`)
          return { id, ...cleanItem(it, `items[${i}]`, false) }
        })
        if (items.length === 0) throw new Error("items is empty")
        return this.write("update", { items })
      }
      case "remove_items": {
        const ids = asArray(args.ids, "ids").map(Number)
        if (ids.some(n => !Number.isInteger(n) || n <= 0)) throw new Error("ids must be numeric ids from list_items")
        return this.write("remove", { ids })
      }
      default:
        throw new Error(`Unknown tool ${name}`)
    }
  }

  private async write(type: Op["type"], args: unknown): Promise<string> {
    const { op, applied } = await this.enqueue(type, args)
    if (applied) {
      return JSON.stringify({ applied: op.status === "done", result: op.result }, null, 1)
    }
    const lastSeen = (await this.state.storage.get<number>("lastSeen")) ?? 0
    return JSON.stringify({
      applied: false,
      queued: true,
      note: "Lumi is closed right now. The change is saved and will be applied on the phone the next time Lumi syncs " +
        "(when the user opens Lumi, or within about 15 minutes). Do not send it again.",
      phone_last_seen: lastSeen ? new Date(lastSeen).toISOString() : null,
    }, null, 1)
  }

  private async listItems(args: any): Promise<string> {
    const snap = await this.state.storage.get<Snapshot>("snapshot")
    if (!snap) return "Lumi has not uploaded any tasks yet. Ask the user to open Lumi > Settings > Claude and tap Sync now."
    const from = optDate(args.from, "from"), to = optDate(args.to, "to")
    const includeDone = args.include_done === true
    const kind = args.kind ? oneOf(args.kind, KINDS, "kind") : null
    const query = str(args.query).toLowerCase()
    const items = snap.tasks.filter(t => {
      if (!includeDone && (t.status === "COMPLETED" || t.status === "CANCELLED")) return false
      if (kind && t.kind !== kind) return false
      if (query && !(t.title.toLowerCase().includes(query) || (t.notes ?? "").toLowerCase().includes(query))) return false
      if (from || to) {
        if (!t.date) return args.include_undated === true
        if (from && t.date < from) return false
        if (to && t.date > to) return false
      }
      return true
    })
    const pending = (await this.ops()).filter(o => o.status === "pending")
    const lastSeen = (await this.state.storage.get<number>("lastSeen")) ?? 0
    return JSON.stringify({
      today: snap.today,
      timezone: snap.zone,
      snapshot_from: new Date(snap.at).toISOString(),
      phone_last_seen: lastSeen ? new Date(lastSeen).toISOString() : null,
      count: items.length,
      items: items.map(t => compact(t)),
      pending_changes_not_yet_on_phone: pending.map(o => ({ type: o.type, args: o.args, queued_at: new Date(o.created).toISOString() })),
    }, null, 1)
  }
}

// ── Tool definitions ───────────────────────────────────────────────────────────────────────────────────────────────

const PROTOCOLS = ["2025-06-18", "2025-11-25", "2025-03-26", "2024-11-05"]
const KINDS: Kind[] = ["task", "reminder", "block"]
const CATEGORIES = ["personal", "work", "study", "health", "other"]
const PRIORITIES = ["none", "low", "medium", "high"]
const STATUSES = ["todo", "in_progress", "done", "cancelled"]

const INSTRUCTIONS = `Lumi is the user's task manager and weekly planner on their phone. Items have a kind:
- task: something to do and check off (optionally with a date/time).
- reminder: just a nudge at a moment ("call mum at 18:00", "take the pill"); not a to-do.
- block: a slot of the weekly schedule with a start and end ("work 9:00-17:00", "gym 19:00-20:00"). Blocks and reminders stay out of the phone calendar.
Always call list_items first to see what exists (and the user's today/timezone). Create many items in ONE create_items call, each with its own date. Dates are the user's local time. Before removing items, confirm with the user.`

const itemProps = {
  title: { type: "string", description: "Short title, in the user's language" },
  kind: { type: "string", enum: KINDS, description: "task (to-do), reminder (nudge at a time), block (schedule slot with start and end). Default task" },
  date: { type: "string", description: "Local date YYYY-MM-DD" },
  time: { type: "string", description: "Local start time HH:MM (24 h). Omit for an all-day item" },
  end_time: { type: "string", description: "Local end time HH:MM, for blocks (or use duration_minutes)" },
  duration_minutes: { type: "integer", description: "Length of a block in minutes (alternative to end_time)" },
  notes: { type: "string", description: "Details" },
  category: { type: "string", enum: CATEGORIES },
  priority: { type: "string", enum: PRIORITIES },
  repeat: { type: "string", description: "daily | weekdays | weekly:mo,we,fr | monthly:15 | yearly | none" },
  remind_before_minutes: { type: "array", items: { type: "integer" }, description: "Extra reminders before the start, in minutes" },
}

const TOOLS = [
  {
    name: "list_items",
    title: "List Lumi items",
    description: "Lists the user's tasks, reminders and schedule blocks from Lumi (ids, dates, status). Call it before editing.",
    inputSchema: {
      type: "object",
      properties: {
        from: { type: "string", description: "First local date YYYY-MM-DD (inclusive)" },
        to: { type: "string", description: "Last local date YYYY-MM-DD (inclusive)" },
        kind: { type: "string", enum: KINDS },
        query: { type: "string", description: "Text to search in titles and notes" },
        include_done: { type: "boolean", description: "Also completed and cancelled items (default false)" },
        include_undated: { type: "boolean", description: "With from/to, also items without a date (default false)" },
      },
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: "create_items",
    title: "Create Lumi items",
    description: "Creates one or more tasks, reminders or schedule blocks in Lumi in a single call. Each item keeps its own date and time.",
    inputSchema: {
      type: "object",
      properties: { items: { type: "array", maxItems: 60, items: { type: "object", properties: itemProps, required: ["title"] } } },
      required: ["items"],
    },
  },
  {
    name: "update_items",
    title: "Edit Lumi items",
    description: "Edits items by id: title, kind, date/time (reschedule), end, notes, category, priority, repeat, or status (done, cancelled…). Only the given fields change; set clear_date to remove the date.",
    inputSchema: {
      type: "object",
      properties: {
        items: {
          type: "array", maxItems: 60,
          items: {
            type: "object",
            properties: {
              id: { type: "integer" }, ...itemProps,
              status: { type: "string", enum: STATUSES },
              clear_date: { type: "boolean", description: "Remove the date and time" },
            },
            required: ["id"],
          },
        },
      },
      required: ["items"],
    },
  },
  {
    name: "remove_items",
    title: "Remove Lumi items",
    description: "Removes items by id (they are cancelled in Lumi, so the user can still restore them). Confirm with the user first.",
    inputSchema: { type: "object", properties: { ids: { type: "array", items: { type: "integer" }, maxItems: 100 } }, required: ["ids"] },
    annotations: { destructiveHint: true },
  },
]

// ── Validation ─────────────────────────────────────────────────────────────────────────────────────────────────────

function cleanItem(raw: unknown, where: string, creating: boolean): Record<string, unknown> {
  if (typeof raw !== "object" || raw === null) throw new Error(`${where} must be an object`)
  const it = raw as Record<string, unknown>
  const out: Record<string, unknown> = {}
  if (it.title !== undefined) {
    const title = str(it.title).trim().slice(0, 200)
    if (!title) throw new Error(`${where}.title is empty`)
    out.title = title
  } else if (creating) throw new Error(`${where}.title is required`)
  if (it.kind !== undefined) out.kind = oneOf(it.kind, KINDS, `${where}.kind`)
  if (it.date !== undefined && it.date !== null) out.date = optDate(it.date, `${where}.date`)
  if (it.time !== undefined && it.time !== null) out.time = optTime(it.time, `${where}.time`)
  if (it.end_time !== undefined && it.end_time !== null) out.end_time = optTime(it.end_time, `${where}.end_time`)
  if (it.duration_minutes !== undefined && it.duration_minutes !== null) {
    const d = Number(it.duration_minutes)
    if (!Number.isInteger(d) || d <= 0 || d > 24 * 60) throw new Error(`${where}.duration_minutes must be 1..1440`)
    out.duration_minutes = d
  }
  if (it.notes !== undefined) out.notes = str(it.notes).slice(0, 4000)
  if (it.category !== undefined) out.category = oneOf(it.category, CATEGORIES, `${where}.category`)
  if (it.priority !== undefined) out.priority = oneOf(it.priority, PRIORITIES, `${where}.priority`)
  if (it.repeat !== undefined) {
    const r = str(it.repeat).trim().toLowerCase()
    if (!/^(none|daily|weekdays|yearly|weekly(:(mo|tu|we|th|fr|sa|su)(,(mo|tu|we|th|fr|sa|su))*)?|monthly(:([1-9]|[12]\d|3[01]))?)$/.test(r))
      throw new Error(`${where}.repeat must be daily | weekdays | weekly:mo,th | monthly:15 | yearly | none`)
    out.repeat = r
  }
  if (it.remind_before_minutes !== undefined) {
    out.remind_before_minutes = asArray(it.remind_before_minutes, `${where}.remind_before_minutes`).map(Number)
      .filter(n => Number.isInteger(n) && n > 0 && n <= 60 * 24 * 14).slice(0, 5)
  }
  if (!creating) {
    if (it.status !== undefined) out.status = oneOf(it.status, STATUSES, `${where}.status`)
    if (it.clear_date === true) out.clear_date = true
  }
  if ((out.end_time || out.duration_minutes) && !out.time && creating) throw new Error(`${where}: a block with an end needs a start time`)
  return out
}

function compact(t: SnapTask) {
  const o: Record<string, unknown> = { id: t.id, title: t.title, kind: t.kind }
  if (t.date) o.date = t.date
  if (t.time) o.time = t.time
  if (t.end_time) o.end_time = t.end_time
  if (t.status !== "TODO") o.status = t.status.toLowerCase()
  o.category = t.category.toLowerCase()
  if (t.priority !== "NONE") o.priority = t.priority.toLowerCase()
  if (t.repeat) o.repeat = t.repeat
  if (t.place) o.place = t.place
  if (t.notes) o.notes = t.notes.length > 300 ? t.notes.slice(0, 300) + "…" : t.notes
  return o
}

function oneOf<T extends string>(v: unknown, allowed: readonly T[], where: string): T {
  const s = str(v).trim().toLowerCase() as T
  if (!allowed.includes(s)) throw new Error(`${where} must be one of ${allowed.join(", ")}`)
  return s
}

function optDate(v: unknown, where: string): string | null {
  if (v === undefined || v === null || v === "") return null
  const s = str(v).trim()
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s) || isNaN(Date.parse(s + "T00:00:00Z"))) throw new Error(`${where} must be YYYY-MM-DD`)
  return s
}

function optTime(v: unknown, where: string): string {
  const s = str(v).trim()
  const m = s.match(/^(\d{1,2}):(\d{2})$/)
  if (!m || Number(m[1]) > 23 || Number(m[2]) > 59) throw new Error(`${where} must be HH:MM (24 h)`)
  return `${m[1].padStart(2, "0")}:${m[2]}`
}

function asArray(v: unknown, where: string): unknown[] {
  if (!Array.isArray(v)) throw new Error(`${where} must be an array`)
  return v
}

// ── Helpers ────────────────────────────────────────────────────────────────────────────────────────────────────────

function str(v: unknown): string {
  return typeof v === "string" ? v : v === undefined || v === null ? "" : String(v)
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8" } })
}

function text(body: string): Response {
  return new Response(body, { headers: { "content-type": "text/plain; charset=utf-8" } })
}

function rpcResult(id: unknown, result: unknown) {
  return { jsonrpc: "2.0", id, result }
}

function rpcError(id: unknown, code: number, message: string) {
  return { jsonrpc: "2.0", id, error: { code, message } }
}

function sleep(ms: number) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

async function sha256(s: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s))
  return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("")
}

function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false
  let diff = 0
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i)
  return diff === 0
}
