# ClaudeSSH

An Android app specifically designed for using Claude Code via SSH into a remote server. Connects to tmux sessions and provides a mobile-optimized interface for the Claude Code workflow.

## Why This Exists

Using Claude Code over SSH on a phone has two major pain points:

1. **No arrow keys on mobile keyboards** - You need up/down arrows constantly (command history, navigating Claude Code menus). This app adds transparent overlay arrow buttons on either side of the screen.

2. **Can't scroll back through output** - Claude Code streams text fast. In a normal terminal (including VSCode's), scrolling sends arrow key commands instead of letting you read the output. This app caches all terminal output locally and renders it as a scrollable document, so you can scroll back through everything without sending keystrokes to the server.

## Features

- **SSH connection management** - Save connection profiles with SSH key or password auth
- **Tmux integration** - Auto-attach to tmux sessions, create new sessions with Claude Code
- **Scrollable history** - All output is cached locally. Scroll up to read, scroll back down to resume live view. History persists across sessions.
- **Arrow key overlay** - Transparent up/down buttons on left or right edge (configurable). Sends actual escape sequences for arrow key presses.
- **Extra keys bar** - Tab, Esc, Ctrl+C, Ctrl+D, Ctrl+Z, Ctrl+L, Ctrl+A, Ctrl+E
- **ANSI color support** - Full 256-color and truecolor rendering of Claude Code's styled output
- **"Scroll to bottom" FAB** - When viewing history, tap to jump back to the live terminal
- **Dark theme** - Designed for terminal use

## Setup

1. Open the app, go to **Connections** (overflow menu)
2. Tap **+** to add a new connection
3. Enter your server details (host, username, SSH key path or password)
4. Configure tmux session name (default: `claude`)
5. Connect - the app will SSH in and attach to your tmux session

## Settings

- **Arrow position**: Left or right side of the screen
- **Arrow opacity**: How transparent the overlay buttons are
- **Font size**: Terminal text size
- **Keep screen on**: Prevent screen timeout during sessions
- **Save history**: Persist session output to disk between app restarts
- **Extra keys**: Show/hide the special keys bar

## Technical Details

- **Min SDK**: 26 (Android 8.0)
- **SSH**: JSch (maintained mwiede fork)
- **Terminal rendering**: ANSI escape code parser with SpannableStringBuilder output, rendered in a scrollable NestedScrollView
- **Architecture**: MVVM with Repository pattern, Kotlin Coroutines + Flow
