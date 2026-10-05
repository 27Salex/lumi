# Claude bridge (legacy, original version)

The first, simpler way to talk to Claude Code on your PC from Lumi: a small Python HTTP service (`lumi_claude_bridge.py`,
see `SETUP.md`) reached over Tailscale with the owner's login plus a token.

It is kept here for reference. Its successor is [`../lumi-hub`](../lumi-hub), which Lumi's Orbit and "Chat with Claude" use.

`android-reference/` holds the original phone-side Kotlin as `.txt` files. They are NOT compiled (they live outside any
Gradle source set and don't end in `.kt`); they are only documentation of how the first version worked.
