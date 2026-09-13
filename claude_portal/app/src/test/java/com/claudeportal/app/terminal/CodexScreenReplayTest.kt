package com.claudeportal.app.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Replays a real recording of `codex` v0.153.4 driving a 100x30 pty
 * (captured with `tmux pipe-pane`) through the interpreter and checks that
 * the resulting screen is the one a user would be looking at.
 *
 * This is the regression guard for scroll-region support: Codex pins its
 * composer and footer below a DECSTBM region and pushes the transcript
 * through it with reverse index, so an interpreter that ignores regions
 * renders the UI block on top of the conversation.
 */
class CodexScreenReplayTest {

    private fun replay(): VirtualScreen {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("codex_scroll_raw.txt")!!
            .readBytes().toString(Charsets.UTF_8)
        val screen = VirtualScreen(cols = 100, rows = 30)
        val interpreter = AnsiScreenInterpreter(screen)
        // Feed in small chunks: SSH splits escape sequences at arbitrary
        // boundaries and the interpreter has to survive that.
        raw.chunked(97).forEach { interpreter.feed(it) }
        return screen
    }

    @Test
    fun `codex bottom block ends up at the bottom of the screen`() {
        val screen = replay()
        val lines = screen.getTextLines()

        val composerRow = lines.indexOfFirst { CodexUi.COMPOSER_PLACEHOLDER.matches(it) }
        val footerRow = lines.indexOfLast { CodexUi.FOOTER_LINE.matches(it) }

        assertTrue("composer row not found in:\n${lines.joinToString("\n")}", composerRow >= 0)
        assertTrue("footer row not found", footerRow > composerRow)
        assertTrue("bottom block should sit in the last rows", composerRow >= screen.rows - 5)
    }

    @Test
    fun `scrolled-off transcript leaves the newest lines on screen`() {
        val screen = replay()
        val lines = screen.getTextLines()
        val rendered = lines.joinToString("\n")

        // The recorded turn prints 1..40 into a 30-row screen, so the tail
        // must be on screen and the head must have scrolled away. Without
        // scroll-region support the screen freezes on the startup banner and
        // the numbers overwrite each other instead.
        assertTrue("expected the tail of the list:\n$rendered", rendered.contains("40"))
        assertFalse("early lines should have scrolled off:\n$rendered", rendered.contains("  1\n"))
        assertFalse("banner should have scrolled off:\n$rendered", rendered.contains("OpenAI Codex"))

        // Rows stay in order: each number is one row further down than the last.
        val rowOf = { n: String -> lines.indexOfFirst { it.trim() == n } }
        assertTrue(rowOf("31") in 0 until rowOf("40"))
    }
}
