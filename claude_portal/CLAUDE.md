# claude_portal - Claude Code Instructions

## Project Overview
Android app for SSH-ing into a server to use terminal coding agents (Claude Code, OpenAI Codex) via tmux. Not a general-purpose terminal - specifically designed for the agent workflow.

## Versioning Policy
Keep all build tooling at latest stable versions: AGP, Kotlin, Gradle, compileSdk/targetSdk, and AndroidX dependencies. When upgrading, update everything together.

## Architecture
- **Kotlin / Android SDK 35** (minSdk 26)
- **MVVM-ish** with Repository pattern, Kotlin Coroutines + Flow
- **ViewBinding** (no Compose)
- **SSHJ** (`com.hierynomus:sshj`) for SSH connections

## Package Structure
```
com.claudeportal.app/
├── ssh/          # SSH connection management (SshManager, KeyCode)
├── terminal/     # Terminal rendering (AnsiParser, HistoryBuffer, TerminalView, AgentKind, CodexUi)
├── data/         # Repositories (ConnectionRepository, HistoryRepository, SettingsRepository)
├── models/       # Data classes (ConnectionProfile, AppSettings, TmuxSession)
├── ui/           # Custom views (ArrowOverlayView)
└── *.kt          # Activities (Main, Connection, Settings, SessionPicker)
```

## Key Design Decisions

### Scrollable History (not terminal scrollback)
The TerminalView is a NestedScrollView + TextView, NOT a traditional terminal grid. All output is appended to a SpannableStringBuilder. Scrolling up is a pure UI operation - it does NOT send arrow key escape sequences. This solves the core problem where an agent's fast output is impossible to review on mobile.

### Per-agent Filtering (AgentKind)
`OutputProcessor.agentKind` says which agent (NONE / CLAUDE / CODEX) is running in the active tmux window; every content-filtering heuristic is gated on it, because applying one agent's heuristics to the other — or to a plain shell — deletes real content.

Detection is by tmux window name first (`claude`, `codex`), then by screen content. Codex runs under `node`, so content detection carries most of the load there; its markers ("Ask Codex to do anything", the `>_ OpenAI Codex` banner, the `esc to interrupt` spinner row) are unambiguous enough to flip on a single sighting, unlike Claude's generic glyphs which need a rolling-window vote.

The two agents differ in ways that matter:

| | Claude Code | Codex |
|---|---|---|
| Screen use | alternate screen, whole-screen overdraw | inline, DECSTBM scroll region + reverse index |
| Line dedup | needed (same text arrives many times, mangled) | **off** — each line is written once; dedup would delete content |
| Spinner | animated glyph column (`✻ Thinking…`) | text row `• Working (12s • esc to interrupt)` + braille frame in the OSC title |
| Prompt / menu marker | `❯` | `›` |
| Status line | user's `ƕ…%~` bar + mode suffixes | footer row `<model> · <cwd>` |
| Chrome to strip | status area (fences, prompt, hints) | bottom block (spinner row, composer, footer) |

Codex UI signatures live in `CodexUi.kt`, captured from a real `codex` v0.153.4 session; `app/src/test/resources/codex_scroll_raw.txt` is a recording of one, replayed by `CodexScreenReplayTest`.

### Arrow Key Overlay
ArrowOverlayView draws transparent up/down buttons that send actual escape sequences (\x1b[A / \x1b[B) via SshManager. Position (left/right) is configurable in settings.

### ANSI Parser
AnsiParser converts raw terminal output with escape codes into Android Spannable styled text. Supports SGR (colors, bold, italic, underline), 256-color, and truecolor. Strips cursor movement sequences since we're rendering a document, not a terminal grid.

AnsiScreenInterpreter + VirtualScreen are the cursor-accurate pair used for screen diffing, and they do implement scroll regions (DECSTBM, RI/IND/NEL, SU/SD). Codex depends on this: it parks its composer and footer below the region, so a region-blind model renders the UI block over the conversation and loses most of the transcript.

### Session History Persistence
HistoryRepository saves plain-text session output to files on disk, keyed by connection name + timestamp. Users can scroll back through past sessions.

## TODOs
- [x] Tmux window control buttons: create window, next window, close window (close only available if >1 window open)
- [x] Create cute pixel art app icon
- [x] Up/down arrow buttons should be square and near the bottom of the screen
- [x] Respect the top status bar and bottom navigation bar — shrink content to be within the two

## Build
Standard Android Gradle build. No special setup required beyond Android SDK.
```
cd claude_portal && ./gradlew assembleDebug
```

## Dependencies
- `com.hierynomus:sshj:0.40.0` - SSH (replaced JSch which had MAC bugs on Android)
- `org.bouncycastle:bcprov-jdk18on` - Full BouncyCastle for ed25519 key support
- `androidx.datastore:datastore-preferences` - Settings persistence
- `com.google.code.gson:gson` - JSON for connection profiles
- Standard AndroidX (material, constraintlayout, lifecycle, coroutines)
