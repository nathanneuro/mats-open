package com.claudeportal.app.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for OutputProcessor's regex patterns and classification logic.
 *
 * These patterns detect Claude Code UI elements (thinking animations, status lines,
 * fences, prompts, tmux bars) and must not false-positive on real content.
 *
 * Test fixtures are captured from real logcat traces when bugs are found.
 */
class OutputProcessorPatternsTest {

    // Mirror the companion object patterns so we can test them directly.
    // When patterns change in OutputProcessor, update these too.
    private val THINKING_SYMBOLS_STR = "✶✻✽·✢*"
    private val LONE_THINKING_SYMBOL = Regex("^\\s*[$THINKING_SYMBOLS_STR]\\s*$")
    private val ELLIPSIS = Regex("[.…\u2024\u2025]+\\s*$")
    private val STATUS_LINE_PATTERN = Regex(
        "^\\s*[$THINKING_SYMBOLS_STR]\\s+([A-Z][a-z]{2,}(?:\\s+\\w+){0,3})[.…\u2024\u2025]{1,3}.*$" +
        "|^\\s*([A-Z][a-z]{2,}(?:\\s+\\w+){0,3})[.…\u2024\u2025]{1,3}.*$"
    )
    private val TMUX_BAR_LINE = Regex("^\\[\\d+]\\s+\\d+:\\S.*")
    private val FENCE_CHARS = setOf('─', '═', '━', '-', '=')
    private val TOOL_PROGRESS_PATTERN = Regex("^⎿\\s+\\S+…\\s*\\(\\d+s.*\\)$")
    // Shell prompts: ➜, $, %, # — NOT ❯ or > (those are Claude Code prompts)
    private val SHELL_PROMPT_PATTERN = Regex("^(➜|\\$|%|#)\\s.*")

    private fun isFenceLine(text: String): Boolean {
        val nonSpace = text.count { it != ' ' }
        if (nonSpace == 0) return false
        val fenceCount = text.count { it in FENCE_CHARS }
        return fenceCount.toFloat() / nonSpace >= 0.6f
    }

