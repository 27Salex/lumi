# MEMORY.md — Decision log

Design decisions and known issues. Add new entries at the top, dated, with **what** was decided and **why**. General
technical rules live in `AGENTS.md`; pending work in `TODO.md`. (Entries before 1.0 were written in Spanish and
translated when the project went public; version numbers before 1.0.0 refer to the private builds 1.x–3.7.5.)

---

## 2026-10-05 — Selectable brain: Anthropic, OpenAI and OpenAI-compatible engines (issue #2)

- **Settings → Lumi's brain** picks the engine that leads; `BrainChain.order` puts it first and keeps the default
  order behind it (Nano → Gemma → Gemini cloud → Anthropic → OpenAI → compatible), rules always last. The
  orchestrator now takes a chain *function*, so a change applies at once (a fixed-list constructor remains for tests).
- **Engines** (`ApiEngines.kt`, raw HttpURLConnection like `CloudGeminiEngine`, no SDK dependency on purpose):
  `AnthropicEngine` (Messages API, `x-api-key` + `anthropic-version: 2023-06-01`; interpreting with
  `claude-haiku-4-5` and replies with `claude-opus-5-5` by default, both editable; `LlmEngine.completeFor(Purpose)`
  lets an engine pick a model per call). Opus 5.5 gets no sampling params (they 400), extra `max_tokens` room for
  its always-on thinking, `effort: low` for Lumi's short calls and server-side refusal fallbacks (`fallbacks:
  "default"` + beta `server-side-fallback-2026-07-01`); a `refusal` stop reason returns null → next engine.
  `OpenAiEngine` covers the OpenAI API (`max_completion_tokens`, no temperature) and any OpenAI-compatible server
  (`max_tokens` + temperature, `<think>` blocks stripped, 120 s read timeout for laptops). Timeouts: 45 s API,
  130 s compatible.
- **Keys** live in prefs `brain` (not in the backup), AES-GCM encrypted with a non-exportable Android Keystore key;
  after a restore they must be typed again. A Claude Pro/Max subscription can't be the brain (stated in the UI);
  Claude Code stays reachable as an agent through Lumi Hub. Plain HTTP base URLs only for the emulator/localhost: a
  LAN Ollama should be published over HTTPS (tailscale serve).
- Each provider has "Test connection"; the picker shows a speed hint per engine (voice latency).
- Verified on the emulator: a mock OpenAI-compatible server on the PC (10.0.2.2) led the chain (interpret 106 ms, reply
  from the mock); a fake Anthropic key was stored encrypted, survived a restart, the real API answered 401 and Lumi fell
  back to Gemma. Not verified: real Anthropic/OpenAI calls (no keys yet).

## 2026-10-05 — General questions with web search, key-less by default (issue #7)

- **Opt-in** (Settings → Web search, off by default; prefs `web_search`, not in the backup because of the Brave key).
  Backends: **Wikipedia API** (default, no key, general knowledge), **SearXNG** (no key, user's trusted instance),
  **Brave Search** (user's key), **through Lumi Hub** (`POST /search`: `claude -p --output-format json --allowedTools
  WebSearch`, prompt via stdin, one at a time, 140 s cap; uses the user's Claude plan; ~14 s in a test).
  DuckDuckGo scraping was rejected (no official API, fragile).
- **When:** in the ASK path, fresh-data questions (`needsFreshData`: years, news, prices, results…) search first;
  other questions search only when the model can't answer (NO_LO_SE or no LLM). The old "search Google" hand-off
  remains the last resort. Only the query leaves the phone.
- **Answer:** top 3 cleaned results (http(s) only, tags/bidi stripped, 500-char snippets) → the active LLM with
  `WebAnswers.SYSTEM` (results are data, not instructions; cite [n]; NO_LO_SE if not answered) → without an LLM, the
  best snippet with its citation. New result `AIProcessingResult.WebAnswer(reply, sources)`; the chat shows numbered
  source links (stored in the message payload, so resumed chats keep them); the voice reply drops "[n]". The thinking
  line says "Searching the web…" while a search runs.
- Verified: emulator + Wikipedia: "who won the 2025 Tour de France?" → Gemma answered from 3 results with sources;
  hub search with real Claude Code returned 3 kotlinlang.org hits.

## 2026-10-05 — Top-level intent routing: task / agent / question / opinion, ask when unsure (issue #1)

- **`IntentRouter` (data/ai, pure) runs first** in `processNaturalLanguageCommand` (after routines, before the
  multi-command split) and only decides unambiguous cases: AGENT ("… con Claude" anywhere at the end, «dile /
  pídele / pásale a Claude…», "ask/tell/have Claude to…", "Claude, …", «que lo haga Claude», a Claude session),
  OPINION («qué opinas», «crees que», "what do you think", "is it worth"… → answered via the ASK path, never a task)
  and UNSURE (a wish to build/write/create with no date → "Should I add it as a task or send it to Claude?" chips).
  Everything else goes through the old pipeline. "… con Claude" needs ≥ 3 words before it («confío en Claude» isn't
  a request).
- `ClaudeIntent` (mentioned in the issue) only existed in an uncommitted checkout; this replaces it.
- **AGENT hands off to Orbit:** the assistant sends the request to the 1:1 "Chat with Claude" Orbit through Lumi Hub
  and says the answer will appear in Orbit (Claude's reply is not shown inside the assistant chat yet). Without the
  Hub it says how to set it up.
- Forced routes (`route` parameter): picking "task" builds a CREATE even if the LLM thought otherwise, with the wish
  prefix stripped from the title ("Programar una nueva web").
- **Evaluation set** `app/src/test/resources/intent_eval.tsv` (101 sentences, es + en, 6 labels): the JVM test
  requires the rules to catch every AGENT/OPINION/UNSURE sentence and to leave every other one alone (all pass).
  `tools/intent_eval.sh` scores a real engine on a device from the `route=` / `final=` log lines (not run in full: ~1 h
  with Gemma on the emulator CPU). Two opinion few-shots were added to the interpret prompt.
- Verified on the emulator: «Me gustaría programar una nueva web» → chips → "task" → Gemma CREATE "Programar una
  nueva web"; «pregúntale a Claude cuánto es 2 más 2» → Orbit → real Claude answered "4".

## 2026-10-05 — Orbit: Lumi as team leader (issue #5)

- **Group message without a mention → `OrbitLeader.decide`** (pure, tested): the user's past choices (word-overlap
  with stored `RoutingExample`s, newest first; "Lumi" is a valid learned choice) → an agent's purpose → code/repo work
  to Claude Code → big multi-step jobs (delegation) to Claude Code → only then Lumi's brain picks a name (short
  prompt, ≥ 4 words, tie breaker only) → otherwise Lumi answers. Cheapest first: Lumi before the PC.
- **Always visible, proposed by default:** "Shall I pass this to Claude? It's code work on your PC." with chips (the
  pick, the other members, Lumi). Per-Orbit "Lumi routes on its own" (header menu) hands off directly and shows
  "Redirect:" chips. Autonomy levels beyond propose/auto (act if pre-approved...) are not built.
- **Learning is explicit and inspectable:** only taps (accept / redirect / pick Lumi) are stored, in prefs
  `orbit_routing` (in the backup), listed under "What Lumi learned" in Orbit with a delete button per row. The
  automatic flag is keyed by the Orbit's createdAt so it survives a backup import.
- Not done: cost display for API agents (no API agents until #2); the #1 intent router is not used yet (#5 was asked
  before #1); `OrbitLeader.isCode/isComplex` are candidates to merge into it.
- Verified on the emulator: "Fix the failing gradle build please" in a 2-agent Orbit → proposal → tap Claude → real
  Claude Code answered; the choice appeared in "What Lumi learned".

## 2026-10-05 — Orbit v1: chats with AI agents, "Chat with Claude" (issue #4)

- **Threads reuse the chat tables** (#8): an Orbit is a `chat_sessions` row of kind ORBIT; Room v9 adds `agents`
  (name, backend, colour key, face key, purpose, config JSON, never secrets) and `orbit_members`. Agent inboxes from
  Lumi Hub (kind HUB) are listed in the same screen.
- **Backends:** `AgentBackend` is the extension point. v1 = `LUMI` (Lumi's brain via `AssistantOrchestrator.ask`,
  conversation only: tasks and phone actions stay in the assistant) and `CLAUDE_PC` (wake-per-message through the
  Hub; thread id `orbit-<session>-<agent>`, so each agent keeps one Claude session per Orbit). API backends wait for #2.
- **Routing v1:** `@Name` (case/accent-insensitive, longest name wins); a one-agent Orbit sends everything to its agent
  (the issue's "plain 1:1 chat with my Claude", created in one tap from "Chat with Claude"); a group without a
  mention → Lumi answers. Claude gets only what happened since its last reply (it has its own session); Lumi's brain
  gets the newest lines within a char budget.
- **Streaming:** a placeholder message per turn; Hub `reply` events update the row found by its turn id (payload),
  under one Mutex because the POST /chat answer and the first events race. "Second opinion" (long press) re-asks the
  previous user question to another member.
- Faces: four simple shapes with two eyes (`AgentFace`, Canvas); six palette colours in the theme. A Lumi-brain agent
  defaults to "Nova" so it isn't confused with Lumi itself.
- Verified on the emulator with a real hub + real Claude Code: 1:1 chat answered "pong", the next message resumed the
  same session; a group Orbit: Lumi (Gemma on-device) answered, and a second opinion from Claude streamed in.
  Gotcha: running the hub with a fake HOME hides Claude's login ("Not logged in"); use `--config` instead.

## 2026-10-05 — Lumi Hub: MCP server + relay for PC agents (issue #3)

- **Built fresh under `tools/lumi-hub/`** (stdlib Python, like the old bridge): the earlier `tools/claude-bridge`
  (Remote Control launcher) only existed uncommitted in another checkout and was not brought in. Same security model:
  127.0.0.1 + `tailscale serve`, phone = owner's `Tailscale-User-Login` AND phone token; MCP clients get one token each
  (their name is the message source). `--dev-no-tailscale` exists only for emulator tests (debug builds allow
  cleartext to 10.0.2.2 via a debug-only network security config; release is HTTPS only).
- **MCP over streamable HTTP with plain JSON responses** (no SSE on /mcp needed for 4 tools) + a stdio proxy mode.
  `lumi_ask` blocks inside the tool call on a Condition until the phone answers (default 10 min, max 1 h); open
  questions are re-sent to a reconnecting phone. Event ids start at the hub's start time in ms so a restarted hub
  never reuses ids the phone already saw.
- **Wake per message:** `claude -p --output-format stream-json --verbose [--resume id]`, prompt via **stdin** (never
  argv), thread → session id persisted in `~/.lumi-hub.json`; one turn per thread, two at once max, 15 min cap.
  Format verified against Claude Code 2.1.289 (init → assistant text blocks → result with session_id).
- **Phone:** `HubConnection` keeps the SSE stream open only while a Lumi screen is visible (Application activity
  counter, 5 s grace), so the always-on services are untouched. `HubInbox` stores events in `chat_sessions` kind HUB
  (one per source) with a JSON payload (state open/answered/added…) and posts notifications on channel `lumi_hub`
  with Yes/No (≤3 options) or a typed reply, and Add task / Dismiss. Decisions run under one Mutex so a chat tap and a
  notification tap (or the hub's own "closed" echo) can't race. Agent text is never interpreted; tasks are built by
  rules (`HubTaskProposal`, English then Spanish date parser).
- Verified on the emulator against a real hub: lumi_send / lumi_create_task / lumi_ask → notifications; tapping
  "Yes" unblocked the agent's call ("The user answered: Yes"); "Add task" created "Review PR 12" for tomorrow 17:00.

## 2026-10-05 — Chat sessions (issue #8)

- **Room v8**: `chat_sessions` + `chat_messages` (migration 7→8, SQL in `ChatSchema`, checked against Room's generated
  schema). Kinds ASSISTANT / ORBIT / HUB so Orbit threads and Hub messages reuse the same tables; messages carry
  `action`, `task_ids` (follow-ups after resuming), `agent_id` and a JSON `payload`.
- **One store for the pill and the app** (`ChatStore`): writes go through a single queue in the app scope so they keep
  their order and finish even when the pill closes right after a phone action. The session on screen is remembered
  (`active_session`); after 3 h idle (`SessionPolicy.IDLE_GAP_MILLIS`) the next opening starts a new chat (the old one
  stays in the list). Auto-title from the first user message; rename / delete / new chat from the chat header.
- **Context for Gemma** (`ChatContextBuilder`, pure): rolling summary + the newest turns within ~360 tokens. When 3+
  turns overflow, `ChatMemory` folds them into the summary with the active LLM (rules fallback), after the reply, never
  in front of it. `ConversationContext.fold` removes by time, not by count (a turn recorded meanwhile survives).
  The stale last task (10 min TTL) is no longer offered for "it", but the turns stay in the note.
- Backup format 2 adds `chats` (task ids not exported: tasks get new ids on import; dedupe by kind + createdAt).
- Fixed on the way: the "What should I do now?" / "How am I doing?" chips in the chat added the user bubble but never
  ran the request (`quick` didn't call `run`).
## 2026-10-05 — Voice Match as the media gate, per-phrase training, adaptive print (issue #6)

- **Why:** the 0.80 × 2 media bump kept only 26 % of "Oye Lumi" / 50 % of "Hey Lumi" (Bluetooth 0.85 × 3: 7 % / 24 %),
  and retraining didn't help (entry below). The Vosk print already ran on every detection when a voice is trained.
- **Measured** (scratch `vm_eval.py`: Vosk small-es + spk-0.4 on 10 multilingual edge-tts voices × 14 "Hey Lumi" + 14
  "Oye Lumi", 2 s wake windows like the service's ring buffer, TV episodes / MUSAN music mixed in). Against the user's
  phrase print: own voice in quiet 0.70 average (5th pct 0.49); with TV at +10 dB 62 % ≥ 0.37 (54 % ≥ 0.40); TV at
  +5 dB 26 %; **TV audio alone max 0.37, 1 of 195 windows ≥ 0.35**; another TTS voice 0.29 average, 16 % ≥ 0.42 (the
  earlier "another person 0.03" was optimistic; TTS voices may be closer to each other than real people). On-device
  self-test (emulator) gave the same transcripts and prints as Python (parity).
- **Vosk gives no print for short clips:** it needs ~50 frames aligned to words, and a short "Hey Lumi" often has
  fewer (no print for ~40 % of wake windows, even in quiet). The print is a mean over frames, so `VoicePrinter`
  decodes the clip again repeated twice when none came out (only then): user's wakes without a print 39 % → 9 % in
  quiet, 59 % → 24 % with TV at +10 dB; TV-alone and other-voice similarities stayed in the same range. Training allows 3 repeats (not time-critical).
  A closed grammar made it worse (more empty results).
- **Policy (`WakeGate`):** with a trained voice + media on speaker/Bluetooth: the user's own detector bar, 1 window,
  Voice Match REQUIRED; a match skips the confirmation; the similarity bar is 0.05 lower (voice mixed with the TV)
  but never below 0.35 during media (so on Relaxed the media bar is stricter than in quiet). No print during media →
  only a score ≥ 0.80 goes on, and it is confirmed. Bluetooth route without playback + voice: voice required, no relax,
  no-print falls back to the old idle policy. Without a trained voice: media → 0.50 (= Strict), 1 window, always
  confirm (measured v2 at 0.50: phone-speaker media 7/h, car 1/h, all confirmed instead of acted on).
- **Training:** 12 samples PER PHRASE ("Hey Lumi" / "Oye Lumi" trained separately; the app language's phrase listed
  first): close ×3, far ×2, soft ×2, louder ×2, music ×3 (skippable after 9). Own recorder + Vosk utterance split;
  each print via `VoicePrinter` on the last 2.5 s (same code as the wake check). LOO outliers < 0.12 dropped (≤ 2).
  Bar per phrase = LOO mean − 1.5 sd (≈ the fixed Normal bar 0.42 for a typical spread), shifted by the sensitivity's
  distance from Normal and kept within ±0.08 of the fixed bar. Fewer than 6 samples or a legacy print → fixed bar.
- **Phrase at wake time:** from the Vosk transcript of the same 2 s ("hoy/oye/uy…" → Oye; "el/eh/en/ay/ahí/hay/
  ilumin…/ayud…" → Hey). Never confused in 80 windows, but about half can't be told → best of both prints.
- **Storage:** prints only (never audio) in `files/voice_profile.txt` (plain text, version=2). The 1.0.x profile
  (one averaged print in SharedPreferences) is migrated on load and works for both phrases with the fixed bar until
  each phrase is retrained; Settings says "older training (3 samples)".
- **Adaptive print:** a wake with a print opens the assistant with a wake id. Voice command sent directly, or "yes" to
  the confirmation → confirmed: the print is learned for that phrase only if score ≥ 0.50 and similarity ≥ bar + 0.08
  (+0.15 during media); 10 per phrase, rolling; the centroid = mean of enrolled + learned. Closed/cancelled/silent
  within 30 s without a request → negative (20 rolling), used ONLY to raise the bar to just above the second highest
  dismissed similarity + 0.03, by ≤ 0.10 and never above the 20th percentile of the user's own similarities (so the
  user dismissing their own wake teaches nothing). "Open app" from the pill counts as neither. "Reset learned voice
  data" in Settings → My voice keeps the training.
- **Latency** (emulator x86_64, print only after a detection; nothing new per 80 ms chunk): print 200-900 ms, up to
  ~1.3 s when the doubled retry runs; model preload at service start 330-550 ms (it used to load on the first wake,
  1-2 s). A rejected sound isn't checked again for 1 s (the same TV line fires several windows).
- **Not verified yet (needs the S25):** real-voice similarities (TTS only so far), real microphone training, print
  latency on the phone, the echo-cancelled mix in a real car.

## 2026-10-04 — Wake word retrain with media / car / sound-alike hard negatives: tried, NOT shipped (issue #6)

- **Kept model v2** (`oye_lumi.bin` and the thresholds unchanged). No retrained model beat it clearly at the same
  true-wake recall: every candidate that cut media false wakes raised false wakes on real everyday audio.
- **New data (outside the repo, licence-clean):** MUSAN (CC BY 4.0: 16 h music, 10 h speech, 6 h noise);
  22 public-domain US TV episodes from archive.org (11 of *The Lucy Show*, which says "Lucy" constantly, plus Andy
  Griffith, Beverly Hillbillies, etc.); 8 LibriVox Spanish audiobooks (public domain); Blender's *Tears of Steel* mix
  (CC BY, test only). Plus 2,408 edge-tts clips: 48 English and 46 Spanish sound-alikes ("Hey Lucy", "Hey Louie",
  "Oye Luis", "Oye Lucía"…) and TV-style lines. These were played through simulated channels: the phone's own
  speaker (small-speaker EQ, clipping, sometimes an echo-canceller residual), car Bluetooth (cabin reverb + synthetic
  road/engine/wind noise), and a TV in the room. Train and test are split by episode, book, file and TTS voice; the
  test voices are the same ones v2 was measured on.
- **New scenario tests:** false wakes/h on held-out media through the phone speaker (2.4 h), car (2.0 h) and room
  (1.5 h); sound-alikes from held-out voices.
- **Baseline v2 per threshold.** Strict 0.50: phone 7.0/h, car 1.0/h, room 7.5/h. Normal 0.35: phone 14.8/h,
  car 5.1/h, room 13.0/h, real audio 0/h; "Hey Lucy"-type 47 %, "Oye Luis"-type 25 %. Relaxed 0.25: phone 25/h,
  car 11/h, room 22/h. So media on the speaker really does wake v2 about every 4 minutes on Normal.
- **WakeGate's raised bar is costly.** It does stop media: speaker 0.80 × 2 windows → 0.8/h; Bluetooth 0.85 × 3 →
  0/h. But only 26 % of held-out "Oye Lumi" and 50 % of "Hey Lumi" pass 0.80 × 2 (Bluetooth 0.85 × 3: 7 % / 24 %),
  even in clean conditions. Calling Lumi over media mostly won't work.
- **Retrain results** (`train_v3.py`; compared at the threshold that gives the SAME recall as v2, since retraining
  shifts the score scale):
  - Positives mixed with media/car audio made everything worse: their background could hold TV lines or
    sound-alikes, which contradicts the negatives (real audio 5–22/h at v2's Normal recall).
  - Fine-tuning v2 and a wider MLP (256/128) were also worse.
  - Dropping near-homophones ("Hey Lumina", "Hey Lou, me too", "Oye Lumen"…) from the negatives didn't help.
  - Best runs at Normal-equivalent recall: media false wakes about halved (phone 7–11/h, room 5–8/h, car 3–3.6/h),
    "Hey Lucy" 47 % → 39–44 %. But real audio went 0 → 0.19–1.1/h, and a control run with no new data
    landed in the same range, so run-to-run spread is as large as the data effect.
  - At Strict-equivalent recall, run v3c reached phone 3.3/h and room 1.4/h, but real audio 0.19/h.
- **Why not shipped:** none is clearly better without losing something, and real-audio false wakes matter most. Next
  step for training: real phone recordings (accepted/rejected wakes) as hard negatives, not more synthetic media.
  App-side options being discussed instead: Voice Match as the media gate in place of the 0.80 bump, and a Vosk
  transcript veto only on names that clearly aren't Lumi. The Spanish small model can't transcribe "lumi" or
  English, so it can't confirm a wake.
- Scripts and data stayed in the session scratchpad (`oww3/`: `dl_musan.py`, `dl_media.py`, `gen_v3.py`,
  `feat_v3.py`, `train_v3.py`, `evalset.py`, `compare.py`), not in the repo.

## 2026-10-04 — Wake word: fewer false wakes from media and car Bluetooth (issue #6)

- **Context-aware gate (`WakeGate`, pure + tested; `AudioEnvironment` reads the system state).** While another app
  plays media (`isMusicActive`, refreshed by an `AudioPlaybackCallback`, held 2 s after it stops because the detector
  looks at the last ~1.3 s) the detector needs ≥ 0.80 for 2 windows in a row on the speaker and ≥ 0.85 for 3 windows
  over Bluetooth (car); a Bluetooth route with nothing playing adds +0.10; headphones keep the bar; calls
  (`MODE_IN_CALL`/`IN_COMMUNICATION`) pause detection; a ringing phone counts as media. In silence nothing changes,
  so the measured true-wake rates per sensitivity still hold. Why not a full pause during media: the user may still
  want to call Lumi over music; a true "Hey Lumi" scored 0.95–0.99 in the self-test.
- **Confirmation as the gate:** after a wake with media playing or a borderline score (< threshold + 0.15) and no
  Voice Match, the first voice request is always confirmed ("Should I note it down?"), even if it looks like a
  command (a series line "remind me…" passes `CommandLikeness`). Voice Match passing skips it.
- **Echo cancellation:** `AcousticEchoCanceler` + `NoiseSuppressor` on the `AudioRecord` session, enabled only while
  media plays (the model was trained on unprocessed audio, so quiet-room capture stays identical). Source stays
  `VOICE_RECOGNITION`.
- **Diagnostics:** wakes log score/policy/media/route; a score the user's sensitivity would accept but the raised bar
  rejected shows "Ignored: other audio was playing" in Settings → My voice. Retraining with TV/music/car hard
  negatives was tried afterwards and not shipped (see the entry above).

## 2026-09-30 — 1.0.0 (same version): "Hey Lumi", better wake word, lock screen and a recognizer loop

User report after trying 1.0.0: English speakers won't say "Oye Lumi", detection still feels weak, and on the lock
screen Lumi appears when called but doesn't seem to listen. Fixed inside 1.0.0 (no version bump, user's request).

- **Wake word v2: one model for "Hey Lumi" + "Oye Lumi".** New English data with edge-tts (47 English voices from 14
  accents + multilingual + some Spanish voices saying "Hey Lumi"; 48 English sound-alike traps such as "Hey Lucy",
  "Hey Louie", "Hey Siri"; normal English speech) and **phone-mic augmentation** (band-pass 80–350 Hz / 3.4–7.6 kHz, soft
  clipping, far-field levels down to 3 %) on top of the old noise/echo/speed augmentation. Same MLP and file format, so
  the app code didn't change (parity test updated). Scripts in `tools/wakeword/`.
- **Results on unseen voices** (phrase inside 3 s with noise; 5.3 h of real audio for false wakes), at similar false
  wake rates: old model "Oye Lumi" 75 % / "Hey Lumi" 56 % / 0.37 false wakes per hour → v2 on Normal ≈ 76 % / 85 % /
  0.1 per hour. Weak spot: English sound-alike names (~25–35 % on Normal), filtered by Voice Match and the "Should I
  note it down?" confirmation. A variant trained harder against English traps (v3) had more false wakes on real audio
  and was dropped. New thresholds: Strict 0.50 / Normal 0.35 / Relaxed 0.25. On-device self-test (emulator): "Hey Lumi"
  0.95–0.99, "Oye Lumi" 0.999, English trap 0.10, normal sentence 0.02.
- **Lock screen:** the assistant Activity is translucent, and Android only lets an OPAQUE activity occlude the keyguard:
  Lumi was drawn over the lock screen but the keyguard kept focus (`mCurrentFocus=NotificationShade`), so it wasn't
  really in front and the recognizer got silence. Over the lock screen it now calls `setTranslucent(false)` on a dark
  background (verified: `mKeyguardOccluded=true`, focus on Lumi, microphone open), and listening starts after the
  window gets focus (max 1.5 s wait) plus a short delay so the wake word detector releases the mic.
- **Recognizer error loop (old bug, found on the emulator):** inside `SpeechRecognizer.apply { }` a bare
  `stopListening()` in the listener called the RECOGNIZER's method; after an error the system answered "stop without
  start" with ERROR_CLIENT → onError → stopListening… ≈30 errors per second until the pill closed (2,179 in 10 s).
  Fixed with `this@VoiceSpeechManager.stopListening()`; after a miss there is now exactly one error.
- **Speech recognition:** on Android 14+ Google's recognizer may auto-detect between the voice language and the other
  one (Spanish ↔ English); a haptic tick marks the moment the mic is really open (words before it are lost).
- The English UI says «Hey Lumi»; the Spanish one «Oye Lumi» (both mention the other phrase where it matters).

## 2026-09-30 — 1.0.0: Gemma batch in both languages (emulator, CPU)

20 phrases (`tools/gemma_phrases.txt`, 10 Spanish + 10 English) through the real Gemma 4 E2B:
- **Interpretation 6.5–8.5 s** with the shorter English prompt (≈10 s before); answers 15–30 s end to end on CPU.
- Decisions were right in both languages (tasks, place reminders, "done", questions answered, advice). Found and fixed:
  - **Translated titles:** "I have the dentist tomorrow at 5" → Gemma copied the Spanish example and said «Dentista».
    Now an English example sits next to the Spanish one, and `reconcile` rejects an LLM title with no word from the
    sentence (`comesFrom`) → the rules' title. English titles drop a leading "the".
  - **Unrequested priority:** Gemma adds `"priority":"MEDIUM"` to many tasks. The rules' priority wins; the LLM's only
    counts when the rules found none and it isn't MEDIUM.
  - **"I need to remember that…"** became a task: English "remember that / keep in mind / note that" now count as an
    explicit request (`asksToRemember`) and the English rules read it as REMEMBER.
  - **Shopping lists:** "add milk, bread and coffee to my shopping list" → "Buy milk / Buy bread / Buy coffee" (rules and
    LLM titles; Spanish «añadir X a la lista de la compra» → «Comprar X»).
  - "next month", "next year", "this/next weekend", "in N months" in `EnglishDateParser`.
  - Follow-ups tolerate fillers ("actually, move it to Friday", «mejor muévela al viernes»).
  - The contacts-permission note ("I need access to your contacts…") is no longer appended to the chat reply, since the
    permission is requested on tap anyway; it stays on the task's card.
- **The batch script** broke on apostrophes ("I'm…" never reached Lumi): phrases are now escaped with `printf %q`.
- **Accent bug:** the Spanish follow-up regex expected «muevela», so the correctly written «muévela» never matched.
  Fixed (`m[uú][eé]vela`), and the language detector knows more English words ("make", "wait", "urgent"…) so a
  sentence like "no wait, make it urgent" isn't read as Spanish because of the leading "no".
- **Database file renamed** `gemini_tasks_db` → `lumi.db` (safe: 1.0.0 is a fresh install under the new app id).

## 2026-09-29 — 1.0 prep: English codebase, bilingual app (en default + es)

- **All code and comments are now English** (user decision for the public GitHub release). UI text lives in
  `res/values/strings.xml` (English, default) and `res/values-es/strings.xml`; both are generated from one list so the
  keys never drift. Resource names can't be Java keywords (`import`, `export`, `package` broke the build → `action_import`…).
- **Two languages at runtime:** `ReplyLanguage.app` = the UI language (set from the configuration in the Application
  and in each Activity's `onCreate`); `ReplyLanguage.current` = the language of the conversation in progress (detected
  per sentence). Screens must use the app language (`uiLabel`, `uiLocale`, `lang = ReplyLanguage.app`), never the plain
  `label` getter, or an English chat turns a Spanish app's lists English. Chat replies (including the view model's local
  ones like "OK, I won't") follow the conversation language; choice answers accept English ("yes", "the second one").
- **English understanding:** `EnglishCommands` + `EnglishDateParser` mirror the Spanish rules; `RuleBasedEngine.parse`
  dispatches on the detected language. The LLM prompts are in English (shorter, ~1,300 tokens) and state the reply language.
- **Conversation context** (`ConversationContext`, last 4 turns + last task for 10 min): follow-ups in both languages
  ("move it to Friday", «ponle prioridad alta», "and tomorrow?") set `refersToLast` and are RULES_FIRST.
- **Per-app language:** `res/xml/locales_config.xml` + Settings → Appearance → Language (Android 13+, `LocaleManager`).
  The daily summary cache stores its language and is regenerated when the app language changes.
- **Voice:** `LumiSpeaker` picks the TTS voice per reply (English text is not read with a Spanish voice).
- **Default routines** are created in the app language (names and steps); triggers understand both languages.
- **Create-task sheet** now also asks "Discard changes?" when closing, but only if something was changed (user request);
  the old shortcut "new task with an empty title closes silently" was removed.
- **Place search attribution:** Photon/OpenStreetMap results show "© OpenStreetMap contributors" (ODbL requires it);
  Photon and the Geocoder are queried in the app language.
- **Gemma in the emulator with 6 GB** instead of 8: with 8 GB the host (16 GB) ran out of memory while idle.

## 2026-09-29 — Going public: package rename, versioning restart, license

- The user wants Lumi public on GitHub for work (portfolio), learning and "having the assistant I wanted"; not on the
  Play Store (no profit either way, and a Play release needs a real privacy/review process).
- **Package `io.github.salex27.lumi`** (GitHub user 27Salex; a package segment can't start with a digit). "Gemini" was
  removed from package, folder, class and doc names; Gemini Nano and the Gemini API are still named as supported engines.
  This supersedes the old rule "the applicationId never changes": 1.0.0 is a separate install, so data moves with the
  backup and Gemma must be downloaded again.
- **Versioning restarts at 1.0.0** (versionCode 1) for the new app id: "3.8" as a first public version would confuse.
- **License Apache-2.0**: permissive, patent grant, same license as most of the stack (AndroidX, LiteRT, Vosk).
  Third-party terms (Gemma Terms of Use, Open-Meteo CC BY 4.0 non-commercial, OSM ODbL, Inter OFL) in `THIRD_PARTY_NOTICES.md`.
- **Backup** (`BackupManager`, format 1): tasks + custom reminder offsets, memories and the prefs files
  (assistant settings, places, routines, contact aliases) in one JSON file. Excluded on purpose: the Gemini API key and
  phone-tied ids (calendar id and sync, Google Tasks account and ids). Import merges without deleting, then restarts the app.
  It is a permanent feature, not only for the 3.7.5 → 1.0.0 move (changing phones, reinstalling).

## 2026-09-29 — v3.7.4: Gemma tested for real (emulator) → the title got lost; dates; a GPU crash

For the first time the real Gemma 4 E2B (same .litertlm and LiteRT-LM as on the S25) was tested on the emulator:
- **Big bug:** Gemma put a new task's title in `newTitle` (the rename field, which came first in the schema) and left
  `targetTitle` empty → Lumi always used the rules' title ("Tengo dentista", "Me recuerdes llamar a mi madre",
  "Que se llama…"). Fix: `AssistantPrompts.movedTitle` moves newTitle → targetTitle (except for EDIT) and the schema lists
  targetTitle first and newTitle last ("EDIT only").
- **Dates:** Gemma gets weekdays wrong ("el viernes" → Sunday the 4th). In `reconcile` the rules' date wins; the LLM's
  only if the rules found none.
- Gemma's DECISIONS were good (ASK for «estoy cansadísimo» / «qué ceno con huevos», UPDATE_STATUS for «lo del informe
  ya lo he terminado»), better than the rules. So the "two-step router" was postponed: the problem was the plumbing, not
  the decision. Revisit with more phrases before redoing it.
- «(tengo que) acordarme de que X» → REMEMBER. General answers without a greeting.
- **A GPU crash that kills the app:** on the emulator LiteRT-LM's GPU backend uses WebGPU and dies with SIGSEGV (not
  catchable). `GemmaLocalEngine`: CPU only on emulators; on any phone a `gpu_trial_pending` flag is committed before
  trying the GPU and cleared after the first answer → if the app dies halfway, it uses the CPU next time instead of looping.
- **Testing Gemma on the emulator:** AVD `Medium_Phone` with `disk.dataPartition.size = 16G` (it froze with 2 GB of RAM).
  CPU: ~10 s per interpretation, 1–2 min per long answer. Logs: `adb logcat -s LumiInterpret` (raw LLM output, rules,
  final, timings, answers). Phrase batch: `tools/gemma_batch.sh < tools/gemma_phrases.txt`. The PC's RTX 4060 can't be
  used inside the emulator (graphics only); for hundreds of phrases Ollama on the PC would do, but it isn't the phone's runtime.

## 2026-09-29 — v3.7.3: questions get answered; memory only on request

«He dejado una natilla en la nevera abierta toda la noche, ¿me la podría comer?» → Gemma chose REMEMBER and it was saved
as a permanent memory, without an answer.
- `AssistantIntents.looksLikeQuestion`: questions without «¿?» (voice loses them): «me la puedo comer», «se puede»,
  «es malo/seguro…», «estará bueno», «qué pasa si», «cuánto dura»… → ASK. Requests to Lumi («¿puedes apuntar…?»,
  «¿me recuerdas…?») don't count.
- `reconcile`: the LLM's REMEMBER/FORGET only count if the user asks (`asksToRemember`: «recuerda que», «ten en cuenta»,
  «apunta que», «mi X es…»); otherwise a question → ASK, else → rules. An LLM CREATE over a clear question (rules = ASK) → ASK.
- Prompt: REMEMBER "ONLY if explicitly asked" + the custard example as ASK. Tests: `QuestionsTest` (with a fake LLM that
  returns REMEMBER, as Gemma did).

## 2026-09-29 — v3.7.2: «Que se llama…» in the title (Gemma) and replies saying "TODO"

«créame una tarea que se llama hacer X» → Gemma returned the title «Que se llama hacer X» and answered «Buenos días…
Está marcada como TODO».
- `TaskPhraseParser.cleanTitle` (pure, tested) is applied to EVERY title, whatever engine it comes from (rules,
  reconcile and `buildAndSave`): removes «(créame) una tarea», «que se llama/llame», «llamada», «titulada», «con el nombre
  de», «tarea:» (and English "a task called"). Before, only «que se llame» was handled, and only in the rules.
- Reply prompt: the status is phrased naturally (pending / in progress…), internal data (TODO, capitals, JSON) is
  forbidden, and no greeting in the middle of a conversation.
- **Lesson:** any title clean-up belongs at the common point (the repository), not only in the rules: with Gemma
  active, the title comes from the LLM.

## 2026-09-29 — v3.7.1: an «Avisar a X» task shows who it's linked to

The user tried «avisar a Roberto…» and thought it didn't work: they only saw a task «Avisa a…» with no link to the
contact (the button only existed when the place reminder fired, and «casa» wasn't even saved).
- `ActionPreview` resolves the contact WHEN CREATING (alias → literal number → address book) and the reply says so:
  "Then you'll have a button to send a WhatsApp to Roberto Pérez («Ya he llegado a casa»)…", or what's missing (no
  contacts permission, not found, several).
- Task editor: a `TaskActionCard` under "Place" with contact, number, message and "Try now".
- Infinitive titles: «Avisa a Roberto» → «Avisar a Roberto» (avisa/escribe/llama/manda/envía…).
- It still doesn't send on its own: WhatsApp doesn't allow it; the real option would be automatic SMS (SEND_SMS),
  pending the user wanting it.

## 2026-09-28 — v3.7.0: voice that doesn't cut off, several commands per sentence, «avisa a X al llegar», places by name

User complaints (with a screenshot): the mic cuts off on a pause; it doesn't take several things in a row ("it feels like
an Alexa"); «Una tarea llamada escribir a Roberto para cuando vuelva a casa» created a task with that whole title; they
wanted to let someone know on arrival; the task's place field only worked with exact street addresses.

- **Chained voice** (`VoiceSpeechManager`): Google's recognizer returns the result after ~1 s of silence and ignores the
  silence extras → each chunk is kept and listening restarts; it is sent after `voicePauseMs` without speech (Short 0.9 s
  / Normal 1.5 s / Long 2.5 s, Settings → Voice conversation). Stop = send what was said.
- **Continuous conversation:** after answering something said by voice (and finishing speaking), the pill listens again
  in "quiet" mode (if you say nothing it closes without an error). Not when Lumi goes to another app.
- **Several commands** (`CommandSplitter`, pure): only splits BEFORE an imperative command verb (closed list), so
  «comprar pan y leche» and «dile a Ana que compre pan y que me espere» aren't broken; «…y avísame cuando llegue a casa /
  y ponle prioridad alta» is glued back to the previous command. Each chunk goes through the normal chain (LLM included)
  and they are gathered into an `AIProcessingResult.Routine` (queued actions + tasks). If a chunk asks back ("Did you
  mean…?"), it stops there. Lists of infinitives still go to the brain dump.
- **Titles:** «(crea) una tarea llamada X» → X (`TASK_NAMED_REGEX`); place with «para cuando vuelva/regrese», «al volver».
  If the LLM returns an "uncleaned" title (with «tarea llamada», «cuando llegue», «recuérdame»…) the rules' title wins
  (`looksUncleaned`).
- **«Avisa a Roberto cuando llegue a casa»** = a task with a place reminder (not a message now: LATER/place in the text).
  `TaskActions` (pure) detects «llamar a X», «escribir/avisar a X (que …)» → the reminder carries a button ("Call", "Send
  to Roberto") that opens the pill with EXTRA_DEVICE, runs the action and marks the task done. «Avisar a X» with no text
  + place → «Ya he llegado a casa». **It doesn't send on its own:** WhatsApp has no API to send without a tap (wa.me
  leaves the text written); automatic SMS would need SEND_SMS → dropped for now.
- **Places by name** (`PlaceSearch`): Photon (OpenStreetMap, free, no key, with a User-Agent) biased to your location +
  the Geocoder for addresses. «Cuando llegue al Mercadona»: if it isn't one of your places (home, work…) the nearest one
  **within 60 km** is used; if Lumi doesn't know where you are it does NOT pick (on the emulator it picked a pharmacy in
  Italy) and asks to save the place. The reply states the chosen address.
- Tested on the emulator: the user's sentence + «pon una alarma a las 7» + «qué tiempo hace en Madrid» in one sentence
  (3 correct results), Mercadona without a location, the "Send" button (opens wa.me). Voice can't be tested on the
  emulator. Tests: `Lumi37Test`.

## 2026-09-28 — v3.6.0: Lumi the assistant (weather, questions, morning summary, messages, routines, lock screen)

User request: make Lumi feel like an assistant, not just a voice shortcut for tasks. They chose weather, general
questions, a morning summary, reading messages and routines (stressing an alarm based on the calendar/tasks), and asked
for it to work on the lock screen.

- **Weather with Open-Meteo** (`data/weather/WeatherService`): free, no API key (non-commercial use). Approximate
  location (lastLocation < 6 h, else getCurrentLocation 6 s) → the "home" place → last known. Cities through its free
  geocoder. 30-min in-memory cache + raw JSON in prefs (Home shows it offline). **Why not the AI:** the numbers come from
  Open-Meteo and the sentences from `WeatherAdvisor` (pure, tested); a small LLM would invent temperatures.
- **Weather warnings for outdoor tasks** (`WeatherAdvisor.taskWarnings`, keywords: run, bike, beach…): in the day brief
  and when creating the task, **only with an already downloaded forecast** (creating never waits for the network).
- **General questions** (ASK; RECALL without personal data falls here): 1) `QuickMath` without AI (small LLMs get sums
  wrong), 2) if it needs current data (`needsFreshData`) → online Gemini with the `google_search` tool (`askWeb`; without
  it if that fails), 3) the available LLM with `generalSystem`, 4) no LLM or a `NO_LO_SE` answer → a Google search
  (DeviceCommand.WebSearch). A question never creates a task.
- **Smart alarm** (`AlarmPlanner`, pure): the first thing of the day between 5:00 and 13:00 (meeting, timed task or
  starting work Mon–Fri) − getting ready (60) − travel (30, only with an address/place or for work), rounded to 5 min.
  «Mañana» at 2:00 means today. A question ("what time…?") → confirms first; a command → sets it (AlarmClock SKIP_UI).
  The evening check-in suggests it with a button that is an **Activity** PendingIntent (Android won't let a receiver
  open the clock).
- **Morning summary** (`MorningScheduler`, 8:00 by default, ±15 min): written by rules (`DayBriefComposer`), not the
  AI, so it arrives even if Gemma isn't loaded. "Listen" opens the pill with EXTRA_SPEAK (reads it aloud).
- **Reading messages** (`LumiNotificationListener`): only ACTIVE notifications (= unread), in memory, nothing on disk.
  MessagingStyle when present; otherwise title/text. Replies use the notification's own "Reply" action (like Android
  Auto), **always after confirming** ("Shall I send it?"); if the notification is gone → wa.me/SMS. «Dile a X que…» uses
  this path if X has an unread message with "Reply". Notification keys contain "|" → URL-encoded when serialized.
- **Routines** (`RoutinesStore` + `RoutineMatcher`): an exact phrase (ignoring accents/punctuation/"oye lumi") → steps
  that are normal sentences interpreted ONLY by the rules (fast and predictable); a step that would end up as CREATE is
  skipped with a note. Actions that stay in Lumi first (alarm, Do Not Disturb…), then those that open apps, the route
  last. Inside a routine no permission screens are opened (they're mentioned); `inRoutine` avoids repeating the alarm in
  the summary. Defaults: Good night, Good morning, Going home, Going to work (editable/restorable).
- **Do Not Disturb:** `setInterruptionFilter` with ACCESS_NOTIFICATION_POLICY (on Android 15+ it is Lumi's own mode).
- **Lock screen:** AssistantActivity with `setShowWhenLocked/setTurnScreenOn`; anything that opens another app (call,
  WhatsApp, maps, edit) asks to unlock with `requestDismissKeyguard` and continues once unlocked. Alarm, timer,
  flashlight, Do Not Disturb and message replies work without unlocking (`DeviceCommand.staysInLumi`).
- Tested on the emulator: real Madrid weather over the lock screen, «buenas noches» (7:30 alarm + Do Not Disturb +
  tomorrow's summary), new settings. The emulator has no location ("here" weather untested) nor real messages.
- Tests: `Lumi36Test` (phrases, weather, alarm, summary, messages, routines).

## 2026-09-28 — v3.5.0: "Oye Lumi" with openWakeWord + messages and calls fixed

- **Vosk out of detection:** its Spanish model has no "lumi" in the vocabulary (it never transcribes it). Now
  `OyeLumiDetector` = openWakeWord: melspectrogram.tflite + embedding_model.tflite (LiteRT 1.4.0, in assets) + our own
  MLP (1536→128→64→1, `WakeClassifier` in pure Kotlin, `assets/oww/oye_lumi.bin`). 80 ms chunks (1280 samples + 480 of
  context → 8 mel frames → 1 embedding); a window of 16 embeddings. Vosk stays only for the voice print (computed over
  the last 2 s on detection) and "Train my voice"; the detector needs no downloads.
- **Training** (scripts in the session scratchpad, `oww/`): 2,268 positives with edge-tts (45 es-* voices from 20
  countries + 12 multilingual, varied speeds and pitches), augmentation (speed, synthetic echo, colored noise, background
  conversation, SNR 3–25 dB), 480 trap phrases, 252 normal phrases, 347 h of real negatives (the first 3 GB of
  openWakeWord's ACAV100M over HTTP Range) and hard-negative mining on half the validation set. 5 voices held out.
  Iterations: v1 72 false/h (overfit to TTS voices) → v2 0 false/h but 40 % recall (negative weight 50) → v3 (weight 8 and
  2.3× positives) chosen. "Live" evaluation (phrase within 3 s, 13 windows).
- **Thresholds:** Strict 0.6 (66 % recall on new voices / 0 false/h / 7.5 % traps), Normal 0.4 (75 % / 0.4 / 13 %),
  Relaxed 0.3 (81 % / 0.75 / 19 %); patience 1 (requiring several windows lost more hits than false triggers it removed).
  Traps are further filtered by the voice print and the "Should I note it down?" confirmation.
- **Parity verified:** `WakeClassifierTest` (Kotlin = PyTorch, 1e-4) and on the emulator with `WakeSelfTestReceiver` (adb,
  protected with DUMP): the same scores as the live Python pipeline (0.8457 / 0.9970 / 0.0198).
- **Personal templates dropped:** cosine similarity of embeddings doesn't separate "Oye Lumi" from trap phrases in the
  same voice (0.84 vs 0.80).
- **WhatsApp ended up as a task:** the rule only accepted one exact form. Now: intent detection (channel + verb, also the
  infinitive «enviar WhatsApp a…»), several patterns, and the LLM (`assistant.ask`) extracts the recipient and text and
  turns it into direct speech («dile que si viene a cenar» → «¿Vienes a cenar?»). If something is missing, `AskFollowUp`
  ("What should I tell Víctor?"). «Recuérdame enviar…» or a date in the command → still a task.
- **Calling only dialed:** CALL_PHONE was requested with contacts and, if missing, the dialer opened. Now it is requested
  right when calling (once); if denied, the dialer.

## 2026-09-28 — v3.4.0: agent mode (edit, on-demand memory, phone actions, contacts)

- **Finding the right task** (`TaskMatcher`, pure): keywords without accents/plurals/fillers; score = 0.6·title coverage +
  0.4·precision. Confident if ≥ 0.7 and it stands out (≥ 0.15 over the 2nd); otherwise "Did you mean…?" with up to 3
  candidates (`AIProcessingResult.Choose`, re-run with `targetId`). Answered by tapping or saying «sí / la segunda / no /
  la del cumpleaños» (`parseChoiceAnswer`). «Ya terminé X» without a match NO LONGER creates a task.
- **EDIT:** rename, date, area, priority, note, place; «edita X» without changes opens the editor (MainActivity with
  `EXTRA_EDIT_TASK_ID`). Renaming «de X a Y» is ambiguous with «a» → `RenameSplitter` picks the split that matches a task.
- **On-demand memory** (`memories` table, Room v7, BM25-like `MemoryRetriever`): never loaded whole; the LLM only gets
  the 2–3 memories related to the sentence. «Recuerda que…» with an obligation or a date = a task, not a memory.
  Questions → RECALL (memory + tasks), never a new task. Settings → Memory to view/forget/add.
- **Rules first** for clear commands (DEVICE, REMEMBER, FORGET, NAVIGATE, EDIT): no waiting for Gemma/Gemini; the rest
  goes to the LLM with the extended schema and the app resolves the task with `TaskMatcher` (the LLM doesn't need the
  exact title).
- **Phone actions** (pure `DeviceCommandParser` + `DeviceActions`): open an app, alarm, timer, call, prefilled
  WhatsApp/SMS (wa.me), music (MEDIA_PLAY_FROM_SEARCH), search, wifi/Bluetooth/volume, flashlight. «Llamar al banco»
  (infinitive) and «llama al banco mañana» are still tasks.
- **Contacts:** accent-insensitive search that ignores quick-access prefixes («AA Mamá») and uses the nickname; several →
  "Which one?" and learned as an alias (`ContactAliases`); Settings → Quick contacts. With CALL_PHONE it calls directly.
- **Bug fixed:** effects that cleared their own state and then waited (`delay`) got cancelled (the effect restarts when
  its key changes) → «llévame a…» (3.2/3.3) never opened the map and «edita» didn't open the editor. The wait now runs in
  `lifecycleScope`. Rule: in a `LaunchedEffect(key)`, don't suspend after changing that key.
- **"Oye Lumi" with Vosk doesn't work:** the Spanish model has no "lumi" in its vocabulary, so it never transcribes it.
  Pending decision: a real wake word detector (openWakeWord or Porcupine). (Solved in 3.5.0.)

## 2026-09-28 — v3.3.0: more tolerant "Oye Lumi", places with an address, free-gap card

- **"Oye Lumi" still didn't recognize the user, even on "Relaxed".** Causes: (1) "Relaxed" only lowered the voice
  threshold; the phrase filter required exact «oye/hola/ey/hey» and the small model often transcribes «hoy lumi», «o
  lumi»; (2) without a voice print (very short phrases) it was silently dropped; (3) training only accepted samples that
  already started with «oye». Changes (`WakePhrases.matches`): the sensitivity also controls the phrase (more calling
  words on Normal/Relaxed, whole-phrase tolerance 0 / ~1 / 1–2, up to 4 words on Relaxed); training stores the PHRASE as
  the model transcribes it in your voice (path B) → you need to retrain; on Relaxed it accepts without a voice print.
  Diagnostics in Settings → My voice: last phrase heard, voice % and reason. «luni» was rejected as a variant (it
  triggered with «hola luna llena»). The 20 conversation phrases still don't trigger.
- **Free-gap card:** after "Start" the task stays on the card in "In progress" mode (Done / Not now); "Not now" hides it
  until the next gap. Readable minutes ("4 h 56 min").
- **Places in tasks:** `PlaceTrigger` accepts an ad hoc address with coordinates (found with the system Geocoder, free,
  biased to ~50 km around your places) and a "No reminder" mode (just a reference + "Directions"). The editor suggests
  saved places, searches "Other address…" and allows "Save as a place". Settings → Places: "Add by searching the address"
  (without being there). Format compatible with v3.1 («casa|ARRIVE»).

## 2026-09-28 — v3.2.0: Android 16 chip, proactive Lumi, routes and the "Gaze" logo

- **Now Bar not available:** on the S25 the user only saw the notification. On One UI the Now Bar / lock screen pill is
  for Samsung apps and partners; it can't be forced. Replaced by the **Android 16 status bar chip** (API 36):
  `Notification.ProgressStyle` + a promotion request + a chronometer (like Maps). Android 16 lets users turn the chip off
  per app → `NotificationManager.canPostPromotedNotifications()`, and Settings shows the state (`LiveUpdateManager.ChipStatus`)
  with a link to `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`. If the system doesn't set `FLAG_PROMOTED_ONGOING`, the
  user is told ("your version doesn't turn it into a chip").
- **Free gaps** (`FreeTimeFinder`, pure): a gap ≥ 25 min between meetings/timed tasks (8–22 h) + an untimed task to get
  ahead on. A card on Home (Start / Another) and in the "what should I do now?" answer.
- **Evening check-in** (pure `CheckInComposer` + `CheckInScheduler`): 20:00 by default (17–23), only if something
  happened today; Move to tomorrow / Reply (a normal command) / Talk to Lumi.
- **"Talk"** on the chip and the check-in → the pill, listening (`AssistantActivity.talkPendingIntent`).
- **Routes in the chosen app** (`MapsLauncher`): Google Maps and Waze open navigation directly; the rest `geo:`; "Ask" =
  chooser. `NAVIGATE` command («llévame a casa», «¿cómo llego a la reunión?»): saved place → coordinates; «la reunión» →
  next meeting with an address (`AgendaEvent.location`); otherwise the text as is.
- **TTS bug:** the `<queries>` entry for `android.intent.action.TTS_SERVICE` was missing; without it, on Android 11+
  TextToSpeech may not find the engine and Lumi didn't speak.
- **"Gaze" logo** (chosen by the user among Spark / Liquid / Gaze / Crystal): two overlapping circles with two eyes in
  the overlap. No spinning (the spin + sweep gradient "jumped" and they didn't like it). It blinks, looks around, looks
  up when thinking, "^ ^" eyes on completion. The notification icon keeps the dot (eyes aren't visible at 24 dp).

## 2026-09-28 — v3.1.0: priority, places, Now Bar, spoken replies, pill at the top

Plan approved by the user ("Lumi — Plan v3.1"). Decisions:
- **Own bottom bar** (`LumiNavItem` in MainActivity): Material's `NavigationBarItem` animated indicator, label and icon at
  once and it "shook" on the S25. Fixed geometry, only colors animate. Don't go back to NavigationBarItem.
- **Compact pill at the top** (Dynamic Island style): drops with a spring, closes by swiping up, reply and confirmation
  below; glow only on the top edge (`TopEdgeGlow`). It doesn't cover the keyboard or the gesture bar.
- **Priority** `TaskPriority` NONE/LOW/MEDIUM/HIGH (three levels + none; the user didn't choose "urgent only"). Room 5→6
  (`priority TEXT NOT NULL DEFAULT 'NONE'`, `place_trigger TEXT`). Rules: «urgente/importante/cuanto antes/!!» → HIGH,
  «no es urgente/sin prisa/cuando pueda» → LOW (rule order matters). New `SET_PRIORITY` action («pon X como urgente»,
  «prioriza X»). Affects: list order (switchable "Sort: date"), DayPlanner (HIGH first), ReminderPlanner (day-before at
  20:00 even without a time), widget and brief. Google Tasks has no priority → local only.
- **Confirm before leaving the editor:** the sheet's `confirmValueChange` intercepts swipe/tap outside/back when there are
  changes; Save / Discard / Keep editing dialog. (A new empty task closed without asking; changed in 1.0: see above.)
- **Now Bar / live update** (`LiveUpdateManager` + pure `LiveUpdatePlanner`): a promoted ongoing notification
  (`setRequestPromotedOngoing`, `setShortCriticalText`, countdown chronometer) with the next timed task or meeting in the
  next 2 h. Recomputed with alarms at the next change (at most hourly), when tasks are saved and when the app opens.
  "Hide" hides that item. Events Lumi created for its tasks are excluded.
- **Lumi speaks** (`LumiSpeaker`): system TTS only when the request came by voice; audio focus with ducking; goes quiet
  if you talk or type again. "Spoken replies" setting (on by default).
- **Place reminders:** Play Services geofences (`play-services-location` 21.3.0), one per task, 150 m radius, no initial
  trigger, **a single reminder** per task+place (like Apple Reminders; re-armed if the place changes or it repeats).
  Places in Settings → Places ("Save here", synonyms oficina/curro → trabajo). If you dictate an unsaved place, the task
  keeps it anyway and Lumi explains how to save it. Needs "Allow all the time" location to work with the app closed.
  Geofences are re-registered when the phone boots. Registration failures are explained in Settings → Places (1000 =
  "Google Location Accuracy" missing, 1004 = "All the time" missing).
- **Editor sheet:** in this BOM, "back" calls `onDismissRequest` without going through `confirmValueChange` → both paths
  check for changes, and "Keep editing" raises the sheet again (`sheetState.show()`).
- **Verified on the emulator (API 36):** migration 5→6 over an installed 3.0.3, pill at the top, priority + place by
  voice, discard dialog, saving a place, live notification with a countdown. Not verifiable on the emulator: the
  promoted Now Bar chip (depends on One UI 8), geofences firing (the emulator lacks Google's accuracy) and the TTS voice
  (no audio on the emulator).

## 2026-09-28 — v3.0.3: "Oye Lumi" fired during conversations + "Train my voice"

- **Cause:** 3.0.2's closed grammar forces Vosk to fit *any* audio into one of the phrases → with 20 synthetic
  conversation phrases it fired 18/20 times, with confidence 1.0 (confidence is useless as a filter).
- **Decision 1 — free transcription + short-phrase filter** (`WakePhrases.isWakePhrase`): the final result must have 2–3
  words, start with oye/hola/ey/hey and the rest must resemble "lumi" (Levenshtein ≤ 1 over «lumi/lomi/alumni…»).
  Result: 0/20 false positives, 3/5 positives. **Rule for the user:** "Oye Lumi", *pause*, then the request (like Siri).
  Saying it all at once doesn't trigger.
- **Decision 2 — local Voice Match** (`VoiceProfile.kt`): voice print model `vosk-model-spk-0.4` (13 MB, 128-dim x-vector),
  downloaded with the voice model. Trained with 3 "Oye Lumi" samples; the average is stored plus how Vosk transcribed
  the user's "Lumi" (widening the accepted names). Cosine threshold per sensitivity: Strict 0.55 / Normal 0.45 / Relaxed
  0.35 (measured: same voice 0.69–0.97, another voice ≈ 0.03). Without a trained profile only the phrase filter applies.
  Everything stays on the phone (SharedPreferences `voice_profile`).
- **Decision 3 — safety net:** if the assistant opens through "Oye Lumi" (`EXTRA_FROM_WAKE_WORD`) and what it heard
  doesn't look like a command (`CommandLikeness`), it asks "I heard «X». Should I note it down?" instead of creating
  tasks. With no answer in 8 s, or if it heard nothing (2.5 s), it closes on its own.

## 2026-09-28 — v3.0.2: "Oye Lumi" didn't trigger

- **Cause:** "lumi" isn't in the vocabulary of Vosk's Spanish model. With a closed grammar, Vosk silently drops unknown
  words (`Ignoring word missing in vocabulary: 'lumi'`), so it could only recognize "oye"/"hola" and the detector (which
  looks for "lumi") never fired. **Verified** by loading the model with Vosk in Python and synthetic es-ES audio.
- **Fix:** a grammar with «lu mi» (two vocabulary words that sound the same) → 3/3 triggers, 0/5 false positives. «lo mi»
  was rejected: a false positive with «la luz del mediodía». Phrases in `WakePhrases` with a regression test.
- Rule: **any new word in the Vosk grammar must be checked against the model's vocabulary.** (Superseded by openWakeWord in 3.5.0.)

## 2026-09-28 — v3.0.1: "Oye Lumi" download and voice errors

- **Symptom (outdoors, on mobile data):** "Couldn't download the model" when turning on "Oye Lumi". The server was fine
  (1 s from the PC). **Causes:** (1) the download ran in the screen's `lifecycleScope` while the "Display over other apps"
  permission opened → if Samsung closed the Activity, it was cancelled and shown as an error; (2) no retries or resume on
  a flaky mobile network; (3) the real reason wasn't shown. **Fix:** download in the app scope, resumable (HTTP Range)
  with 4 attempts, visible reason + "Retry", the overlay permission is now a separate button, and "Oye Lumi" stays on
  while the download continues.
- **Vosk `setPause(true)` does NOT release the microphone** (it keeps recording): Google's recognizer would get silence.
  Now `stop()` is used and listening resumes with `startListening()`.
- Speech recognizer errors are now shown in the assistant (they used to be ignored and voice seemed "not to work").
  Outdoors the typical case is `ERROR_NETWORK`: without the offline Spanish pack, Google needs internet.

## 2026-09-28 — v3.0 "Lumi 3.0"

### Design (request: serious, a Manus × Revolut mix with an Apple touch; no firefly, the name stays)
- **Design system** in `presentation/theme/LumiTheme.kt`: `LumiColors` tokens (light: white + sky blue `#38BDF8`; dark:
  near-black + pastel magenta `#F5A9D0`), **Inter** typeface (the closest to SF, OFL, bundled in `res/font`), flat
  surfaces, thin borders. Access: `Lumi.colors`. System/Light/Dark selector in Settings.
