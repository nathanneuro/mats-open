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
class HistoryBuffer(private val maxLines: Int = 1000) {

    companion object {
        // Files older than this in the history dir are purged on connect.
        private const val MAX_FILE_AGE_MS = 2L * 24 * 60 * 60 * 1000
    }

    private val lock = ReentrantReadWriteLock()
    private val styledContent = SpannableStringBuilder()
    private val plainContent = StringBuilder()
    private var lineCount = 0

    // Per-window disk persistence (clean only — dirty stays in memory)
    private var historyDir: File? = null
    private var connectionName: String? = null
    // -1 = pre-tmux raw shell. Once the tmux status bar is parsed, this
    // switches to the real tmux window index, leaving the shell history
    // preserved in its own file ({conn}_w-1.txt).
    private var activeWindowIndex: Int = -1
    private val windowWriters = mutableMapOf<Int, FileWriter>()
    private val windowFiles = mutableMapOf<Int, File>()

    // Per-window in-memory dirty (raw, un-deduplicated) buffers. Used by the
    // broom toggle as a backup view when the dedup heuristic eats real output.
    // Bounded — no disk persistence; old text is discarded once the cap is hit.
    private val dirtyBuffers = mutableMapOf<Int, StringBuilder>()
    private val DIRTY_MAX_CHARS = 60_000
    private val DIRTY_TRIM_TO = 40_000

