# Terminal App Design Plan

Based on analysis of the Termux terminal emulator codebase. Focused on how to build a local Android terminal with PTY-backed shell sessions.

## Architecture Overview

Termux uses a clean four-layer architecture:

```
TermuxActivity (UI)
    ↕ ServiceConnection
TermuxService (session lifecycle, foreground service)
    ↕ creates/manages
TerminalSession (PTY I/O, threading)
    ↕ contains
TerminalEmulator (VT100/xterm state machine) + TerminalView (Canvas rendering)
```

The key insight: the terminal emulator and view are fully decoupled from the app layer. They live in separate Gradle modules (`terminal-emulator`, `terminal-view`) with no Android dependencies in the emulator module.

---

## How Sessions Are Opened

### 1. Native PTY Creation (JNI)

The core of session creation is a native C function (`jni/termux.c`) that:

1. Opens `/dev/ptmx` (PTY master) with `O_RDWR | O_CLOEXEC`
2. Calls `grantpt()` + `unlockpt()` to set up the slave side
3. Gets slave device name via `ptsname_r()` (e.g. `/dev/pts/3`)
4. Configures the terminal: sets UTF-8 mode, disables flow control via `tcsetattr()`
5. Sets initial window size via `ioctl(TIOCSWINSZ)`
6. **Forks** the process:
   - **Child**: calls `setsid()`, opens the PTY slave, dups it to fd 0/1/2 (stdin/stdout/stderr), then `execvp()` to replace itself with the shell (e.g. `/bin/bash`)
   - **Parent**: returns the PTY master file descriptor to Java

This gives us a real pseudo-terminal — the shell thinks it's running in a normal terminal.

### 2. Java Session Initialization (`TerminalSession.initializeEmulator()`)

After JNI returns the master FD, the Java side:

1. Creates a `TerminalEmulator` (the VT100 state machine) with configured rows/columns
2. Spawns **three threads**:
   - **InputReader**: reads bytes from PTY master FD → pushes to `mProcessToTerminalIOQueue` (a `ByteQueue`)
   - **OutputWriter**: reads from `mTerminalToProcessIOQueue` → writes to PTY master FD
   - **Waiter**: calls `waitpid()` on the child process, detects exit

### 3. Service-Level Management

`TermuxService` is a foreground Android Service that:

- Maintains a list of all active `TermuxSession` objects
- Survives activity destruction (sessions persist in background)
- Shows a persistent notification
- Handles session creation/destruction lifecycle

---

## How Sessions Are Maintained

### Data Flow: Shell Output → Screen

```
Shell process writes to stdout (PTY slave fd)
    ↓
PTY kernel buffer
    ↓
InputReader thread reads from PTY master fd
    ↓
mProcessToTerminalIOQueue (ByteQueue, thread-safe)
    ↓
MainThreadHandler receives MSG_NEW_INPUT
    ↓
TerminalEmulator.append(buffer, length)
    ↓
processByte() → processCodePoint() for each byte
    ↓
State machine handles escape sequences, updates TerminalBuffer
    ↓
notifyScreenUpdate() → TerminalView.invalidate()
    ↓
onDraw() → TerminalRenderer.render() paints to Canvas
```

### Data Flow: User Input → Shell

```
User types on keyboard
    ↓
TerminalView.onKeyDown() or IME commitText()
    ↓
KeyHandler converts to escape sequences (arrows → \033[A, etc.)
    ↓
TerminalSession.write() / writeCodePoint()
    ↓
UTF-8 encode into mUtf8InputBuffer
    ↓
mTerminalToProcessIOQueue (ByteQueue)
    ↓
OutputWriter thread reads queue → writes to PTY master fd
    ↓
Shell reads from stdin (PTY slave fd)
```

### Terminal Emulation

`TerminalEmulator` is a byte-by-byte state machine:

- **UTF-8 decoding**: accumulates multi-byte sequences into code points
- **Control characters** (0-31): handles BEL, BS, TAB, LF, CR, ESC directly
- **Escape sequence state machine**: `ESC_NONE` → `ESC` → `ESC_CSI` / `ESC_OSC` etc.
  - CSI sequences (`\033[...`): cursor movement, colors, erase, scroll regions
  - OSC sequences (`\033]...`): window title, clipboard
- **Screen buffer**: `TerminalBuffer` is a circular array of `TerminalRow` objects
  - Two buffers: main (with scrollback) and alternate (for fullscreen apps like vim)
  - Each row stores characters + packed style info (colors, bold, underline)