- In light mode sky blue lacks contrast as text on white → `accentText = #0284C7` for text/icons and `accent` only for
  fills (with dark text on top).
- **Removed:** gradients on text/buttons, the aurora, category emojis (now icon + color from the validated palette),
  emojis in replies (the prompt asks for a sober style). **The gradient only exists in Lumi's brand.**
- **Lumi logo** (`LumiMark`): two interlaced sky blue/magenta arcs + a core; IDLE/LISTENING/THINKING/SPEAKING/SUCCESS
  states. A static version generated for the icon, widget and notification. (Replaced by "Gaze" in 3.2.)
- **Assistant in two modes:** compact (Siri/Gemini-style pill, voice first, tap the logo → type) when invoked from
  outside (side button, "Oye Lumi", widget, tile); full (conversation) from the app's bar.
- Edge glow (Apple Intelligence style) only in compact mode while listening/thinking.

### Features
- **Multiple reminders** (`reminders` table, Room v5): Lumi decides the AUTO ones with `ReminderPlanner` (important
  appointment: day before at 20:00 + chosen lead time + 10 min; deadline: 2 days before, that day at 9:00 and 18:00;
  linked meeting: 15 min before). The user adds relative CUSTOM ones ("remind me 2 hours before" or in the editor) that
  follow the due date if it changes. A switch turns the AUTO ones off.
