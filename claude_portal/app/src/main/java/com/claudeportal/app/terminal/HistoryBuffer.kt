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
 * Disk model: append-only per-window log files. Files are NEVER bulk-read
 * into memory — long sessions can produce files much larger than RAM.
 * All reads are bounded: a small tail for the initial replay after
 * reconnect, and chunked reads (~50 KB) for scroll-back paging.
 *
 * Memory model: a deduped sliding window of recent lines per tmux index.
 * Incoming lines apply newest-wins dedup against this in-memory ring; only
 * lines that are genuinely new (not present in the ring) get queued to
 * disk. Lines whose key already lived in the ring move to its tail and
 * are not re-written. Pure-decoration table rows are dropped before they
 * reach memory or disk. The ring is capped — older content rolls off the
 * front, and if the same key reappears later it will be written to disk
 * again. That residual disk duplication is filtered at scroll-back time
 * by chunk-vs-current dedup in TerminalView.
 *
 * Each window also has an in-memory dirty (raw, un-deduplicated) buffer
 * for the broom toggle's fallback view. Dirty buffers are not persisted.
 *
 * Pending-switch routing: tmux button presses call beginPendingSwitch()
 * before the SSH command goes out. Output that arrives in the gap between
 * button and the next status-bar parse is held in a buffer and flushed
 * into the new window's storage when commitPendingSwitch() lands.
 */
class HistoryBuffer(private val maxLines: Int = 1000) {

    companion object {
        // Files older than this in the history dir are purged on connect.
        private const val MAX_FILE_AGE_MS = 2L * 24 * 60 * 60 * 1000
        // In-memory deduped ring per window. Older lines roll off.
        private const val MAX_LINES_PER_WINDOW = 10_000
        // Tail-read cap for the initial replay after reconnect — small
        // enough to never threaten the heap on huge legacy files.
        private const val INITIAL_REPLAY_TAIL_BYTES = 64L * 1024
        // Chunk size for scroll-back paging. Small, fixed.
        private const val SCROLLBACK_CHUNK_BYTES = 32 * 1024
    }

    private val lock = ReentrantReadWriteLock()
    private val styledContent = SpannableStringBuilder()
    private val plainContent = StringBuilder()
    private var lineCount = 0

    private var historyDir: File? = null
    private var connectionName: String? = null
    private var activeWindowIndex: Int = -1

    // Per-window in-memory deduped ring + key index for O(1) lookup.
    private val windowLines = mutableMapOf<Int, MutableList<String>>()
    private val windowKeyIndex = mutableMapOf<Int, HashMap<String, Int>>()

    // Per-window append-only file writer. Disk is the long-term log; the
    // ring is the live render source.
    private val windowWriters = mutableMapOf<Int, FileWriter>()
    private val windowFiles = mutableMapOf<Int, File>()

    // Per-window in-memory dirty (raw) buffers — broom toggle.
    private val dirtyBuffers = mutableMapOf<Int, StringBuilder>()
    private val DIRTY_MAX_CHARS = 60_000
    private val DIRTY_TRIM_TO = 40_000

    // (windowIdx, text) pairs queued for the disk-writer coroutine. Only
    // genuinely-new (post-dedup) lines land here.
    private val pendingWrites = ConcurrentLinkedQueue<Pair<Int, String>>()
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var writeJob: Job? = null

    private data class PendingSwitch(
        val cleanBuf: StringBuilder = StringBuilder(),
        val dirtyBuf: StringBuilder = StringBuilder(),
        val startedAt: Long = System.currentTimeMillis()
    )
    private var pendingSwitch: PendingSwitch? = null
    private val PENDING_TIMEOUT_MS = 2500L

    fun setConnection(historyDir: File, connectionName: String) {
        lock.write {
            if (this.connectionName == connectionName && this.historyDir == historyDir) return
            closeWriters()
            windowLines.clear()
            windowKeyIndex.clear()
            windowFiles.clear()
            dirtyBuffers.clear()
            this.historyDir = historyDir
            this.connectionName = connectionName
            activeWindowIndex = -1
            pendingSwitch = null
        }
        purgeOldFiles(historyDir)
        ensureWindow(-1)
        startDiskWriter()
    }

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

