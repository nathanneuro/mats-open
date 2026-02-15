package com.claudessh.app.terminal

import android.graphics.Color

/**
 * Cursor-aware ANSI interpreter that writes to a VirtualScreen.
 *
 * Unlike AnsiParser (which strips cursor sequences for a flat text view),
 * this faithfully interprets all cursor movement, erase, and SGR sequences
 * to maintain an accurate screen state. This is what lets us diff screens
 * and extract only genuinely new content.
 */
class AnsiScreenInterpreter(private val screen: VirtualScreen) {

    // Current SGR state
    private var fg: Int? = null
    private var bg: Int? = null
    private var bold = false
    private var italic = false
    private var underline = false

    // Escape sequence buffer for incomplete sequences across chunk boundaries
    private var escBuffer = StringBuilder()
    private var inEscape = false
    private var inOsc = false

    // Standard ANSI colors
    private val ansiColors = intArrayOf(
        Color.parseColor("#000000"), Color.parseColor("#CC0000"),
        Color.parseColor("#4E9A06"), Color.parseColor("#C4A000"),
        Color.parseColor("#3465A4"), Color.parseColor("#75507B"),
        Color.parseColor("#06989A"), Color.parseColor("#D3D7CF"),
    )
    private val ansiBrightColors = intArrayOf(
        Color.parseColor("#555753"), Color.parseColor("#EF2929"),
        Color.parseColor("#8AE234"), Color.parseColor("#FCE94F"),
        Color.parseColor("#729FCF"), Color.parseColor("#AD7FA8"),
        Color.parseColor("#34E2E2"), Color.parseColor("#EEEEEC"),
    )

    /**
     * Feed raw SSH output into the interpreter.
     * Returns any leftover bytes that form an incomplete escape sequence
     * (they'll be buffered internally and prepended to the next call).
     */
    fun feed(raw: String) {
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]

            if (inOsc) {
                escBuffer.append(ch)
                // OSC ends with BEL or ST (ESC \)
                if (ch == '\u0007') {
                    // OSC complete, discard
                    inOsc = false
                    inEscape = false
                    escBuffer.clear()
                } else if (ch == '\\' && escBuffer.length >= 2 &&
                    escBuffer[escBuffer.length - 2] == '\u001b') {
                    // ST = ESC \
                    inOsc = false
                    inEscape = false
                    escBuffer.clear()
                }
                i++
                continue
            }

            if (inEscape) {
                escBuffer.append(ch)
                val seq = escBuffer.toString()

                if (seq.length == 1) {
                    // Just got the byte after ESC
                    when (ch) {
                        '[' -> { /* CSI - continue collecting */ }
                        ']' -> { inOsc = true }
                        '(' , ')' -> { /* charset designation - need one more byte */ }
                        else -> {
                            // Unknown short escape, discard
                            inEscape = false
                            escBuffer.clear()
                        }
                    }
                } else if (seq.startsWith("[")) {
                    // CSI sequence: ESC [ <params> <final byte>
                    if (ch in '@'..'~') {
                        // Final byte - sequence is complete
                        handleCsi(seq.substring(1, seq.length - 1), ch)
                        inEscape = false
                        escBuffer.clear()
                    }
                    // Otherwise keep collecting parameter/intermediate bytes
                } else if (seq.length == 2 && (seq[0] == '(' || seq[0] == ')')) {
                    // Charset designation complete (e.g. ESC ( B), discard
                    inEscape = false
                    escBuffer.clear()
                }
                i++
                continue
            }