- **Recurrence** (`Recurrence`, compact text "WEEKLY:MO,TH"): completing creates the next one with the same custom
  reminders. Only with «cada/todos los/los + día» (not «el lunes y el jueves», which is one-off).
- **Rescheduling:** imperatives only (mueve, pospón, aplaza, retrasa, adelanta, cambia, pasa, reprograma). Infinitives
  («pasar la ITV») are new tasks. If the task to move isn't found → it is created as a new task.
- **Brain dump:** only split if EVERY chunk starts with a verb («comprar pan y leche» = 1 task); a date said once is shared.
- **Meetings:** the hint «para la reunión del X» → `MeetingMatcher` (shared words; generic → next meeting). Without its
  own date, the task is due 1 h before the meeting.
- **Quick capture:** share → Lumi (links as «Revisar: …» with the URL as the note), Quick Settings tile, replying from a
  reminder notification ("done", "in 20 minutes" = remind again, "postpone an hour" = move, "tomorrow at 10").
- **"Oye Lumi"** (first version): Vosk (Apache 2.0, 40 MB small Spanish model) with a closed grammar. A microphone-type
  foreground service; only started with the app visible (Android 14 restriction), paused while the assistant uses the
  mic and optionally with the screen off. Opening the assistant over other apps uses the SYSTEM_ALERT_WINDOW exemption;
  without that permission it shows a notification. **It doesn't replace "Hey Google"** (reserved for the system).

