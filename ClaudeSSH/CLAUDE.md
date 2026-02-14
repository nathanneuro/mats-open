# ClaudeSSH - Claude Code Instructions

## Project Overview
Android app for SSH-ing into a server to use Claude Code via tmux. Not a general-purpose terminal - specifically designed for the Claude Code workflow.

## Versioning Policy
Keep all build tooling at latest stable versions: AGP, Kotlin, Gradle, compileSdk/targetSdk, and AndroidX dependencies. When upgrading, update everything together.

## Architecture
- **Kotlin / Android SDK 35** (minSdk 26)
- **MVVM-ish** with Repository pattern, Kotlin Coroutines + Flow
- **ViewBinding** (no Compose)
- **JSch** (`com.github.mwiede:jsch`) for SSH connections

## Package Structure
```
com.claudessh.app/
├── ssh/          # SSH connection management (SshManager, KeyCode)
├── terminal/     # Terminal rendering (AnsiParser, HistoryBuffer, TerminalView)
├── data/         # Repositories (ConnectionRepository, HistoryRepository, SettingsRepository)
├── models/       # Data classes (ConnectionProfile, AppSettings, TmuxSession)
├── ui/           # Custom views (ArrowOverlayView)
└── *.kt          # Activities (Main, Connection, Settings, SessionPicker)
```

## Key Design Decisions

### Scrollable History (not terminal scrollback)
The TerminalView is a NestedScrollView + TextView, NOT a traditional terminal grid. All output is appended to a SpannableStringBuilder. Scrolling up is a pure UI operation - it does NOT send arrow key escape sequences. This solves the core problem where Claude Code's fast output is impossible to review on mobile.

### Arrow Key Overlay
ArrowOverlayView draws transparent up/down buttons that send actual escape sequences (\x1b[A / \x1b[B) via SshManager. Position (left/right) is configurable in settings.

### ANSI Parser
AnsiParser converts raw terminal output with escape codes into Android Spannable styled text. Supports SGR (colors, bold, italic, underline), 256-color, and truecolor. Strips cursor movement sequences since we're rendering a document, not a terminal grid.

### Session History Persistence
HistoryRepository saves plain-text session output to files on disk, keyed by connection name + timestamp. Users can scroll back through past sessions.

## TODOs
- [ ] Tmux window control buttons: create window, next window, close window (close only available if >1 window open)
- [ ] Create cute pixel art app icon

## Build
Standard Android Gradle build. No special setup required beyond Android SDK.
```
cd ClaudeSSH && ./gradlew assembleDebug
```

## Dependencies
- `com.github.mwiede:jsch:0.2.16` - SSH (maintained JSch fork)
- `androidx.datastore:datastore-preferences` - Settings persistence
- `com.google.code.gson:gson` - JSON for connection profiles
- Standard AndroidX (material, constraintlayout, lifecycle, coroutines)