    fun setActiveWindow(index: Int) {
        ensureWindow(index)
        val pending = lock.write {
            val p = pendingSwitch
            pendingSwitch = null
            activeWindowIndex = index
            p
        }
        if (pending != null) {
            val clean = pending.cleanBuf.toString()
            val dirty = pending.dirtyBuf.toString()
            if (clean.isNotEmpty()) ingestLines(index, clean)
            if (dirty.isNotEmpty()) appendToDirtyBuf(index, dirty)
        }
    }

    fun beginPendingSwitch() {
        lock.write { pendingSwitch = PendingSwitch() }
    }

    private fun checkPendingTimeout() {
        val expired = lock.read {
            val p = pendingSwitch ?: return
            System.currentTimeMillis() - p.startedAt > PENDING_TIMEOUT_MS
        }
        if (expired) {
            val pending = lock.write {
                val p = pendingSwitch
                pendingSwitch = null
                p
            } ?: return
            val idx = activeWindowIndex
            val clean = pending.cleanBuf.toString()
            val dirty = pending.dirtyBuf.toString()
            if (clean.isNotEmpty()) ingestLines(idx, clean)
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

    /** Allocate per-window storage. Opens a FileWriter in append mode and
     *  initialises an empty in-memory ring. The file is NEVER read whole
     *  here — this constructor path is bounded and fast regardless of how
     *  large the on-disk log is. The ring repopulates from a small tail
     *  on demand via readWindowClean(). */
    private fun ensureWindow(windowIndex: Int) {
        lock.write {
            val dir = historyDir ?: return
            val name = connectionName ?: return
            val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            if (windowLines.containsKey(windowIndex)) return
            val file = File(dir, "${safeName}_w${windowIndex}.txt")
            windowFiles[windowIndex] = file
            windowLines[windowIndex] = mutableListOf()
            windowKeyIndex[windowIndex] = HashMap()
            try {
                windowWriters[windowIndex] = FileWriter(file, true)
            } catch (_: Exception) {
            }
        }
    }

    /** Insert a single line into the in-memory ring with newest-wins dedup.
     *  Returns true iff this line was *not* already present under the same
     *  key — i.e. the caller should write it to the disk log. Caller holds
     *  the write lock. */
    private fun appendDedupLineUnlocked(
        lines: MutableList<String>,
        keyIdx: HashMap<String, Int>,
        line: String
    ): Boolean {
        if (line.isBlank()) {
            // Collapse runs of consecutive blank/whitespace-only lines to one.
            if (lines.isNotEmpty() && lines.last().isBlank()) return false
            lines.add(line)
            return true
        }
        // Symbol-only lines (no letters/digits) carry no content — drop.
        if (LineDedup.isSymbolOnly(line)) return false
        val key = LineDedup.keyFor(line)
        if (key != null) {
            val oldIdx = keyIdx[key]
            if (oldIdx != null && oldIdx in lines.indices) {
                // Already present — newest wins, but no disk write needed
                // since the disk log already has a copy. Move to end of
                // ring so it's recognised as recent.
                lines.removeAt(oldIdx)
                val it = keyIdx.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    if (e.value == oldIdx) it.remove()
                    else if (e.value > oldIdx) e.setValue(e.value - 1)
                }
                lines.add(line)
                keyIdx[key] = lines.size - 1
                trimRingHead(lines, keyIdx)
                return false
            }
        }
        lines.add(line)
        if (key != null) keyIdx[key] = lines.size - 1
        trimRingHead(lines, keyIdx)
        return true
    }

    private fun trimRingHead(lines: MutableList<String>, keyIdx: HashMap<String, Int>) {
        while (lines.size > MAX_LINES_PER_WINDOW) {
            val dropped = lines.removeAt(0)
            val droppedKey = LineDedup.keyFor(dropped)
            if (droppedKey != null && keyIdx[droppedKey] == 0) keyIdx.remove(droppedKey)
            val it = keyIdx.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                e.setValue(e.value - 1)
            }
        }
    }