    // (windowIdx, text)
    private val pendingWrites = ConcurrentLinkedQueue<Pair<Int, String>>()
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
            activeWindowIndex = -1
            pendingSwitch = null
        }
        purgeOldFiles(historyDir)
        ensureWriter(-1)
        startDiskWriter()
    }

    /** Delete history files in `dir` whose mtime is older than MAX_FILE_AGE_MS,
     *  plus any leftover *_dirty.txt files (no longer persisted to disk). */
    private fun purgeOldFiles(dir: File) {
        try {
            val cutoff = System.currentTimeMillis() - MAX_FILE_AGE_MS
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                if (f.lastModified() < cutoff || f.name.endsWith("_dirty.txt")) {
                    f.delete()
                }
            }
        } catch (_: Exception) {
        }
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
            if (clean.isNotEmpty()) pendingWrites.add(index to clean)
            if (dirty.isNotEmpty()) appendToDirtyBuf(index, dirty)
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
            if (clean.isNotEmpty()) pendingWrites.add(idx to clean)
            if (dirty.isNotEmpty()) appendToDirtyBuf(idx, dirty)
        }
    }

    private fun appendToDirtyBuf(windowIndex: Int, text: String) {
        lock.write {
            val buf = dirtyBuffers.getOrPut(windowIndex) { StringBuilder() }
            buf.append(text)
            if (buf.length > DIRTY_MAX_CHARS) {
                buf.delete(0, buf.length - DIRTY_TRIM_TO)
            }
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
            pendingWrites.add(activeWindowIndex to text)
        }
    }

    /** Append raw, un-deduplicated text to the active window's in-memory
     *  dirty buffer. Not persisted to disk — old text is dropped past a cap. */
    fun appendDirtyPlain(text: String) {
        checkPendingTimeout()
        val (target, pendingBuf) = lock.read {
            val p = pendingSwitch
            if (p != null) -1 to p.dirtyBuf else activeWindowIndex to null
        }
        if (pendingBuf != null) {
            lock.write { pendingBuf.append(text) }
            return
        }
        appendToDirtyBuf(target, text)
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

    /**
     * Snapshot the tail of each known per-window clean history file.
     * Used by the W? recovery to score each file against tmux's live
     * pane capture and pick the one that actually matches reality.
     */
    fun snapshotAllWindowTails(maxChars: Int = 8_000): Map<Int, String> {
        flushPendingWrites()
        val files = lock.read { windowFiles.toMap() }
        val out = HashMap<Int, String>()
        for ((idx, file) in files) {
            try {
                if (!file.exists()) continue
                val text = readTailBounded(file, maxChars)
                val tail = if (text.length > maxChars) text.substring(text.length - maxChars) else text
                out[idx] = tail
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** Snapshot all in-memory dirty buffers — raw, un-deduplicated text per
     *  window. Used by W? to compare against the live tmux pane capture: the
     *  raw stream typically matches what tmux shows much more closely than
     *  the filtered/deduped clean file does. */
    fun snapshotAllDirtyBuffers(): Map<Int, String> = lock.read {
        dirtyBuffers.mapValues { it.value.toString() }
    }

    /** Read the in-memory dirty (un-deduplicated) buffer for the given window. */
    fun readWindowDirty(windowIndex: Int = activeWindowIndex, maxChars: Int = DIRTY_MAX_CHARS): String {
        val text = lock.read { dirtyBuffers[windowIndex]?.toString() } ?: return ""
        return if (text.length > maxChars) text.substring(text.length - maxChars) else text
    }

    fun activeWindow(): Int = activeWindowIndex

    private fun readTail(file: File, maxChars: Int): String {
        if (!file.exists()) return ""
        return try {
            // Flush pending writes to disk first so the file is up-to-date
            flushPendingWrites()
            val text = readTailBounded(file, maxChars)
            if (text.length > maxChars) text.substring(text.length - maxChars) else text
        } catch (_: Exception) {
            ""
        }
    }

    // Read at most ~maxChars characters from the end of the file without
    // loading the whole thing. UTF-8 is up to 4 bytes/char; the leading
    // partial char (if any) is dropped by the BufferedReader.
    private fun readTailBounded(file: File, maxChars: Int): String {
        val length = file.length()
        val maxBytes = maxChars.toLong() * 4L
        val skip = (length - maxBytes).coerceAtLeast(0L)
        return file.inputStream().use { fis ->
            var remaining = skip
            while (remaining > 0) {
                val s = fis.skip(remaining)
                if (s <= 0) break
                remaining -= s
            }
            fis.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    /**
     * Read a chunk of older content from the active window's clean disk file,
     * ending at byte offset `length - skipFromEndBytes` and going `chunkBytes`
     * further back. Returns null if there is nothing more to read at this
     * offset. Used to page older history into the terminal view when the
     * user scrolls to the top in history mode.
     *
     * Bytes (not chars) so the caller can advance the offset by the returned
     * String's `toByteArray(UTF_8).size` without re-decoding.
     */
    fun readWindowCleanChunk(
        windowIndex: Int = activeWindowIndex,
        skipFromEndBytes: Long,
        chunkBytes: Int = 50_000
    ): String? {
        val file = lock.read { windowFiles[windowIndex] } ?: return null
        if (!file.exists()) return null
        val length = file.length()
        if (length <= skipFromEndBytes) return null
        return try {
            flushPendingWrites()
            val endOffset = length - skipFromEndBytes
            val startOffset = (endOffset - chunkBytes).coerceAtLeast(0L)
            val readLen = (endOffset - startOffset).toInt()
            file.inputStream().use { fis ->
                var remaining = startOffset
                while (remaining > 0) {
                    val s = fis.skip(remaining)
                    if (s <= 0) break
                    remaining -= s
                }
                val buf = ByteArray(readLen)
                var off = 0
                while (off < readLen) {
                    val r = fis.read(buf, off, readLen - off)
                    if (r <= 0) break
                    off += r
                }
                // Drop the leading partial UTF-8 char if we didn't start at a boundary.
                val raw = String(buf, 0, off, Charsets.UTF_8)
                if (startOffset > 0) {
                    val firstNl = raw.indexOf('\n')
                    if (firstNl >= 0 && firstNl < raw.length - 1) raw.substring(firstNl + 1) else raw
                } else raw
            }
        } catch (_: Exception) {
            null
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
            val inMemory = lock.read { plainContent.toString() }
            // Cap read window to in-memory tail + maxChars so we don't OOM on huge files.
            val text = readTailBounded(file, inMemory.length + maxChars)
            if (text.length > maxChars) {
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

    /**
     * Truncate the active window's persisted clean file and dirty buffer.
     * Used when transitioning from a non-tmux shell into tmux: pre-tmux
     * shell output would otherwise blend with the new tmux window's
     * content (both land under window index 0).
     */
    fun resetActiveWindow() {
        val idx = activeWindowIndex
        // Drain anything queued for this window so it doesn't land after the truncate.
        val keep = ArrayList<Pair<Int, String>>()
        while (true) {
            val p = pendingWrites.poll() ?: break
            if (p.first != idx) keep.add(p)
        }
        keep.forEach { pendingWrites.add(it) }
        lock.write {
            dirtyBuffers[idx]?.clear()
            try {
                windowWriters[idx]?.close()
            } catch (_: Exception) {}
            windowWriters.remove(idx)
            windowFiles[idx]?.let { f ->
                try { f.delete() } catch (_: Exception) {}
            }
            windowFiles.remove(idx)
            styledContent.clear()
            plainContent.clear()
            lineCount = 0
        }
        ensureWriter(idx)
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
        windowWriters.clear()
        windowFiles.clear()
        dirtyBuffers.clear()
    }

    private fun startDiskWriter() {
        writeJob?.cancel()
        writeJob = writeScope.launch {
            while (isActive) {
                flushPendingWrites()
                checkPendingTimeout()
                delay(250) // Flush every 250ms for faster disk persistence
            }
        }
    }

    private fun flushPendingWrites() {
        try {
            val flushedClean = mutableSetOf<Int>()
            while (true) {
                val pair = pendingWrites.poll() ?: break
                val (windowIdx, text) = pair
                val writer = lock.read { windowWriters[windowIdx] } ?: continue
                writer.write(text)
                flushedClean.add(windowIdx)
            }
            for (idx in flushedClean) {
                lock.read { windowWriters[idx] }?.flush()
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
