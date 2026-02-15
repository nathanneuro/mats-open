package com.claudessh.app.terminal

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/**
 * Parses ANSI escape sequences and produces styled SpannableStringBuilder output.
 * Handles SGR (Select Graphic Rendition) codes for colors and text styling.
 * Strips cursor movement and other control sequences for the history view.
 */
class AnsiParser {

    private var currentFg: Int? = null
    private var currentBg: Int? = null
    private var bold = false
    private var underline = false
    private var italic = false

    /** Number of lines to delete from the end of existing output before appending. */
    var pendingDeleteLines = 0
        private set

    /** If true, content in this chunk should replace backward (cursor-up was seen). */
    var hasBackwardMovement = false
        private set

    // Standard 8 ANSI colors
    private val ansiColors = intArrayOf(
        Color.parseColor("#000000"), // 0 Black
        Color.parseColor("#CC0000"), // 1 Red
        Color.parseColor("#4E9A06"), // 2 Green
        Color.parseColor("#C4A000"), // 3 Yellow
        Color.parseColor("#3465A4"), // 4 Blue
        Color.parseColor("#75507B"), // 5 Magenta
        Color.parseColor("#06989A"), // 6 Cyan
        Color.parseColor("#D3D7CF"), // 7 White
    )

    // Bright variants
    private val ansiBrightColors = intArrayOf(
        Color.parseColor("#555753"), // 0 Bright Black
        Color.parseColor("#EF2929"), // 1 Bright Red
        Color.parseColor("#8AE234"), // 2 Bright Green
        Color.parseColor("#FCE94F"), // 3 Bright Yellow
        Color.parseColor("#729FCF"), // 4 Bright Blue
        Color.parseColor("#AD7FA8"), // 5 Bright Magenta
        Color.parseColor("#34E2E2"), // 6 Bright Cyan
        Color.parseColor("#EEEEEC"), // 7 Bright White
    )

    /**
     * Parse raw terminal output into a SpannableStringBuilder with ANSI colors applied.
     * Strips control sequences that aren't SGR (color/style).
     */
    fun parse(raw: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        pendingDeleteLines = 0
        hasBackwardMovement = false
        var i = 0

        while (i < raw.length) {
            when {
                // ESC sequence
                raw[i] == '\u001b' && i + 1 < raw.length -> {
                    when (raw.getOrNull(i + 1)) {
                        '[' -> {
                            // CSI sequence - find the end
                            val seqStart = i
                            i += 2
                            val params = StringBuilder()
                            while (i < raw.length && raw[i] in '\u0020'..'\u003f') {
                                params.append(raw[i])
                                i++
                            }
                            if (i < raw.length) {
                                val finalByte = raw[i]
                                i++
                                when (finalByte) {
                                    'm' -> handleSgr(params.toString())
                                    // Cursor down / next line → emit newlines
                                    // Claude Code uses these to separate prompt options
                                    'B', 'E' -> {
                                        val count = params.toString().toIntOrNull() ?: 1
                                        for (j in 0 until count) {
                                            val startPos = builder.length
                                            builder.append('\n')
                                            applyCurrentStyle(builder, startPos)
                                        }
                                    }
                                    // Cursor up → signal backward movement for in-place replacement
                                    'A' -> {
                                        hasBackwardMovement = true
                                    }
                                    // Strip other cursor/erase/scroll sequences
                                    'C', 'D', 'F', 'G', 'H', 'J', 'K',
                                    'S', 'T', 'f', 'r', 's', 'u', 'n', 'l', 'h' -> {
                                        // Stripped - these control terminal state, not text content
                                    }
                                    else -> {
                                        // Unknown CSI - strip it
                                    }
                                }
                            }
                        }
                        ']' -> {
                            // OSC sequence (e.g., window title) - skip until ST or BEL
                            i += 2
                            while (i < raw.length) {
                                if (raw[i] == '\u0007') { // BEL
                                    i++
                                    break
                                }
                                if (raw[i] == '\u001b' && raw.getOrNull(i + 1) == '\\') { // ST
                                    i += 2
                                    break
                                }
                                i++
                            }
                        }
                        '(' , ')' -> {
                            // Character set designation - skip
                            i += 3
                        }
                        else -> {
                            i += 2
                        }
                    }
                }
                // Carriage return - used with \n for line endings, strip standalone CR
                raw[i] == '\r' -> {
                    i++
                    // Only add if not followed by \n (CR+LF -> just \n)
                    if (i >= raw.length || raw[i] != '\n') {
                        // Standalone CR - just skip (overwrites current line in real terminal)
                    }
                }
                // Backspace
                raw[i] == '\b' -> {
                    // Remove last character if present
                    if (builder.isNotEmpty()) {
                        builder.delete(builder.length - 1, builder.length)
                    }
                    i++
                }
                // BEL
                raw[i] == '\u0007' -> {
                    i++
                }
                // Regular printable character or newline/tab
                else -> {
                    val startPos = builder.length
                    builder.append(raw[i])
                    applyCurrentStyle(builder, startPos)
                    i++
                }
            }
        }

        return builder
    }