## 2026-09-28 (morning) — v2.1.1: Gemma on GPU

- **Problem (seen on the S25 with diagnostics):** Gemma loaded on GPU (12.6 s) but every answer failed with "Can not find
  OpenCL library on this device" → Lumi fell back to another engine. **Cause:** since Android 12, vendor native libraries
  (`libOpenCL.so`) aren't visible to apps unless declared. **Fix:** `<uses-native-library android:name="libOpenCL.so" /
  "libvndksupport.so" required=false>` in the manifest (required by the official LiteRT-LM GPU guide).
- **Safety net:** if the GPU still fails while generating, `GemmaLocalEngine` reloads on **CPU**, retries the same request
  and remembers it (`force_cpu` in prefs; `retryGpu()` reverts it). So it stays local AI instead of falling to the cloud.

## 2026-09-28 (night) — v2.1 "Lumi" (decisions taken without the user, at their request)

### Mascot: Lumi (replaced in v3.0 by an abstract logo)
- **Decision:** the assistant is called **Lumi**, an AI firefly (not a physical robot). The app is also called "Lumi".
  **Why:** the user wanted a "cute" mascot like big companies have; a firefly fit the glowing gradient orb that already
  existed, so it got a face instead of a full redesign.
- ~~The `applicationId` does NOT change~~ (superseded by the 1.0 rename, see above).

### AI
- **Gemma WITHOUT a timeout** (explicit request): the next engine is only used if Gemma fails or returns nothing. If it
  is slow, the user turns it off in Settings. `AssistantOrchestrator` accepts a `null` timeout.
- **Why v2.0 always fell back to Gemini cloud** (fixed): LiteRT-LM's `ThinkingConfig()` comes with reasoning ON and an
  unlimited budget; no `maxOutputToken`; and the coroutine timeout didn't stop native generation, which kept the mutex and
  blocked every following request. Now: thinking off, limited tokens, streaming with `cancelProcess()` on cancel.
  Diagnostics visible in Settings ("Test Gemma").
- **Gemini Nano:** confirmed NOT available on the user's Galaxy S25 (no Prompt API). The code stays just in case.
- **Descriptions:** only if the user gives one explicitly («descripción …», «detalles …», «nota: …», «con la nota de que
  …»). The AI may only fill it if ≥ 80 % of its words appear in what the user said (`isQuotedFrom`). Never invented.

### Daily summary
- **Cached** (`BriefStore`, SharedPreferences) and only regenerated if there is none, it is from **another day**, or the
  user taps "refresh". **Why:** the user didn't want to spend Gemini requests / Gemma time on every app start. "Another
  day" was my addition: yesterday's summary would say "good night" and list old tasks. (1.0: also per language.)
- The Progress tab comment is generated by local rules, **never the AI**, for the same reason.

### UI
- Bottom navigation: **Home · Tasks · Agenda · Progress**; Settings from the gear on Home.
- Home reduced to greeting + Lumi card + "Today" + the "Ask Lumi" bar (there were too many buttons before).
- Tasks: filters as **dropdowns**, groups Overdue/Today/Upcoming/No date/Done; tap = edit; "+" = create by hand
  (`TaskEditSheet`).
- Agenda: a Google Calendar-style day view (week strip, all-day row, timeline with task and event blocks, columns when
  they overlap, a red "now" line). Timed tasks last 30 min in the view.
- Charts (dataviz skill): colors = validated dark steps of the reference palette (blue `#3987E5`, orange `#D95926`). The
  validator couldn't run (no Node.js on the PC), but these are slots 1–2 of the already validated reference palette; CVD
  separation doesn't depend on the background and ours (`#111421`) is darker than the reference → contrast ≥.