            // Normal character processing
            when (ch) {
                '\u001b' -> {
                    inEscape = true
                    escBuffer.clear()
                }
                '\r' -> screen.carriageReturn()
                '\n' -> screen.lineFeed()
                '\b' -> screen.moveCursorBackward(1)
                '\t' -> {
                    // Tab: advance to next 8-col boundary
                    val nextTab = ((screen.cursorCol / 8) + 1) * 8
                    screen.moveCursorTo(screen.cursorRow, nextTab.coerceAtMost(screen.cols - 1))
                }
                '\u0007' -> { /* BEL - ignore */ }
                else -> {
                    if (ch >= ' ') {
                        screen.writeChar(ch, fg, bg, bold, italic, underline)
                    }
                    // Other control chars silently ignored
                }
            }
            i++
        }
    }

    /**
     * Check if there's a buffered incomplete escape sequence.
     * The OutputProcessor uses this to know if a chunk boundary split a sequence.
     */
    fun hasIncompleteSequence(): Boolean = inEscape

    private fun handleCsi(params: String, finalByte: Char) {
        when (finalByte) {
            'm' -> handleSgr(params)
            'H', 'f' -> {
                // Cursor position: ESC[row;colH (1-based, default 1;1)
                val parts = params.split(";")
                val row = (parts.getOrNull(0)?.toIntOrNull() ?: 1) - 1
                val col = (parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1
                screen.moveCursorTo(row, col)
            }
            'A' -> {
                // Cursor up
                val n = params.toIntOrNull() ?: 1
                screen.moveCursorUp(n)
            }
            'B' -> {
                // Cursor down
                val n = params.toIntOrNull() ?: 1
                screen.moveCursorDown(n)
            }
            'C' -> {
                // Cursor forward
                val n = params.toIntOrNull() ?: 1
                screen.moveCursorForward(n)
            }
            'D' -> {
                // Cursor backward
                val n = params.toIntOrNull() ?: 1
                screen.moveCursorBackward(n)
            }
            'E' -> {
                // Cursor next line
                val n = params.toIntOrNull() ?: 1
                screen.moveCursorDown(n)
                screen.carriageReturn()
            }
            'F' -> {
                // Cursor previous line
                val n = params.toIntOrNull() ?: 1
                screen.moveCursorUp(n)
                screen.carriageReturn()
            }
            'G' -> {
                // Cursor horizontal absolute (1-based)
                val col = (params.toIntOrNull() ?: 1) - 1
                screen.moveCursorTo(screen.cursorRow, col)
            }
            'J' -> {
                // Erase display
                when (params.toIntOrNull() ?: 0) {
                    0 -> screen.eraseToEndOfScreen()
                    1 -> screen.eraseToStartOfScreen()
                    2, 3 -> screen.eraseEntireScreen()
                }
            }
            'K' -> {
                // Erase line
                when (params.toIntOrNull() ?: 0) {
                    0 -> screen.eraseToEndOfLine()
                    1 -> screen.eraseToStartOfLine()
                    2 -> screen.eraseEntireLine()
                }
            }
            'X' -> {
                // ECH: Erase Character — erase n chars at cursor, don't move cursor
                val n = params.toIntOrNull() ?: 1
                for (j in 0 until n) {
                    val c = screen.cursorCol + j
                    if (c < screen.cols) screen.cells[screen.cursorRow][c].clear()
                }
            }
            'S' -> {
                // SU: Scroll Up — scroll content up n lines
                val n = params.toIntOrNull() ?: 1
                repeat(n) { screen.scrollUp() }
            }
            'T' -> {
                // Scroll down - ignore
            }
            's' -> {
                // Save cursor position - ignore for now
            }
            'u' -> {
                // Restore cursor position - ignore for now
            }
            'n' -> {
                // Device status report - ignore (we can't respond)
            }
            'l', 'h' -> {
                // Set/reset mode - ignore (e.g. cursor visibility, alt screen)
            }
            'd' -> {
                // VPA: Vertical Position Absolute (1-based)
                val row = (params.toIntOrNull() ?: 1) - 1
                screen.moveCursorTo(row, screen.cursorCol)
            }
            'r' -> {
                // Set scrolling region - ignore for now
            }
        }
    }

    private fun handleSgr(params: String) {
        if (params.isEmpty()) {
            resetStyle()
            return
        }

        val codes = params.split(";").mapNotNull { it.toIntOrNull() }
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> resetStyle()
                1 -> bold = true
                3 -> italic = true
                4 -> underline = true
                22 -> bold = false
                23 -> italic = false
                24 -> underline = false
                in 30..37 -> fg = ansiColors[code - 30]
                in 40..47 -> bg = ansiColors[code - 40]
                39 -> fg = null
                49 -> bg = null
                in 90..97 -> fg = ansiBrightColors[code - 90]
                in 100..107 -> bg = ansiBrightColors[code - 100]
                38 -> {
                    if (i + 1 < codes.size && codes[i + 1] == 5 && i + 2 < codes.size) {
                        fg = get256Color(codes[i + 2])
                        i += 2
                    } else if (i + 1 < codes.size && codes[i + 1] == 2 && i + 4 < codes.size) {
                        fg = Color.rgb(codes[i + 2], codes[i + 3], codes[i + 4])
                        i += 4
                    }
                }
                48 -> {
                    if (i + 1 < codes.size && codes[i + 1] == 5 && i + 2 < codes.size) {
                        bg = get256Color(codes[i + 2])
                        i += 2
                    } else if (i + 1 < codes.size && codes[i + 1] == 2 && i + 4 < codes.size) {
                        bg = Color.rgb(codes[i + 2], codes[i + 3], codes[i + 4])
                        i += 4
                    }
                }
            }
            i++
        }
    }

    private fun get256Color(index: Int): Int {
        return when {
            index < 8 -> ansiColors[index]
            index < 16 -> ansiBrightColors[index - 8]
            index < 232 -> {
                val adjusted = index - 16
                val r = (adjusted / 36) * 51
                val g = ((adjusted % 36) / 6) * 51
                val b = (adjusted % 6) * 51
                Color.rgb(r, g, b)
            }
            else -> {
                val gray = 8 + (index - 232) * 10
                Color.rgb(gray, gray, gray)
            }
        }
    }

    private fun resetStyle() {
        fg = null
        bg = null
        bold = false
        italic = false
        underline = false
    }

    fun reset() {
        resetStyle()
        escBuffer.clear()
        inEscape = false
        inOsc = false
    }
}