    /** Split a text blob on newlines and feed each line through the dedup
     *  ring. Lines that come back as "new" are queued for the disk log. */
    private fun ingestLines(windowIndex: Int, text: String) {
        if (text.isEmpty()) return
        ensureWindow(windowIndex)
        val toWrite = StringBuilder()
        lock.write {
            val lines = windowLines[windowIndex] ?: return@write
            val keyIdx = windowKeyIndex[windowIndex] ?: return@write
            val parts = text.split('\n')
            // A trailing terminator '\n' yields a final empty string — drop
            // that artifact so it doesn't push a synthetic blank onto the ring.
            val end = if (parts.isNotEmpty() && parts.last().isEmpty()) parts.size - 1 else parts.size
            for (i in 0 until end) {
                val line = parts[i]
                if (appendDedupLineUnlocked(lines, keyIdx, line)) {
                    toWrite.append(line).append('\n')
                }
            }
        }
        if (toWrite.isNotEmpty()) {
            pendingWrites.add(windowIndex to toWrite.toString())
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
        }
        val pendingBuf = lock.read { pendingSwitch?.cleanBuf }
        if (pendingBuf != null) {
            lock.write { pendingBuf.append(text) }
            return
        }
        ingestLines(activeWindowIndex, text)
    }

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

    /** Return text suitable for re-rendering the active window. If the
     *  in-memory ring already has content (live session), join it. Else
     *  read a small tail (≤ INITIAL_REPLAY_TAIL_BYTES) from disk so the
     *  user sees recent prior history after reconnect. The disk log is
     *  never read whole. */
    fun readWindowClean(windowIndex: Int = activeWindowIndex, maxChars: Int = 200_000): String {
        val cached = lock.read {
            val lines = windowLines[windowIndex] ?: return@read null
            if (lines.isEmpty()) null else lines.joinToString("\n")
        }
        if (cached != null) {
            return if (cached.length > maxChars) cached.substring(cached.length - maxChars) else cached
        }
        val file = lock.read { windowFiles[windowIndex] } ?: return ""
        if (!file.exists() || file.length() == 0L) return ""
        // Bounded tail read — never bulk-load.
        flushPendingWrites()
        val tail = try {
            readTailBytes(file, INITIAL_REPLAY_TAIL_BYTES)
        } catch (_: Exception) {
            return ""
        }
        // Dedup happens as this chunk transitions from disk into memory:
        // the tail goes through the ring (newest-wins, drops decoration,
        // collapses duplicates) before we surface it to the caller.
        seedRingFromTail(windowIndex, tail)
        val seeded = lock.read {
            val lines = windowLines[windowIndex] ?: return@read null
            if (lines.isEmpty()) null else lines.joinToString("\n")
        } ?: return ""
        return if (seeded.length > maxChars) seeded.substring(seeded.length - maxChars) else seeded
    }

    private fun seedRingFromTail(windowIndex: Int, tail: String) {
        if (tail.isEmpty()) return
        lock.write {
            val lines = windowLines[windowIndex] ?: return@write
            val keyIdx = windowKeyIndex[windowIndex] ?: return@write
            if (lines.isNotEmpty()) return@write  // someone won the race
            tail.split('\n').forEach { line ->
                if (LineDedup.isPureTableDecoration(line)) return@forEach
                appendDedupLineUnlocked(lines, keyIdx, line)
            }
        }
    }

    /** Snapshot the current ring per window — small, in-memory only. */
    fun snapshotAllWindowTails(maxChars: Int = 8_000): Map<Int, String> {
        val out = HashMap<Int, String>()
        lock.read {
            for ((idx, lines) in windowLines) {
                val text = lines.joinToString("\n")
                val tail = if (text.length > maxChars) text.substring(text.length - maxChars) else text
                out[idx] = tail
            }
        }
        return out
    }

    fun snapshotAllDirtyBuffers(): Map<Int, String> = lock.read {
        dirtyBuffers.mapValues { it.value.toString() }
    }

    fun readWindowDirty(windowIndex: Int = activeWindowIndex, maxChars: Int = DIRTY_MAX_CHARS): String {
        val text = lock.read { dirtyBuffers[windowIndex]?.toString() } ?: return ""
        return if (text.length > maxChars) text.substring(text.length - maxChars) else text
    }

    fun activeWindow(): Int = activeWindowIndex

