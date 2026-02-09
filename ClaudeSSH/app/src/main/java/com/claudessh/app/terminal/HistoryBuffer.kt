package com.claudessh.app.terminal

import android.text.SpannableStringBuilder
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe buffer that accumulates all terminal output for scrollable history.
 *
 * This is the core of the "scroll like a document" feature. Instead of a traditional
 * terminal scrollback that sends arrow keys, this buffer stores the complete styled
 * output. The UI renders it in a ScrollView so the user can scroll freely without
 * sending any keystrokes to the remote session.
 */
class HistoryBuffer(private val maxLines: Int = 50000) {

    private val lock = ReentrantReadWriteLock()
    private val styledContent = SpannableStringBuilder()
    private val plainContent = StringBuilder()
    private var lineCount = 0

    /**
     * Append styled text (with ANSI colors) to the history.
     */
    fun appendStyled(text: SpannableStringBuilder) = lock.write {
        styledContent.append(text)
        trimIfNeeded()
    }

    /**
     * Append plain text (for persistence to disk).
     */
    fun appendPlain(text: String) = lock.write {
        plainContent.append(text)
        lineCount += text.count { it == '\n' }
    }

    /**
     * Get the full styled content for display in the scrollable view.
     */
    fun getStyledContent(): SpannableStringBuilder = lock.read {
        SpannableStringBuilder(styledContent)
    }

    /**
     * Get plain text content for saving to disk.
     */
    fun getPlainContent(): String = lock.read {
        plainContent.toString()
    }

    /**
     * Get approximate line count.
     */
    fun getLineCount(): Int = lock.read {
        lineCount
    }

    /**
     * Get content length in characters.
     */
    fun getLength(): Int = lock.read {
        styledContent.length
    }

    fun clear() = lock.write {
        styledContent.clear()
        plainContent.clear()
        lineCount = 0
    }

    private fun trimIfNeeded() {
        if (lineCount <= maxLines) return

        // Trim oldest lines from both buffers
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
            // For styled content, find roughly the same position
            val styledTrimPos = minOf(trimPos, styledContent.length)
            if (styledTrimPos > 0) {
                styledContent.delete(0, styledTrimPos)
            }
            lineCount -= excessLines
        }
    }
}
