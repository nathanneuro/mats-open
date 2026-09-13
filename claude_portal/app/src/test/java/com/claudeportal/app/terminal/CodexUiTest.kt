package com.claudeportal.app.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Codex CLI UI detection. Fixtures below are verbatim rows captured from a
 * real `codex` v0.153.4 session running in tmux at 100 columns.
 */
class CodexUiTest {

    // --- working / spinner row ---

    @Test
    fun `working row - status verb is extracted`() {
        assertEquals("Working", CodexUi.workingStatus("• Working (1s • esc to interrupt)"))
        assertEquals("Working", CodexUi.workingStatus("• Working (12s • esc to interrupt)"))
        assertEquals(
            "Reviewing approval request",
            CodexUi.workingStatus("• Reviewing approval request (5s • esc to interrupt)")
        )
    }

    @Test
    fun `working row - trailing background-terminal chatter is ignored`() {
        assertEquals(
            "Working",
            CodexUi.workingStatus(
                "• Working (3s • esc to interrupt) · 1 background terminal running · " +
                    "/ps to view · /stop to close"
            )
        )
    }

    @Test
    fun `working row - longer elapsed formats still match`() {
        assertEquals("Working", CodexUi.workingStatus("• Working (2m14s • esc to interrupt)"))
    }

    @Test
    fun `working row - agent prose is not a spinner`() {
        assertNull(CodexUi.workingStatus("• I’ll run the command to create /tmp/approvaltest.txt."))
        assertNull(CodexUi.workingStatus("• Ran touch /tmp/approvaltest.txt successfully."))
        assertNull(CodexUi.workingStatus("  Working on it (whenever you're ready)"))
    }

    // --- composer + footer ---

    @Test
    fun `composer placeholder matches`() {
        assertTrue(CodexUi.COMPOSER_PLACEHOLDER.matches("› Ask Codex to do anything"))
        assertTrue(CodexUi.COMPOSER_PLACEHOLDER.matches("  Ask Codex to do anything  "))
    }

    @Test
    fun `composer placeholder does not match a typed prompt`() {
        assertFalse(CodexUi.COMPOSER_PLACEHOLDER.matches("› reply with just the word hi"))
    }

    @Test
    fun `footer matches model and cwd`() {
        assertTrue(CodexUi.FOOTER_LINE.matches("  gpt-6-astra low · /tmp"))
        assertTrue(CodexUi.FOOTER_LINE.matches("gpt-6-astra low · ~/projects/mats"))
    }

    @Test
    fun `footer does not match prose containing a middle dot`() {
        assertFalse(CodexUi.FOOTER_LINE.matches("• Ran ls -1A /usr/share/doc"))
        assertFalse(CodexUi.FOOTER_LINE.matches("See the notes · they cover the setup"))
    }

    // --- bottom block ---

    @Test
    fun `bottom block covers chrome but not transcript`() {
        assertTrue(CodexUi.isBottomBlockLine("› Ask Codex to do anything"))
        assertTrue(CodexUi.isBottomBlockLine("  gpt-6-astra low · /tmp"))
        assertTrue(CodexUi.isBottomBlockLine("• Working (7s • esc to interrupt)"))

        assertFalse(CodexUi.isBottomBlockLine("• Explored"))
        assertFalse(CodexUi.isBottomBlockLine("  └ List doc"))
        assertFalse(CodexUi.isBottomBlockLine("› run the shell command: touch /tmp/x"))
        assertFalse(CodexUi.isBottomBlockLine(""))
    }

    // --- window detection ---

    @Test
    fun `screen with codex markers is detected`() {
        assertTrue(CodexUi.looksLikeCodex(listOf("hello", "› Ask Codex to do anything")))
        assertTrue(CodexUi.looksLikeCodex(listOf("│ >_ OpenAI Codex (v0.153.4)   │")))
        assertTrue(CodexUi.looksLikeCodex(listOf("• Working (4s • esc to interrupt)")))
    }

    @Test
    fun `claude and shell screens are not codex`() {
        assertFalse(
            CodexUi.looksLikeCodex(
                listOf("✻ Thinking…", "❯ ", "─────────────────", "◑ medium")
            )
        )
        assertFalse(CodexUi.looksLikeCodex(listOf("➜  ~ ls -la", "total 48")))
        // The footer alone is too weak a signal to flip a window to Codex.
        assertFalse(CodexUi.looksLikeCodex(listOf("gpt-6-astra low · /tmp")))
    }

    // --- title spinner ---

    @Test
    fun `braille title frame means working`() {
        assertTrue(CodexUi.titleShowsSpinner("⠙ tmp"))
        assertTrue(CodexUi.titleShowsSpinner("⠐ Claude Code"))
        assertFalse(CodexUi.titleShowsSpinner("tmp"))
        assertFalse(CodexUi.titleShowsSpinner("nathan@host: ~/projects"))
    }

    // --- window name mapping ---

    @Test
    fun `window names map to agents`() {
        assertEquals(AgentKind.CLAUDE, AgentKind.fromWindowName("claude"))
        assertEquals(AgentKind.CLAUDE, AgentKind.fromWindowName("3:Claude"))
        assertEquals(AgentKind.CODEX, AgentKind.fromWindowName("codex"))
        assertEquals(AgentKind.NONE, AgentKind.fromWindowName("node"))
        assertEquals(AgentKind.NONE, AgentKind.fromWindowName("zsh"))
    }
}