    /**
     * Read a small chunk of older content from the on-disk log for
     * scroll-back paging. Reads at most ~chunkBytes bytes, ending at
     * `length - skipFromEndBytes` and going chunkBytes earlier. Returns
     * null when the offset is past the start of the file. The chunk is
     * returned raw; the caller is expected to dedup against current view
     * content with LineDedup.dedupChunkAgainstKnown.
     *
     * This is the only path that touches disk for older content, and it
     * only ever reads `chunkBytes` worth at a time.
     */
    fun readWindowCleanChunk(
        windowIndex: Int = activeWindowIndex,
        skipFromEndBytes: Long,
        chunkBytes: Int = SCROLLBACK_CHUNK_BYTES
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
                val raw = String(buf, 0, off, Charsets.UTF_8)
                if (startOffset > 0L) {
                    val firstNl = raw.indexOf('\n')
                    if (firstNl >= 0 && firstNl < raw.length - 1) raw.substring(firstNl + 1) else raw
                } else raw
            }
        } catch (_: Exception) {
            null
        }
    }

    fun loadOlderContent(maxChars: Int = 100_000): String? {
        // Compatibility shim: reads a single bounded chunk from disk.
        // Callers use readWindowCleanChunk instead — keep this only for
        // anything that still references it.
        return readWindowCleanChunk(skipFromEndBytes = 0, chunkBytes = maxChars)
    }

    fun clear() = lock.write {
        styledContent.clear()
        plainContent.clear()
        lineCount = 0
    }

    /**
     * Truncate the active window's history. Used when a non-tmux shell
     * context transitions into tmux so pre-tmux output doesn't blend with
     * the new window 0.
     */
    fun resetActiveWindow() {
        val idx = activeWindowIndex
        // Drop pending writes destined for this window so they don't land
        // after the truncate.
        val keep = ArrayList<Pair<Int, String>>()
        while (true) {
            val p = pendingWrites.poll() ?: break
            if (p.first != idx) keep.add(p)
        }
        keep.forEach { pendingWrites.add(it) }
        lock.write {
            windowLines[idx]?.clear()
            windowKeyIndex[idx]?.clear()
            dirtyBuffers[idx]?.clear()
            try { windowWriters[idx]?.close() } catch (_: Exception) {}
            windowWriters.remove(idx)
            windowFiles[idx]?.let { f ->
                try { f.delete() } catch (_: Exception) {}
            }
            windowFiles.remove(idx)
            styledContent.clear()
            plainContent.clear()
            lineCount = 0
        }
        ensureWindow(idx)
    }

    fun close() {
        writeJob?.cancel()
        writeScope.cancel()
        flushPendingWrites()
        lock.write { closeWriters() }
    }

    private fun closeWriters() {
        for (w in windowWriters.values) {
            try { w.close() } catch (_: Exception) {}
        }
        windowWriters.clear()
    }

    private fun startDiskWriter() {
        writeJob?.cancel()
        writeJob = writeScope.launch {
            while (isActive) {
                flushPendingWrites()
                checkPendingTimeout()
                delay(250)
            }
        }
    }

    private fun flushPendingWrites() {
        try {
            val touched = mutableSetOf<Int>()
            while (true) {
                val pair = pendingWrites.poll() ?: break
                val (idx, text) = pair
                val writer = lock.read { windowWriters[idx] } ?: continue
                writer.write(text)
                touched.add(idx)
            }
            for (idx in touched) {
                lock.read { windowWriters[idx] }?.flush()
            }
        } catch (_: Exception) {
            // Disk write failure is non-fatal.
        }
    }

    /** Read at most `maxBytes` from the end of the file. Drops any leading
     *  partial line. Used only by initial replay, never by live reads. */
    private fun readTailBytes(file: File, maxBytes: Long): String {
        val length = file.length()
        val skip = (length - maxBytes).coerceAtLeast(0L)
        return file.inputStream().use { fis ->
            var remaining = skip
            while (remaining > 0) {
                val s = fis.skip(remaining)
                if (s <= 0) break
                remaining -= s
            }
            val readBytes = (length - skip).coerceAtMost(maxBytes).toInt()
            val buf = ByteArray(readBytes)
            var off = 0
            while (off < readBytes) {
                val r = fis.read(buf, off, readBytes - off)
                if (r <= 0) break
                off += r
            }
            val raw = String(buf, 0, off, Charsets.UTF_8)
            if (skip > 0L) {
                val nl = raw.indexOf('\n')
                if (nl >= 0 && nl < raw.length - 1) raw.substring(nl + 1) else raw
            } else raw
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