    /**
     * Strip all ANSI escape codes and return plain text.
     * Used for saving to history files.
     */
    fun stripAnsi(raw: String): String {
        return raw.replace(Regex("\u001b\\[[0-9;]*[a-zA-Z]"), "")
            .replace(Regex("\u001b\\][^\u0007]*\u0007"), "")
            .replace(Regex("\u001b[()][A-Z0-9]"), "")
            .replace("\r\n", "\n")
            .replace("\r", "")
            .replace("\u0007", "")
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
                in 30..37 -> currentFg = ansiColors[code - 30]
                in 40..47 -> currentBg = ansiColors[code - 40]
                39 -> currentFg = null
                49 -> currentBg = null
                in 90..97 -> currentFg = ansiBrightColors[code - 90]
                in 100..107 -> currentBg = ansiBrightColors[code - 100]
                38 -> {
                    // Extended foreground: 38;5;N (256-color) or 38;2;R;G;B (truecolor)
                    if (i + 1 < codes.size && codes[i + 1] == 5 && i + 2 < codes.size) {
                        currentFg = get256Color(codes[i + 2])
                        i += 2
                    } else if (i + 1 < codes.size && codes[i + 1] == 2 && i + 4 < codes.size) {
                        currentFg = Color.rgb(codes[i + 2], codes[i + 3], codes[i + 4])
                        i += 4
                    }
                }
                48 -> {
                    // Extended background: 48;5;N or 48;2;R;G;B
                    if (i + 1 < codes.size && codes[i + 1] == 5 && i + 2 < codes.size) {
                        currentBg = get256Color(codes[i + 2])
                        i += 2
                    } else if (i + 1 < codes.size && codes[i + 1] == 2 && i + 4 < codes.size) {
                        currentBg = Color.rgb(codes[i + 2], codes[i + 3], codes[i + 4])
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
                // 216 color cube: 16 + 36*r + 6*g + b (r,g,b in 0..5)
                val adjusted = index - 16
                val r = (adjusted / 36) * 51
                val g = ((adjusted % 36) / 6) * 51
                val b = (adjusted % 6) * 51
                Color.rgb(r, g, b)
            }
            else -> {
                // Grayscale: 232-255 -> 8, 18, ..., 238
                val gray = 8 + (index - 232) * 10
                Color.rgb(gray, gray, gray)
            }
        }
    }

    private fun applyCurrentStyle(builder: SpannableStringBuilder, startPos: Int) {
        val endPos = builder.length
        if (startPos >= endPos) return

        currentFg?.let {
            builder.setSpan(ForegroundColorSpan(it), startPos, endPos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        currentBg?.let {
            builder.setSpan(BackgroundColorSpan(it), startPos, endPos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (bold && italic) {
            builder.setSpan(StyleSpan(Typeface.BOLD_ITALIC), startPos, endPos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (bold) {
            builder.setSpan(StyleSpan(Typeface.BOLD), startPos, endPos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (italic) {
            builder.setSpan(StyleSpan(Typeface.ITALIC), startPos, endPos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (underline) {
            builder.setSpan(UnderlineSpan(), startPos, endPos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun resetStyle() {
        currentFg = null
        currentBg = null
        bold = false
        underline = false
        italic = false
    }

    fun reset() {
        resetStyle()
    }
}
