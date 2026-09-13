package com.claudeportal.app.terminal

/**
 * Codex CLI UI signatures, captured from a real `codex` TUI (v0.153.x)
 * running under tmux.
 *
 * Codex differs from Claude Code in ways that matter to the portal:
 *
 *  - It does NOT use the alternate screen. The transcript is written once
 *    into a DECSTBM scroll region and scrolls away, so there is no
 *    whole-screen overdraw to dedup — only the bottom UI block (spinner
 *    row, composer, footer) is redrawn in place.
 *  - Agent output lines start with `•`, user turns and the composer with
 *    `›`. Menus mark the selected option with `›` instead of Claude's `❯`.
 *  - The working spinner is a text row, "• Working (12s • esc to
 *    interrupt)", not an animated glyph column.
 *  - The last row is a footer, "<model> · <cwd>", which is the closest
 *    equivalent of Claude's status line.
 */
object CodexUi {

    /** Bullet that prefixes Codex's own output lines. */
    const val AGENT_BULLET = '•' // •
    /** Marker for user turns, the composer, and the selected menu row. */
    const val USER_MARKER = '›' // ›

    /** Both separators Codex uses inside one-line UI chrome: • and ·. */
    private const val SEPS = "•·"

    /**
     * The working/spinner row, e.g.
     *   "• Working (12s • esc to interrupt)"
     *   "• Reviewing approval request (5s • esc to interrupt) · 1 background terminal running"
     * Group 1 is the status verb phrase shown in the thinking indicator.
     */
    val WORKING_LINE = Regex(
        "^\\s*[$SEPS]?\\s*([A-Z][A-Za-z]*(?:\\s+[A-Za-z]+){0,3})\\s*" +
            "\\((?:\\d+[a-z]+)+\\s*[$SEPS]\\s*esc to interrupt\\)"
    )

    /** The empty composer, shown whenever Codex is waiting for input. */
    val COMPOSER_PLACEHOLDER = Regex("^\\s*$USER_MARKER?\\s*Ask Codex to do anything\\s*$")

    /**
     * The footer: model name, separator, working directory —
     * "gpt-6-astra low · /tmp". Anchored on the path so ordinary prose
     * containing a middle dot doesn't match.
     */
    val FOOTER_LINE = Regex("^\\s*\\S[^$SEPS]{0,48}[$SEPS]\\s*[~/][^\\s]*\\s*$")

    /** Startup banner inside the welcome box: ">_ OpenAI Codex (v0.153.4)". */
    val BANNER = Regex(">_\\s*OpenAI Codex")

    /** A numbered menu row: "› 1. Yes, proceed" / "  2. No". */
    val MENU_OPTION = Regex("^\\s*$USER_MARKER?\\s*\\d+\\.\\s+\\S.*")

    /** Braille spinner frames Codex writes into the terminal title (OSC 0)
     *  while a turn is in flight. Presence of one = the agent is working. */
    private val BRAILLE_SPINNER = '⠀'..'⣿'

    fun titleShowsSpinner(title: String): Boolean = title.any { it in BRAILLE_SPINNER }

    /** Status verb from a working row, or null if the line isn't one. */
    fun workingStatus(text: String): String? =
        WORKING_LINE.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Rows that make up Codex's fixed bottom block. These are redrawn in
     *  place on every frame and must never reach the transcript. */
    fun isBottomBlockLine(text: String): Boolean {
        if (text.isBlank()) return false
        return COMPOSER_PLACEHOLDER.matches(text) ||
            FOOTER_LINE.matches(text) ||
            WORKING_LINE.containsMatchIn(text)
    }

    /**
     * Whether a screen looks like Codex. The placeholder, the banner and
     * the working row are unambiguous — no other program prints them — so
     * one sighting is enough. The footer alone is not: it's just
     * "word · path".
     */
    fun looksLikeCodex(lines: List<String>): Boolean = lines.any { line ->
        COMPOSER_PLACEHOLDER.matches(line) ||
            BANNER.containsMatchIn(line) ||
            WORKING_LINE.containsMatchIn(line)
    }
}
