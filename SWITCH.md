# Buddy for Switch

A fork of [Buddy](https://github.com/ghorbelhamdi/buddy-android) for Android on the Nintendo Switch (LineageOS, rooted, Termux).
The difference: the agent is **Claude Code from Termux**, not a second copy inside Buddy's own Alpine/proot Linux.
One Claude, one environment (the Termux one: its root, its tools, its memory), Buddy's chat and bubble on top.

## Goals

- **Nothing running while idle.** No resident Claude process: each message runs `claude -p --resume <session>` and exits,
  as Buddy already does. Termux is started for the run and left for Android to reclaim afterwards.
- **No built-in Linux.** No Alpine download, no proot, no second Claude install (saves ~316 MB of storage).
- **Same Claude as the Termux terminal.** Same `~/.claude`, sign-in, MCP servers, project memory and `CLAUDE.md`.
- **Fit the Switch.** Landscape 1280×720, Joy-Con navigation, root available.

## How Buddy works today

```
App ──ProcessBuilder(proot … bash /root/bin/buddy-run <token> ask <chat> claude <dev> <msg>)──► Alpine
 ▲                                                                                  │ claude -p --output-format stream-json
 └──────────────── POST http://127.0.0.1:8765/event (Bearer token) ◄────────────────┘
App's accessibility service ◄── MCP http://127.0.0.1:8765/mcp?chat=<id> (phone tools)
```

The app and the helper only talk over localhost HTTP, so the helper can run anywhere on the device.

## The change: Termux backend

```
App ──Intent com.termux.RUN_COMMAND (background, script on stdin)──► Termux: bash -s -- <token> ask …
 ▲                                                                       │ claude -p --resume … (Termux's own claude)
 └──────────────── POST http://127.0.0.1:8765/event ◄────────────────────┘
```

- **Run through `RUN_COMMAND`**, not `su`: the run happens in Termux's own process and SELinux context, so every file
  Claude writes (sessions, `~/.claude.json`) stays readable by the Termux terminal. (Root via `su <uid>` would create files
  with the wrong SELinux categories.) Needs `allow-external-apps=true` (already set) and the
  `com.termux.permission.RUN_COMMAND` permission (granted with `pm grant`).
- **The helper script travels on stdin** (`RUN_COMMAND_STDIN`) from the APK's assets, so the Termux side needs no install
  step and always matches the app version.
- **Short commands with a result** (checks, the smart reviewer) use `RUN_COMMAND_PENDING_INTENT`, which returns stdout,
  stderr and the exit code.
- A new `Termux` class replaces `Linux`/`Helper.run` behind the same calls; `buddy-run` gets Termux paths
  (`$PREFIX/bin/claude`, `~/.buddy/` for sessions and work).

## Features

| Buddy feature | In this fork |
|---|---|
| Chats, bubble, tool calls, approvals, ask, point_out | kept |
| Phone tools (MCP, accessibility) | kept; also usable from the Termux terminal (already set up) |
| Notification rules | kept (restricted agent, as upstream) |
| Smart reviewer | kept, through Termux |
| Agent install / sign-in, Alpine download | removed: check that Termux's `claude` exists and is signed in |
| Codex, OpenCode | hidden (only Claude Code is used) |
| Built-in terminal, SSH server, mic bridge | removed: Termux is the terminal |
| Claude/ChatGPT dictation, ChatGPT voice | removed (they need the built-in Linux); the phone's recognizer and TTS stay |
| Remote Control session inside Buddy | ported to Termux as an on/off switch (Settings and a Quick Settings tile): `claude remote-control --name Switch` in server mode, so sessions are started from the Claude app or claude.ai/code on demand; off frees the memory, optional auto-off after hours of inactivity. Starting `/remote-control` by hand in the Termux terminal keeps working |
| Self-update from upstream releases | pointed at this fork's releases, or off |
| Developer mode (Bash, files) | chats get Claude's full tools in Termux, like the terminal session |

## Steps

1. **Own identity:** application id `app.buddy.sw`, name "Buddy SW", own signing key, so it installs next to upstream Buddy
   for side-by-side testing. Java packages stay `app.buddy.assistant` (smaller diff against upstream).
2. **Termux backend:** `Termux.java` (`RUN_COMMAND` with stdin; result through a PendingIntent); `Helper.run` and the
   other call sites go through it.
3. **`buddy-run` for Termux:** paths, the Termux `claude`, tool permissions for chats; Alpine-only parts removed.
4. **Setup and settings:** replace Install/Sign in with a Termux check; hide Codex/OpenCode and the Linux-only screens.
5. **Idle footprint:** measure after a chat: Termux process and service, Buddy's service; tune (e.g. let Termux's service
   stop after background runs).
6. **Remote Control switch:** `claude remote-control` in Termux, on/off from Settings and a tile; measure its idle memory.
7. **Switch fit:** Joy-Con navigation in the chat and bubble, landscape layout.

Build on the Switch: `build.sh` with `SDK_JAR` = platform-36 `android.jar` and `AAPT_JAR` = platform-33 `android.jar`
(Termux's aapt2 can't read API 36 resources).