- Voice: default language **es-ES** (the system language was English on the user's phone). Selector in Settings.
  (1.0: the default follows the phone's language, and TTS follows each reply's language.)

### Sync
- **Google Calendar through `CalendarContract`** (the system calendar) instead of the REST API: free, no Google Cloud,
  Android itself syncs with Google. Timed appointments → a 30-min event without an alarm (Lumi reminds, to avoid
  duplicates). Events Lumi creates are excluded when reading the agenda (they'd show twice).
- **Google Tasks through the REST API + the Authorization API** (`play-services-auth`). The user must create an Android
  OAuth client (guide: `docs/GOOGLE_TASKS_SETUP.md`; the SHA-1 is shown in Settings with a copy button). Its own "Lumi"
  list. Conflicts: the most recent change wins. Tombstones for deletions. Cancelled → deleted in Google. Syncs when the
  app opens and 3 s after each change (no WorkManager: enough for personal use).
- `TaskChangeListener`: the repository notifies calendar, Google Tasks and the widget of changes (decoupled).
- `TaskEntity.mergeFrom(task)`: sync ids are kept when updating from the domain (`toEntity()` used to lose them).

### Toolchain (v2.0)
- AGP 8.13.2 + Gradle 8.14.3 + Kotlin 2.4.20 + KSP 2.3.12 + compileSdk 36. The newest AndroidX (core 1.19, lifecycle
  2.11, activity 1.13, Compose BOM ≥ 2026.08) require compileSdk 37 + AGP 9.1 → older versions are pinned. Moving to
  AGP 9 is a separate change (Kotlin built into AGP, the kotlin-android plugin goes away).
- PowerShell 5 `Set-Content` corrupts UTF-8; and in bash heredocs, careful with `\n` in Python (it was taken as a real
  newline and removed indentation in `app/build.gradle.kts`): edit files with Python or the editing tools.

## 2026-09-28 (day) — v2.0

- Chained AI engines (`AssistantEngine`): Gemini Nano (AICore) → Gemma (LiteRT-LM, ~2.6 GB download) → Gemini cloud
  (optional API key, off by default) → rules. The LLM only interprets and phrases; data (plan, tasks) is computed by code.
- The `Service` overlay was replaced by a translucent `AssistantActivity`: AICore only runs inference with a foreground
  Activity, and this way the app can be the system **digital assistant** (`ACTION_ASSIST`).
- Spanish dates/times with `SpanishDateParser` («a las 5» alone = 17:00). Reminders with AlarmManager (+ "Done" / "+1 h").

## 2026-09-28 — v1.x

- Fixed categories (`TaskCategory`), inferred by AI → keywords → active filter → PERSONAL; editable with one tap.
- Task matching in `UPDATE_STATUS`: LIKE and, if that fails, word overlap (avoids duplicates).

---

## Known issues / technical debt

- Not yet verified on the S25: the Now Bar chip on One UI 8, place reminders with "all the time" location, "Oye Lumi"
  with the real microphone, Gemma on GPU after the OpenCL fix, replying from a notification, real Google Calendar meetings.
- The wake word was trained on synthetic (TTS) voices with simulated phone-mic effects; real voices may score lower.
  English sound-alike names ("Hey Lucy") can wake it. If it misses you, use "Relaxed" + "Train my voice".
- Voice Match thresholds were set on TTS voices; Vosk gives no print for some short wakes even after the doubled retry
  (then media wakes need 0.80 + confirmation).
- Google Tasks only has a date (no time) → the time lives only on the phone.
- The Agenda view doesn't allow dragging blocks to change the time (edit from the sheet).
- The APK is debug-signed (no release keystore): an app signed with another key can't update over it.