### Rendering

`TerminalRenderer` draws to an Android `Canvas`:

- Monospace font, each cell is exactly `fontWidth × fontLineSpacing` pixels
- Iterates visible rows, groups consecutive characters with same style
- Draws text runs with `canvas.drawText()`, backgrounds with `canvas.drawRect()`
- Supports 256-color + 24-bit truecolor, reverse video, cursor rendering

### Window Resize

When the `TerminalView` size changes:

1. Calculate new rows/columns from pixel dimensions and font metrics
2. Call `JNI.setPtyWindowSize(fd, rows, cols)` → sends `TIOCSWINSZ` ioctl
3. Kernel delivers `SIGWINCH` to the shell process
4. Shell redraws for new dimensions

---

## Plan for claude_portal

### What We Can Reuse

Termux's `terminal-emulator` and `terminal-view` modules are Apache 2.0 licensed and designed to be standalone. We could either:

**Option A: Use Termux modules as dependencies**
- Add `terminal-emulator` and `terminal-view` as local modules
- Write our own Service + Activity layer on top
- Pros: battle-tested emulator, full VT100/xterm compatibility
- Cons: large codebase, more than we need for SSH-only

**Option B: Build minimal from scratch, informed by Termux patterns**
- Implement a simplified version of the same architecture
- Skip local PTY entirely — our "session" is an SSH channel, not a fork+exec
- Pros: smaller, tailored to SSH use case
- Cons: more work, risk of emulator bugs

### Key Difference: SSH vs Local PTY

For claude_portal, the session backend is fundamentally different:

| | Termux | claude_portal |
|---|---|---|
| Session backend | Local PTY + fork/exec | SSH ChannelShell |
| I/O source | PTY master fd | SSH channel InputStream/OutputStream |
| Process lifecycle | waitpid() on child | SSH channel close/disconnect |
| Window resize | ioctl(TIOCSWINSZ) | channel.setPtySize() |
| Auth | N/A | Password / key-based SSH auth |

But the **emulator and view layers are identical** — we still need VT100 parsing and Canvas rendering regardless of where the bytes come from.

### Recommended Architecture

```
ConnectionActivity (UI, manages TerminalView)
    ↕
SshSessionService (foreground service, session lifecycle)
    ↕ creates/manages
SshTerminalSession (wraps SSH channel + TerminalEmulator)
    ├── JSch Session + ChannelShell (network I/O)
    ├── TerminalEmulator (VT100 parsing)
    └── Two I/O threads:
        ├── InputReader: SSH channel InputStream → emulator
        └── OutputWriter: user input queue → SSH channel OutputStream
```

### Key Implementation Steps

1. **Adapt TerminalSession for SSH**: Replace the PTY/fork/exec with JSch `ChannelShell`. The `InputReader` reads from `channel.getInputStream()` instead of a PTY fd. The `OutputWriter` writes to `channel.setOutputStream()` (see android-jsch-notes.md — use `setOutputStream()` not `getOutputStream()` to avoid PipedInputStream EOF bugs).

2. **Use or port the TerminalEmulator**: The emulator processes bytes identically regardless of source. Either include Termux's `terminal-emulator` module or write a simpler one.

3. **Use or port TerminalView**: The rendering is the same — monospace grid of styled characters on a Canvas.

4. **Session lifecycle**: Replace `waitpid()` with SSH channel/session disconnect detection. Handle network interruptions, reconnection logic.

5. **Window resize**: Call `channel.setPtySize(cols, rows, pixelWidth, pixelHeight)` instead of the ioctl.

6. **Service layer**: Foreground service to keep SSH connections alive when the app is backgrounded, similar to TermuxService.

### Threading Model

Match Termux's three-thread model, adapted for SSH:

- **InputReader thread**: `while ((len = sshInputStream.read(buf)) != -1) { processToTerminalQueue.write(buf, len); }` — feeds bytes into the emulator via a main-thread handler
- **OutputWriter thread**: reads from `terminalToProcessQueue` and writes to `channel.getOutputStream()` (or uses `setOutputStream()`)
- **Connection monitor**: watches `session.isConnected()` and `channel.isClosed()`, triggers reconnect or cleanup

### What We Already Have

claude_portal already has `SshManager` using JSch with `ChannelShell`. The main gaps are:

- No terminal emulator (we're likely just dumping raw text)
- No proper terminal view with escape sequence rendering
- No service-based session management for background persistence
