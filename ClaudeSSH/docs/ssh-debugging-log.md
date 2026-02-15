# SSH Connection Debugging Log

## Problem
After connecting to the server, the SSH session disconnects within seconds. Typed commands (e.g. `ls`) never reach the server.

## Key Findings

### 1. `getInputStream()` must be called BEFORE `shell.connect()`
JSch warns: `getInputStream() should be called before connect()`. When called after, the internal I/O plumbing isn't wired correctly. The session's `run()` loop hits "End of IO Stream Read" on the next packet after we write data, causing `Session.run()` → `Session.disconnect()`.

### 2. `setOutputStream()` runs on JSch's session thread
Using `shell.setOutputStream(myOutputStream)` means our code runs directly inside `Session.run()`. If anything goes wrong in our callback, it kills the session loop. This approach also caused a ~2.5s idle disconnect when `serverAliveInterval=2000` was set (the keepalive timeout was too aggressive).

### 3. Dedicated reader thread approach
Using `shell.getInputStream()` + a plain `Thread` (not coroutine) for reading. This:
- Decouples our code from JSch's session thread
- Avoids the PipedInputStream EOF bug (thread identity stays consistent with a plain Thread)
- Matches Termux's architecture (dedicated InputReader thread)

### 4. `getOutputStream()` before vs after `connect()`
Getting `shell.outputStream` before `connect()` returns a `Channel$1` packet writer. Writing to it before the channel is connected may produce malformed packets. Moving it after `connect()` caused a connect hang — needs more investigation. The previous working version (with `setOutputStream()`) got it before connect and it worked, so this may not be the issue.

### 5. Timeout/keepalive interactions
- `sshSession.timeout` sets `socket.setSoTimeout()` — affects Session.run()'s read loop
- `setServerAliveInterval(N)` overrides socket timeout to N ms in Session.run()
- With `serverAliveInterval=2000`, the session disconnected ~2.15s after last data — the keepalive mechanism itself may have been the trigger
- `timeout=0` (infinite) keeps idle sessions alive but breaks `connect()` since it also affects the connect timeout
- Fix: use `sshSession.connect(15000)` for connect timeout, then set `timeout` separately

### 6. Server zombie sessions
Repeated failed connection attempts leave zombie sshd processes on the server. Eventually the server stops accepting new connections (MaxStartups). Fix: `pkill -u nathan sshd` on server.

## Current State
- `getInputStream()` is now called before `connect()` ✓
- Dedicated reader thread (plain Java Thread) ✓
- JSch logger enabled for debugging ✓
- **Blocking issue**: TCP connect hanging — unclear if Tailscale, server, or timeout config problem. Need to verify phone network connectivity.

## Architecture (from terminal-design-plan.md)
The long-term fix is to adopt Termux's terminal architecture:
- Use Termux's `terminal-emulator` + `terminal-view` modules (Apache 2.0)
- Dedicated InputReader thread: SSH InputStream → ByteQueue → emulator
- Dedicated OutputWriter thread: user input queue → SSH OutputStream
- Foreground service for session persistence

## Code Pattern (current)
```kotlin
// Before connect: set up input stream
val inputStream = shell.inputStream

shell.connect()

// After connect: set up output stream
outputStream = shell.outputStream

// Dedicated reader thread
Thread({
    val buf = ByteArray(8192)
    while (true) {
        val len = inputStream.read(buf)
        if (len == -1) break
        _outputFlow.tryEmit(String(buf, 0, len))
    }
}, "ssh-reader").start()
```
