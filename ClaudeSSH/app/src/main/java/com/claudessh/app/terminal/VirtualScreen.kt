package com.claudessh.app.terminal

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.graphics.Typeface

/**
 * An 80×24 grid of styled character cells representing the terminal screen state.
 * Used by AnsiScreenInterpreter to faithfully track what the remote terminal looks like.
 */
class VirtualScreen(val cols: Int = 80, val rows: Int = 24) {

    data class Cell(
        var char: Char = ' ',
        var fg: Int? = null,
        var bg: Int? = null,
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false
    ) {
        fun copyFrom(other: Cell) {
            char = other.char
            fg = other.fg
            bg = other.bg
            bold = other.bold
            italic = other.italic
            underline = other.underline
        }

        fun clear() {
            char = ' '
            fg = null
            bg = null
            bold = false
            italic = false
            underline = false
        }

        fun contentEquals(other: Cell): Boolean =
            char == other.char && fg == other.fg && bg == other.bg &&
                bold == other.bold && italic == other.italic && underline == other.underline
    }

    val cells: Array<Array<Cell>> = Array(rows) { Array(cols) { Cell() } }
    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set

    fun moveCursorTo(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
    }

    fun moveCursorUp(n: Int = 1) {
        cursorRow = (cursorRow - n).coerceIn(0, rows - 1)
    }

    fun moveCursorDown(n: Int = 1) {
        cursorRow = (cursorRow + n).coerceIn(0, rows - 1)
    }

    fun moveCursorForward(n: Int = 1) {
        cursorCol = (cursorCol + n).coerceIn(0, cols - 1)
    }

    fun moveCursorBackward(n: Int = 1) {
        cursorCol = (cursorCol - n).coerceIn(0, cols - 1)
    }

    fun carriageReturn() {
        cursorCol = 0
    }

    fun lineFeed() {
        if (cursorRow < rows - 1) {
            cursorRow++
        } else {
            scrollUp()
        }
    }

    /** Write a character at cursor position with given style, advance cursor. */
    fun writeChar(ch: Char, fg: Int?, bg: Int?, bold: Boolean, italic: Boolean, underline: Boolean) {
        if (cursorCol >= cols) {
            // Wrap to next line
            cursorCol = 0
            lineFeed()
        }
        val cell = cells[cursorRow][cursorCol]
        cell.char = ch
        cell.fg = fg
        cell.bg = bg
        cell.bold = bold
        cell.italic = italic
        cell.underline = underline
        cursorCol++
    }

    /** Erase from cursor to end of line. */
    fun eraseToEndOfLine() {
        for (c in cursorCol until cols) {
            cells[cursorRow][c].clear()
        }
    }

    /** Erase from start of line to cursor. */
    fun eraseToStartOfLine() {
        for (c in 0..cursorCol.coerceAtMost(cols - 1)) {
            cells[cursorRow][c].clear()
        }
    }

    /** Erase entire line. */
    fun eraseEntireLine() {
        for (c in 0 until cols) {
            cells[cursorRow][c].clear()
        }
    }

    /** Erase from cursor to end of screen. */
    fun eraseToEndOfScreen() {
        eraseToEndOfLine()
        for (r in (cursorRow + 1) until rows) {
            for (c in 0 until cols) {
                cells[r][c].clear()
            }
        }
    }

    /** Erase from start of screen to cursor. */
    fun eraseToStartOfScreen() {
        eraseToStartOfLine()
        for (r in 0 until cursorRow) {
            for (c in 0 until cols) {
                cells[r][c].clear()
            }
        }
    }

    /** Erase entire screen. */
    fun eraseEntireScreen() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                cells[r][c].clear()
            }
        }
    }

    /** Scroll all lines up by one, bottom line becomes blank. */
    fun scrollUp() {
        val topRow = cells[0]
        for (r in 0 until rows - 1) {
            cells[r] = cells[r + 1]
        }
        cells[rows - 1] = topRow
        for (c in 0 until cols) {
            cells[rows - 1][c].clear()
        }
    }

    /** Get the text content of a row (trailing spaces trimmed). */
    fun getRowText(row: Int): String {
        val sb = StringBuilder(cols)
        for (c in 0 until cols) {
            sb.append(cells[row][c].char)
        }
        return sb.toString().trimEnd()
    }

    /** Get all rows as text lines (trailing spaces trimmed per line). */
    fun getTextLines(): List<String> {
        return (0 until rows).map { getRowText(it) }
    }

    /** Get all rows as text, trimming trailing blank lines. */
    fun getVisibleTextLines(): List<String> {
        val lines = getTextLines()
        val lastNonBlank = lines.indexOfLast { it.isNotBlank() }
        return if (lastNonBlank >= 0) lines.subList(0, lastNonBlank + 1) else emptyList()
    }

    /** Convert a row to a styled SpannableStringBuilder. */
    fun getRowStyled(row: Int): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val rowCells = cells[row]

        // Find the last non-space cell to avoid trailing spaces
        var lastNonSpace = cols - 1
        while (lastNonSpace >= 0 && rowCells[lastNonSpace].char == ' ' &&
            rowCells[lastNonSpace].bg == null) {
            lastNonSpace--
        }

        for (c in 0..lastNonSpace) {
            val cell = rowCells[c]
            val start = builder.length
            builder.append(cell.char)

            cell.fg?.let {
                builder.setSpan(ForegroundColorSpan(it), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            cell.bg?.let {
                builder.setSpan(BackgroundColorSpan(it), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (cell.bold && cell.italic) {
                builder.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (cell.bold) {
                builder.setSpan(StyleSpan(Typeface.BOLD), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (cell.italic) {
                builder.setSpan(StyleSpan(Typeface.ITALIC), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (cell.underline) {
                builder.setSpan(UnderlineSpan(), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        return builder
    }

    /** Deep copy of this screen's cell data for diffing. */
    fun snapshot(): Array<Array<Cell>> {
        return Array(rows) { r ->
            Array(cols) { c ->
                Cell().also { it.copyFrom(cells[r][c]) }
            }
        }
    }

    /** Check if two row arrays have the same content. */
    companion object {
        fun rowsEqual(a: Array<Cell>, b: Array<Cell>): Boolean {
            if (a.size != b.size) return false
            for (i in a.indices) {
                if (!a[i].contentEquals(b[i])) return false
            }
            return true
        }
    }
}