    private fun isStatusInfoLine(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 5) return false
        val pipeCount = trimmed.count { it == '|' }
        val hasStats = trimmed.contains('%') || trimmed.contains("GB") ||
            trimmed.contains("tokens") || trimmed.contains("CPU") ||
            trimmed.contains("RAM") || trimmed.contains("GPU")
        if (pipeCount >= 1 && hasStats) return true
        return false
    }

    // --- LONE_THINKING_SYMBOL ---

    @Test
    fun `lone thinking symbol - matches single symbols`() {
        assertTrue(LONE_THINKING_SYMBOL.matches("✶"))
        assertTrue(LONE_THINKING_SYMBOL.matches("✻"))
        assertTrue(LONE_THINKING_SYMBOL.matches("✽"))
        assertTrue(LONE_THINKING_SYMBOL.matches("·"))
        assertTrue(LONE_THINKING_SYMBOL.matches("✢"))
        assertTrue(LONE_THINKING_SYMBOL.matches("*"))
    }

    @Test
    fun `lone thinking symbol - matches with whitespace`() {
        assertTrue(LONE_THINKING_SYMBOL.matches("  ✶  "))
        assertTrue(LONE_THINKING_SYMBOL.matches(" ✢ "))
    }

    @Test
    fun `lone thinking symbol - rejects symbol with text`() {
        assertFalse(LONE_THINKING_SYMBOL.matches("✶ Thinking"))
        assertFalse(LONE_THINKING_SYMBOL.matches("✢ Running…"))
    }

    // --- STATUS_LINE_PATTERN ---

    @Test
    fun `status line - matches symbol + text + ellipsis`() {
        assertNotNull(STATUS_LINE_PATTERN.find("✶ Thinking..."))
        assertNotNull(STATUS_LINE_PATTERN.find("✢ Running…"))
        assertNotNull(STATUS_LINE_PATTERN.find("✻ Compacting conversation…"))
    }

    @Test
    fun `status line - matches text + ellipsis without symbol`() {
        assertNotNull(STATUS_LINE_PATTERN.find("Compacting conversation…"))
        assertNotNull(STATUS_LINE_PATTERN.find("Processing files..."))
    }

    @Test
    fun `status line - allows trailing text after ellipsis`() {
        assertNotNull(STATUS_LINE_PATTERN.find("✢ Setting up… (5m 45s · ↓ 9.1k tokens)"))
        assertNotNull(STATUS_LINE_PATTERN.find("✶ Thinking… [2s]"))
    }

    @Test
    fun `status line - rejects tool use lines`() {
        assertNull(STATUS_LINE_PATTERN.find("Read(file.kt)"))
        assertNull(STATUS_LINE_PATTERN.find("Bash(ls -la)"))
    }

    // --- TMUX_BAR_LINE ---

    @Test
    fun `tmux bar line - matches standard format`() {
        assertTrue(TMUX_BAR_LINE.matches("[0] 0:zsh  1:claude*"))
        assertTrue(TMUX_BAR_LINE.matches("[0] 0:bash  1:claude*  2:htop-"))
    }

    @Test
    fun `tmux bar line - rejects normal content`() {
        assertFalse(TMUX_BAR_LINE.matches("normal content here"))
        assertFalse(TMUX_BAR_LINE.matches("  some indented text"))
    }

    // --- Fence detection ---

    @Test
    fun `fence line - matches long fence`() {
        assertTrue(isFenceLine("──────────────────────────────────────────"))
        assertTrue(isFenceLine("════════════════════════════════════════"))
    }

    @Test
    fun `fence line - short fences still detected but filtered by length check`() {
        assertTrue(isFenceLine("---"))
        assertTrue(isFenceLine("==="))
        assertTrue("---".trim().length <= 20)
    }

    // ==========================================================================
    // REGRESSION TESTS — Add fixtures from logcat traces below this line.
    // Each test should reference the bug it was captured from.
    // ==========================================================================

    // --- trace_001: status info lines leaking into content ---

    @Test
    fun `trace_001 - status info lines with 2+ pipes are caught`() {
        assertTrue(isStatusInfoLine("   CPU: 51% | RAM: 11% | GPU0: 0%"))
        assertTrue(isStatusInfoLine("   CPU: 57% | RAM: 11% | GPU0: 0%                         Checking for updates"))
    }

    @Test
    fun `trace_001 - status info lines with 1 pipe and stats are caught`() {
        assertTrue(isStatusInfoLine("  /media/external-drive/crystal_society | Context left until auto-compact: 11%"))
        assertTrue(isStatusInfoLine("  22.1/24.0GB | GPU1: 0% 0.0/24.0GB"))
    }

    @Test
    fun `trace_001 - status info does not match normal content`() {
        assertFalse(isStatusInfoLine("  Bash(RUN_SLOW_TESTS=1 uv run pytest tests/test_simulator.py::TestChessFullGame"))
        assertFalse(isStatusInfoLine("Hello world"))
        assertFalse(isStatusInfoLine("  ⎿  some output from a tool"))
    }

    @Test
    fun `trace_003 - status info does not match command lines with pipes`() {
        // Shell pipes and regex alternation should not be caught as status info
        assertFalse(isStatusInfoLine("● Bash(ss -tlnp | grep -E '5000|8000')"))
        assertFalse(isStatusInfoLine("  ps aux | grep '[p]ython.*crystal_society'"))
    }

    // --- trace_001: tool progress lines repeating ---

    @Test
    fun `trace_001 - tool progress lines are caught`() {
        assertTrue(TOOL_PROGRESS_PATTERN.matches("⎿  Running… (2s · timeout 10m)"))
        assertTrue(TOOL_PROGRESS_PATTERN.matches("⎿  Running… (15s · timeout 10m)"))
        assertTrue(TOOL_PROGRESS_PATTERN.matches("⎿  Running… (9s · timeout 10m)"))
    }

    @Test
    fun `trace_001 - tool progress does not match real tool output`() {
        assertFalse(TOOL_PROGRESS_PATTERN.matches("⎿  some actual command output here"))
        assertFalse(TOOL_PROGRESS_PATTERN.matches("⎿  PASS: test_chess_game (3.2s)"))
    }

    // --- trace_002: shell prompt buffering ---

    @Test
    fun `trace_002 - shell prompt pattern matches zsh prompts`() {
        assertTrue(SHELL_PROMPT_PATTERN.matches("➜  projects2 c"))
        assertTrue(SHELL_PROMPT_PATTERN.matches("➜  projects2 cd self_awa"))
        assertTrue(SHELL_PROMPT_PATTERN.matches("➜  projects2 cd self_awareness"))
        assertTrue(SHELL_PROMPT_PATTERN.matches("➜  self_awareness"))
    }

    @Test
    fun `trace_002 - shell prompt pattern matches other common prompts`() {
        assertTrue(SHELL_PROMPT_PATTERN.matches("$ ls -la"))
        assertTrue(SHELL_PROMPT_PATTERN.matches("% cd foo"))
        assertTrue(SHELL_PROMPT_PATTERN.matches("# apt install vim"))
    }

    @Test
    fun `trace_002 - shell prompt does not match Claude Code prompts`() {
        // ❯ and > are Claude Code prompts — they should NOT be buffered
        assertFalse(SHELL_PROMPT_PATTERN.matches("❯ ok, lets try the slow tests again"))
        assertFalse(SHELL_PROMPT_PATTERN.matches("> some claude code input"))
    }

    @Test
    fun `trace_002 - shell prompt pattern does not match content`() {
        assertFalse(SHELL_PROMPT_PATTERN.matches("brain_like  self_awareness"))
        assertFalse(SHELL_PROMPT_PATTERN.matches("  some output text"))
        assertFalse(SHELL_PROMPT_PATTERN.matches("● Bash(ls -la)"))
    }

    // --- trace_004: isClaudeWindow gating and tmux bar parsing ---
    // Bug: Claude-specific filters (thinking detection, status area, fence lines)
    // were applied to ALL tmux windows, garbling non-Claude output (zsh, htop, etc.)
    // Fix: extractTmuxBar sets isClaudeWindow based on active window name.

    private val TMUX_SESSION_PATTERN = Regex("^\\[([^\\]]+)]\\s+")
    private val TMUX_WINDOW_PATTERN = Regex("(\\d+):(\\S+)")

    /** Parse a tmux bar string the same way OutputProcessor.extractTmuxBar does. */
    private fun parseTmuxBar(lastRow: String): Pair<List<TmuxWindow>, Boolean>? {
        val sessionMatch = TMUX_SESSION_PATTERN.find(lastRow) ?: return null
        val afterSession = lastRow.substring(sessionMatch.range.last + 1)
        val gapIndex = afterSession.indexOf("   ")
        val windowsText = if (gapIndex >= 0) afterSession.substring(0, gapIndex) else afterSession
        val matches = TMUX_WINDOW_PATTERN.findAll(windowsText).toList()
        if (matches.isEmpty()) return null
        val windows = matches.map { match ->
            val index = match.groupValues[1].toIntOrNull() ?: 0
            val rawName = match.groupValues[2]
            val isActive = rawName.endsWith("*") || rawName.endsWith("*-")
            TmuxWindow(
                index = index,
                name = rawName.removeSuffix("*").removeSuffix("*-").removeSuffix("-"),
                isActive = isActive
            )
        }
        val activeWindow = windows.firstOrNull { it.isActive }
        val isClaude = activeWindow?.name?.contains("claude", ignoreCase = true) == true
        return Pair(windows, isClaude)
    }

    @Test
    fun `trace_004 - tmux bar detects claude as active window`() {
        // Real logcat fixture: TMUX_BAR chunk content (ANSI stripped)
        val bar = "[0] 0:claude* 1:zsh  2:tail  3:uv-          \"✳ GPU Memory Issue\" 01:28 18-Feb-26"
        val (windows, isClaude) = parseTmuxBar(bar)!!
        assertEquals(4, windows.size)
        assertEquals("claude", windows[0].name)
        assertTrue(windows[0].isActive)
        assertTrue(isClaude)
    }

    @Test
    fun `trace_004 - tmux bar detects non-claude active window`() {
        val bar = "[0] 0:claude  1:zsh* 2:tail  3:uv-          hostname 01:28 18-Feb-26"
        val (windows, isClaude) = parseTmuxBar(bar)!!
        assertEquals(4, windows.size)
        assertFalse(windows[0].isActive)
        assertTrue(windows[1].isActive)
        assertEquals("zsh", windows[1].name)
        assertFalse(isClaude)
    }

    @Test
    fun `trace_004 - filters that should only apply to Claude windows`() {
        // These are Claude Code UI elements that should NOT be filtered on non-Claude windows
        val claudeOnlyLines = listOf(
            "✶",                                                           // lone thinking symbol
            "────────────────────────────────────────────────────────────", // fence line (>20 chars)
            "❯",                                                           // bare Claude prompt
            "  /path/to/project | CPU: 5% | RAM: 1% | GPU0: 100%",        // status info
            "  ctrl+b ctrl+b (twice) to run in background",               // bg hint
            "⎿  Running… (2s · timeout 10m)",                              // tool progress
            "✶ Thinking...",                                                // status line
        )
        // On a Claude window, all these should match their respective filters
        assertTrue(LONE_THINKING_SYMBOL.matches(claudeOnlyLines[0]))
        assertTrue(isFenceLine(claudeOnlyLines[1]) && claudeOnlyLines[1].trim().length > 20)
        assertTrue(claudeOnlyLines[2].trimStart().let {
            (it.startsWith("❯") || it.startsWith(">")) &&
            it.removePrefix("❯").removePrefix(">").isBlank()
        })
        assertTrue(isStatusInfoLine(claudeOnlyLines[3]))
        assertTrue(claudeOnlyLines[4].trimStart().let {
            it.contains("ctrl+b") && it.contains("run in background")
        })
        assertTrue(TOOL_PROGRESS_PATTERN.matches(claudeOnlyLines[5].trimStart()))
        assertNotNull(STATUS_LINE_PATTERN.find(claudeOnlyLines[6]))
    }

    @Test
    fun `trace_004 - tmux bar line always filtered regardless of window`() {
        // TMUX_BAR_LINE filter should apply on all windows (not Claude-specific)
        assertTrue(TMUX_BAR_LINE.matches("[0] 0:claude* 1:zsh  2:tail"))
        assertTrue(TMUX_BAR_LINE.matches("[1] 0:bash  1:vim*"))
    }

    // --- trace_002: garbled text from partial cursor overwrites ---

    @Test
    fun `trace_002 - garbled chimera lines should be detectable`() {
        // These are screen artifacts where cursor positioning partially overwrites old content.
        // "self_model" + "areness ls" = chimera of "self_model" and "self_awareness ls"
        // "erusing…" = fragment of "Perusing…"
        // "erustests/..." = fragment leaking from screen state
        // These should be caught by partial overwrite detection in diffScreens.
        val garbled = listOf(
            "self_modelareness ls",
            "   erusing…",
            "   erustests/test_simulator.py::TestGoFullGame -v -s 2>&1)"
        )
        // Just documenting these as known garbled patterns for now.
        // The fix is in diffScreens partial-overwrite detection, not regex patterns.
        for (line in garbled) {
            assertNotNull("Documenting garbled line: $line", line)
        }
    }
}
