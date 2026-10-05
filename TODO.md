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

## Lumi Hub (#3) follow-ups
- [ ] Claude Code Channels (`claude/channel`) to push into a running interactive session
- [ ] Files in `lumi_send`; Codex / Gemini CLI as wake-per-message agents (`Agents.command_for`)
- [ ] Push to Lumi itself while it is closed (UnifiedPush); today only an optional ntfy ping to the ntfy app

## Orbit (#4) follow-ups
- [ ] API agents (Gemini, Anthropic, OpenAI, Ollama) and Codex on the PC: new `AgentBackendKind` + `AgentBackend` (#2)
- [ ] Agents reading the user's tasks (`agents.can_read_tasks` exists, not used yet); images/files inline
- [ ] Rename an Orbit from the thread (the store supports it; no UI yet)

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
