package com.claudessh.app.terminal

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
 * Files persist across reconnects to the same server.
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
    private val pendingWrites = ConcurrentLinkedQueue<Pair<Int, String>>()
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var writeJob: Job? = null

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
        }
        ensureWriter(0)
        startDiskWriter()
    }

    /**
     * Switch which tmux window receives disk writes.
     * Creates the file/writer on demand if this window hasn't been seen before.
     */
    fun setActiveWindow(index: Int) {
        activeWindowIndex = index
        ensureWriter(index)
    }

    private fun ensureWriter(windowIndex: Int) {
        lock.write {
            if (windowWriters.containsKey(windowIndex)) return
            val dir = historyDir ?: return
            val name = connectionName ?: return
            val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val file = File(dir, "${safeName}_w${windowIndex}.txt")
            windowFiles[windowIndex] = file
            windowWriters[windowIndex] = FileWriter(file, true) // append mode
        }
    }

    fun appendStyled(text: SpannableStringBuilder) = lock.write {
        styledContent.append(text)
        trimIfNeeded()
    }

    fun appendPlain(text: String) {
        val windowIdx = activeWindowIndex
        lock.write {
            plainContent.append(text)
            lineCount += text.count { it == '\n' }
        }
        pendingWrites.add(windowIdx to text)
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
     * Load older content from the active window's disk file.
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
        windowWriters.clear()
        windowFiles.clear()
    }

    private fun startDiskWriter() {
        writeJob?.cancel()
        writeJob = writeScope.launch {
            while (isActive) {
                flushPendingWrites()
                delay(500)
            }
        }
    }

    private fun flushPendingWrites() {
        try {
            val flushed = mutableSetOf<Int>()
            while (true) {
                val (windowIdx, text) = pendingWrites.poll() ?: break
                val writer = lock.read { windowWriters[windowIdx] } ?: continue
                writer.write(text)
                flushed.add(windowIdx)
            }
            for (idx in flushed) {
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
