package com.claudeportal.app.terminal

import android.text.SpannableStringBuilder
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe buffer with always-on disk persistence, per-tmux-window.
 *
 * In-memory: sliding window of recent styled content (~200K chars).
 * On-disk: plain text appended to per-window files ({connection}_w{N}.txt).
 * Each window also has a parallel {connection}_w{N}_dirty.txt that captures
 * raw, un-deduplicated output as a backup against dedup heuristic bugs.
 * Files persist across reconnects to the same server.
 *
 * Pending-switch routing: tmux button presses call beginPendingSwitch()
 * before the SSH command goes out. Output that arrives in the gap between
 * the button and the next status-bar parse is buffered in memory and then
 * flushed into the new window's files when commitPendingSwitch() lands.
 */
class HistoryBuffer(private val maxLines: Int = 50000) {

    private val lock = ReentrantReadWriteLock()
    private val styledContent = SpannableStringBuilder()
    private val plainContent = StringBuilder()
    private var lineCount = 0

    // Per-window disk persistence
    private var historyDir: File? = null
    private var connectionName: String? = null
    private var activeWindowIndex: Int = 0
    private val windowWriters = mutableMapOf<Int, FileWriter>()
    private val windowFiles = mutableMapOf<Int, File>()
    private val dirtyWindowWriters = mutableMapOf<Int, FileWriter>()
    private val dirtyWindowFiles = mutableMapOf<Int, File>()

    // (windowIdx, text, isDirty)
    private val pendingWrites = ConcurrentLinkedQueue<Triple<Int, String, Boolean>>()
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var writeJob: Job? = null

    // Pending-switch state: when non-null, incoming clean/dirty text is held
    // here instead of being routed to a window file. The next commitPendingSwitch
    // flushes these into the target window's files.
    private data class PendingSwitch(
        val cleanBuf: StringBuilder = StringBuilder(),
        val dirtyBuf: StringBuilder = StringBuilder(),
        val startedAt: Long = System.currentTimeMillis()
    )
    private var pendingSwitch: PendingSwitch? = null
    private val PENDING_TIMEOUT_MS = 2500L

    /**
     * Set the connection for disk persistence. Creates/opens the window 0 file.
     * Called when a connection starts. If reconnecting to the same server,
     * existing files are appended to (history is preserved).
     */
    fun setConnection(historyDir: File, connectionName: String) {
        lock.write {
            // If reconnecting to same server, keep existing writers
            if (this.connectionName == connectionName && this.historyDir == historyDir) return
            closeWriters()
            this.historyDir = historyDir
            this.connectionName = connectionName
            activeWindowIndex = 0
            pendingSwitch = null
        }
        ensureWriter(0)
        startDiskWriter()
    }

    /**
     * Switch which tmux window receives disk writes.
     * Creates the file/writer on demand if this window hasn't been seen before.
     * If a pending switch is in progress, this commits it: any buffered
     * output gets flushed into the new window's files.
     */
    fun setActiveWindow(index: Int) {
        ensureWriter(index)
        val pending = lock.write {
            val p = pendingSwitch
            pendingSwitch = null
            activeWindowIndex = index
            p
        }
        if (pending != null) {
            val clean = pending.cleanBuf.toString()
            val dirty = pending.dirtyBuf.toString()
            if (clean.isNotEmpty()) pendingWrites.add(Triple(index, clean, false))
            if (dirty.isNotEmpty()) pendingWrites.add(Triple(index, dirty, true))
        }
    }

    /**
     * Mark that a tmux window switch is in flight (user pressed a button).
     * Subsequent output is buffered until setActiveWindow() commits the switch
     * or PENDING_TIMEOUT_MS elapses. This prevents output from being written
     * to the wrong window's file in the gap between button press and the
     * tmux status bar reflecting the new active window.
     */
    fun beginPendingSwitch() {
        lock.write {
            // If a previous pending switch is stale, drop it (assume it failed
            // and the user is trying again). The buffered text is lost — it's
            // a backup mechanism, not authoritative.
            pendingSwitch = PendingSwitch()
        }
    }

    private fun checkPendingTimeout() {
        val expired = lock.read {
            val p = pendingSwitch ?: return
            System.currentTimeMillis() - p.startedAt > PENDING_TIMEOUT_MS
        }
        if (expired) {
            // Timeout: flush whatever was buffered into the *current* active
            // window so output isn't lost. Best-effort fallback.
            val pending = lock.write {
                val p = pendingSwitch
                pendingSwitch = null
                p
            } ?: return
            val idx = activeWindowIndex
            val clean = pending.cleanBuf.toString()
            val dirty = pending.dirtyBuf.toString()
            if (clean.isNotEmpty()) pendingWrites.add(Triple(idx, clean, false))
            if (dirty.isNotEmpty()) pendingWrites.add(Triple(idx, dirty, true))
        }
    }

    private fun ensureWriter(windowIndex: Int) {
        lock.write {
            val dir = historyDir ?: return
            val name = connectionName ?: return
            val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            if (!windowWriters.containsKey(windowIndex)) {
                val file = File(dir, "${safeName}_w${windowIndex}.txt")
                windowFiles[windowIndex] = file
                windowWriters[windowIndex] = FileWriter(file, true)
            }
            if (!dirtyWindowWriters.containsKey(windowIndex)) {
                val file = File(dir, "${safeName}_w${windowIndex}_dirty.txt")
                dirtyWindowFiles[windowIndex] = file
                dirtyWindowWriters[windowIndex] = FileWriter(file, true)
            }
        }
    }

