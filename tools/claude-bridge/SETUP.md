# Claude on your PC (Lumi → Claude Code over Tailscale)

Say **"Claude, fix the failing build"** (or «Claude, arregla el build», «dile a Claude que…», "tell Claude to…") and
Lumi opens a new terminal on your PC running an interactive Claude Code session with **Remote Control** on. Lumi then
opens the Claude app on your phone, where the session shows up as **"Lumi · <task>"** so you can follow and steer it.

It only works while **Tailscale is connected on both the phone and the PC**. Nothing is exposed to the internet.

## What you need

- Tailscale on the PC and the phone, same account, with **MagicDNS** and **HTTPS certificates** enabled
  (admin console → DNS). `tailscale status --json` shows `CertDomains` when they are.
- Claude Code on the PC (`claude` on PATH), signed in with a plan that has Remote Control.
- Python 3 on the PC (standard library only).

## On the PC

```powershell
python tools\claude-bridge\lumi_claude_bridge.py --dir C:\path\to\your\project
```

It prints:

```
Address (Lumi -> Settings): https://laptop.your-tailnet.ts.net:8443
Token:                      <random token>
Only accepts:               you@example.com
```

Keep the window open while you want Lumi to be able to start sessions; Ctrl+C stops it and removes the
`tailscale serve` entry. `--new-token` replaces the token, `--https-port` picks 443/8443/10000.
The token lives in `%USERPROFILE%\.lumi-claude-bridge.json`.

## On the phone

Lumi → Settings → Integrations → **Claude on your PC**: turn it on, paste the address and the token, **Test
connection**.

## Security

- The bridge listens on `127.0.0.1` only; `tailscale serve` publishes it over HTTPS **inside your tailnet**.
- Every request must come from **your own Tailscale login** (the `Tailscale-User-Login` header set by
  `tailscale serve`; devices shared into your tailnet by other people are rejected) **and** carry the token.
- Claude is started with an argument list, never through a shell, and in its normal permission mode: it asks before
  editing or running anything, and you approve from the Claude app. At most 3 sessions per minute.
- The token is not included in Lumi's backups.
