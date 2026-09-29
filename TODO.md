# TODO — Lumi

Pending work. Finished items are removed; their history is in `MEMORY.md` and git.

## 1.0.0 (first public release)
- [x] Package renamed to `io.github.salex27.lumi` (Gemini removed from names and docs)
- [x] Backup: export / import everything (Settings → Backup)
- [x] All code and comments in English; UI in English (default) and Spanish; per-app language picker
- [x] Lumi understands English (dates, commands, follow-ups, weather, device actions)
- [x] Conversation context (follow-ups like "move it to Friday", "and tomorrow?")
- [x] Create-task sheet asks before discarding real changes
- [ ] Batch of Spanish + English phrases through the real Gemma (`tools/gemma_batch.sh`) and interpretation timing
- [ ] README (en + es), LICENSE, THIRD_PARTY_NOTICES, screenshots, demo script
- [ ] versionCode 1 / versionName 1.0.0, build, APK to Drive, tag `v1.0.0`
- [ ] Publish on GitHub (ask the user first)

## To test on the Galaxy S25
- [ ] Now Bar: One UI promoting the notification (Android 16 + One UI 8)
- [ ] Place reminders with "Allow all the time" location (arriving at / leaving home)
- [ ] Spoken replies in English and Spanish (voice per reply)
- [ ] "Oye Lumi" with the real microphone (and battery with "screen on only")
- [ ] Gemma on GPU
- [ ] Replying from a reminder notification
- [ ] Linking to real Google Calendar meetings
- [ ] Backup: export on 3.7.5, import on 1.0.0

## Ideas
- [ ] "Oye Lumi" detector trained on English voices ("Hey Lumi")
- [ ] Break a task into steps (subtasks with Gemma)
- [ ] Focus mode (Pomodoro tied to a task)
- [ ] Tasks from screenshots (on-device OCR)
- [ ] Sunday weekly review
- [ ] Habits and streaks
- [ ] Capture from Galaxy Watch
- [ ] Automatic backup to Google Drive
- [ ] Time blocking: suggest free calendar slots for each task