    fun appendStyled(text: SpannableStringBuilder) = lock.write {
        styledContent.append(text)
        trimIfNeeded()
    }

    fun appendPlain(text: String) {
        checkPendingTimeout()
        lock.write {
            plainContent.append(text)
            lineCount += text.count { it == '\n' }
            val pending = pendingSwitch
            if (pending != null) {
                pending.cleanBuf.append(text)
                return@write
            }
            pendingWrites.add(Triple(activeWindowIndex, text, false))
        }
    }

    /** Append raw, un-deduplicated text to the active window's dirty file. */
    fun appendDirtyPlain(text: String) {
        checkPendingTimeout()
        lock.write {
            val pending = pendingSwitch
            if (pending != null) {
                pending.dirtyBuf.append(text)
                return@write
            }
            pendingWrites.add(Triple(activeWindowIndex, text, true))
        }
    }

    fun getStyledContent(): SpannableStringBuilder = lock.read {
        SpannableStringBuilder(styledContent)
    }

    fun getPlainContent(): String = lock.read {
        plainContent.toString()
    }

    fun getLineCount(): Int = lock.read {
        lineCount
    }

    fun getLength(): Int = lock.read {
        styledContent.length
    }

    /**
     * Read the full clean history file for the given window (defaults to active).
     * Used when re-rendering the terminal after a window switch or broom toggle.
     */
    fun readWindowClean(windowIndex: Int = activeWindowIndex, maxChars: Int = 200_000): String {
        val file = lock.read { windowFiles[windowIndex] } ?: return ""
        return readTail(file, maxChars)
    }

    /** Read the full dirty (un-deduplicated) history file for the given window. */
    fun readWindowDirty(windowIndex: Int = activeWindowIndex, maxChars: Int = 200_000): String {
        val file = lock.read { dirtyWindowFiles[windowIndex] } ?: return ""
        return readTail(file, maxChars)
    }

    fun activeWindow(): Int = activeWindowIndex

    private fun readTail(file: File, maxChars: Int): String {
        if (!file.exists()) return ""
        return try {
            // Flush pending writes to disk first so the file is up-to-date
            flushPendingWrites()
            val text = file.readText()
            if (text.length > maxChars) text.substring(text.length - maxChars) else text
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Load older content from the active window's clean disk file.
     * Returns plain text from the start of the file up to `maxChars`.
     */
    fun loadOlderContent(maxChars: Int = 100_000): String? {
        val file = lock.read { windowFiles[activeWindowIndex] } ?: return null
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            if (text.length > maxChars) {
                val inMemory = lock.read { plainContent.toString() }
                val diskOnly = if (text.endsWith(inMemory)) {
                    text.substring(0, text.length - inMemory.length)
                } else {
                    text
                }
                if (diskOnly.length > maxChars) {
                    diskOnly.substring(diskOnly.length - maxChars)
                } else {
                    diskOnly
                }
            } else {
                text
            }
        } catch (e: Exception) {
            null
        }
    }

    fun clear() = lock.write {
        styledContent.clear()
        plainContent.clear()
        lineCount = 0
    }

    fun close() {
        writeJob?.cancel()
        writeScope.cancel()
        flushPendingWrites()
        lock.write { closeWriters() }
    }

    private fun closeWriters() {
        for (writer in windowWriters.values) {
            try { writer.close() } catch (_: Exception) {}
        }
        for (writer in dirtyWindowWriters.values) {
            try { writer.close() } catch (_: Exception) {}
        }
        windowWriters.clear()
        windowFiles.clear()
        dirtyWindowWriters.clear()
        dirtyWindowFiles.clear()
    }

    private fun startDiskWriter() {
        writeJob?.cancel()
        writeJob = writeScope.launch {
            while (isActive) {
                flushPendingWrites()
                checkPendingTimeout()
                delay(500)
            }
        }
    }

    private fun flushPendingWrites() {
        try {
            val flushedClean = mutableSetOf<Int>()
            val flushedDirty = mutableSetOf<Int>()
            while (true) {
                val triple = pendingWrites.poll() ?: break
                val (windowIdx, text, isDirty) = triple
                val writer = lock.read {
                    if (isDirty) dirtyWindowWriters[windowIdx] else windowWriters[windowIdx]
                } ?: continue
                writer.write(text)
                if (isDirty) flushedDirty.add(windowIdx) else flushedClean.add(windowIdx)
            }
            for (idx in flushedClean) {
                lock.read { windowWriters[idx] }?.flush()
            }
            for (idx in flushedDirty) {
                lock.read { dirtyWindowWriters[idx] }?.flush()
            }
        } catch (_: Exception) {
            // Disk write failure is non-fatal
        }
    }

    private fun trimIfNeeded() {
        if (lineCount <= maxLines) return

        val excessLines = lineCount - maxLines
        var trimPos = 0
        var linesFound = 0

        val plain = plainContent.toString()
        for (i in plain.indices) {
            if (plain[i] == '\n') {
                linesFound++
                if (linesFound >= excessLines) {
                    trimPos = i + 1
                    break
                }
            }
        }

        if (trimPos > 0 && trimPos < plainContent.length) {
            plainContent.delete(0, trimPos)
            val styledTrimPos = minOf(trimPos, styledContent.length)
            if (styledTrimPos > 0) {
                styledContent.delete(0, styledTrimPos)
            }
            lineCount -= excessLines
        }
    }
}
