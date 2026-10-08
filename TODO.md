# TODO — Lumi

Pending work. Finished items are removed; their history is in `MEMORY.md` and git.

## 1.0.0 (first public release)
- [x] Package renamed to `io.github.salex27.lumi` (Gemini removed from names and docs)
- [x] Backup: export / import everything (Settings → Backup)
- [x] All code and comments in English; UI in English (default) and Spanish; per-app language picker
- [x] Lumi understands English (dates, commands, follow-ups, weather, device actions)
- [x] Conversation context (follow-ups like "move it to Friday", "and tomorrow?")
- [x] Create-task sheet asks before discarding real changes
- [x] Batch of Spanish + English phrases through the real Gemma (`tools/gemma_batch.sh`) and interpretation timing
- [x] README (en + es), LICENSE, THIRD_PARTY_NOTICES, screenshots, demo script
- [x] versionCode 1 / versionName 1.0.0, build, APK to Drive, tag `v1.0.0`
- [x] Published: https://github.com/27Salex/lumi (release v1.0.0 with the APK)

## 1.1.7 security fixes: real-device checks
- [ ] S25: a reminder notification action (Text/Call), morning "Listen", widget/tile/wake word still open the assistant; Share text to Lumi asks before acting; locked phone: "read my messages" asks to unlock, timer works
- [ ] Update from v1.1.6 (debug build) to the release build with the in-app updater; My PC left while unlocking locks at once

## 1.1.2 pending real-device checks
- [ ] Chats tab, Settings groups, Servers (test per service), on-device search, model picker + streaming against the new hub, My PC on a weak link (real device)
- [ ] Keyboard pushes the Home SmartBar, assistant pill and Orbit input up; New chat creates separate threads; SearXNG answers over the tailnet

## 1.1.3 My PC (view only) pending real-device checks
- [ ] On the S25: `lumi_hub.py enable-pc-view`, restart `lumi`, re-link, My PC: unlock prompt, monitors, pinch/pan, fit, full screen/landscape, Lock now, locks when leaving the app
- [ ] Bind the unlock to a Keystore key that needs the biometric (today the biometric only gates the request); show the cursor in frames

## Alarm sets, bedtime and phone control: real-device checks
- [ ] S25: alarm sets in Samsung Clock, delete from Lumi, bedtime notification and boot, brightness/volume/media/share location

## Lumi Hub (#3) follow-ups
- [ ] Claude Code Channels (`claude/channel`) to push into a running interactive session
- [ ] Files in `lumi_send`; Codex / Gemini CLI as wake-per-message agents (`Agents.command_for`)
- [ ] Push to Lumi itself while it is closed (UnifiedPush); today only an optional ntfy ping to the ntfy app

## Orbit (#4) follow-ups
- [ ] API agents (Gemini, Anthropic, OpenAI, Ollama) and Codex on the PC: new `AgentBackendKind` + `AgentBackend` (#2)
- [ ] Agents reading the user's tasks (`agents.can_read_tasks` exists, not used yet); images/files inline
- [x] Rename an Orbit: long-press in the Chats screen
- [ ] Leader (#5): cost hints when an API agent would be used; more autonomy levels (act if pre-approved)

## Intent routing (#1) follow-ups
- [ ] Run `tools/intent_eval.sh` with Gemma on the S25 and with the cloud engine; grow the set from real logcat
- [ ] Show Claude's answer inside the assistant chat (today it lands in Orbit); remember "task or Claude?" preferences

## Routing (#12, #13) follow-ups

- Real-device check: plain "best high-speed trains to Madrid tomorrow" in an Orbit with Claude, "barcelona" after Claude asks, "pues busca un sitio" after a food question, web search on and off. Typo tolerance for places requests is done (`NearbyIntent`); other intents still need exact words. Real-device check of "donde pudo comer barato" and location permission flow.

## Web search (#7) follow-ups
- [ ] Fetch the page of the top result when snippets are too thin; news-specific backend
- [ ] Spoken answers: keep them even shorter than the chat text

## Selectable brain (#2) follow-ups
- [ ] Try real Anthropic / OpenAI / OpenRouter keys (only mocked so far) and measure voice latency per engine
- [ ] Orbit agents on these APIs (`AgentBackendKind` + an `AgentBackend` that reuses `ApiEngines`)

## Chats (#10) and in-app update (#11) follow-ups
- [ ] Swipe actions on chat rows; search inside message bodies (today title + last message)
- [ ] Try the update with a real GitHub release (v1.2.0 with an APK asset) on the S25, including "Install unknown apps" and the same-key rule
- [ ] Proper release keystore so updates survive a different build machine

## To test on the Galaxy S25
- [ ] Lumi Hub through real Tailscale (`tailscale serve`, owner check) and notifications while using other apps
- [ ] Now Bar: One UI promoting the notification (Android 16 + One UI 8)
- [ ] Place reminders with "Allow all the time" location (arriving at / leaving home)
- [ ] Spoken replies in English and Spanish (voice per reply)
- [ ] "Oye Lumi" with the real microphone (and battery with "screen on only")
- [ ] Gemma on GPU
- [ ] Replying from a reminder notification
- [ ] Linking to real Google Calendar meetings
- [ ] Backup: export on 3.7.5, import on 1.0.0

## Ideas
- [ ] Break a task into steps (subtasks with Gemma)
- [ ] Focus mode (Pomodoro tied to a task)
- [ ] Tasks from screenshots (on-device OCR)
- [ ] Sunday weekly review
- [ ] Habits and streaks
- [ ] Capture from Galaxy Watch
- [ ] Automatic backup to Google Drive
- [ ] Time blocking: suggest free calendar slots for each task
