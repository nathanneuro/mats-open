package com.claudeportal.app.terminal

import android.text.SpannableStringBuilder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The brain of the output pipeline. Intercepts raw SSH data and produces
 * three clean output flows: deduplicated content, thinking state, and tmux bar.
 *
 * Raw SSH data → AnsiScreenInterpreter → VirtualScreen → diff → new lines only.
 */
class OutputProcessor(
    private val cols: Int = 80,
    private val rows: Int = 24,
    private val displayCols: Int = 40,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    // Virtual screens for diffing
    private val screen = VirtualScreen(cols, rows)
    private val interpreter = AnsiScreenInterpreter(screen)
    private var previousSnapshot: Array<Array<VirtualScreen.Cell>>? = null

    // Thinking state machine
    enum class ThinkingState { IDLE, ANIMATING, STATUS_TEXT }
    private var thinkingState = ThinkingState.IDLE
    private var thinkingTimeoutJob: Job? = null
    private var thinkingRow = -1 // Screen row with thinking symbol, excluded from content

    // Output flows
    private val _contentFlow = MutableSharedFlow<List<SpannableStringBuilder>>(extraBufferCapacity = 64)
    val contentFlow: SharedFlow<List<SpannableStringBuilder>> = _contentFlow

    private val _thinkingFlow = MutableStateFlow(ThinkingUpdate(false, null, null))
    val thinkingFlow: StateFlow<ThinkingUpdate> = _thinkingFlow

    private val _tmuxBarFlow = MutableStateFlow<TmuxBarUpdate?>(null)
    val tmuxBarFlow: StateFlow<TmuxBarUpdate?> = _tmuxBarFlow

    private val _statusBarFlow = MutableStateFlow<String?>(null)
    val statusBarFlow: StateFlow<String?> = _statusBarFlow

    // Plain text flow for history persistence
    private val _plainTextFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val plainTextFlow: SharedFlow<String> = _plainTextFlow

    companion object {
        private val THINKING_SYMBOLS = setOf('✶', '✻', '✽', '·', '✢', '*')
        private val THINKING_SYMBOLS_STR = "✶✻✽·✢*"
        private val FENCE_CHARS = setOf('─', '═', '━', '-', '=')
        // A line that is ONLY a thinking symbol (with optional whitespace)
        private val LONE_THINKING_SYMBOL = Regex("^\\s*[$THINKING_SYMBOLS_STR]\\s*$")
        // Trailing dots/ellipsis in any form: .., ..., …, Unicode variants
        private val ELLIPSIS = Regex("[.…\u2024\u2025]+\\s*$")
        // A content line that's really a Claude Code thinking/status UI element:
        // Option 1: thinking_symbol + short text + ellipsis (status update)
        //   e.g. "✶ Thinking...", "● Running…", "Compacting conversation…"
        //   NOT tool-use lines like "● Read(file.kt)" or "● Bash(ls -la)"
        // Option 2: short capitalized text ending with ellipsis (no symbol)
        //   e.g. "Compacting conversation…"
        // Status line: thinking_symbol + word(s) + ellipsis, or just word(s) + ellipsis.
        // Allow trailing text after ellipsis (timestamps, token counts, etc.)
        private val STATUS_LINE_PATTERN = Regex(
            "^\\s*[$THINKING_SYMBOLS_STR]\\s+([A-Z][a-z]{2,}(?:\\s+\\w+){0,3})[.…\u2024\u2025]{1,3}.*$" +
            "|^\\s*([A-Z][a-z]{2,}(?:\\s+\\w+){0,3})[.…\u2024\u2025]{1,3}.*$"
        )
        // Regex to detect tmux status bar: black text on green background
        private val TMUX_BAR_PATTERN = Regex("\u001b\\[3[0-7]m\u001b\\[4[2-7]m|\\[\\d+\\]")
        // Tmux bar line leaked into content: e.g. "[0] 0:zsh  1:claude*  ..."
        private val TMUX_BAR_LINE = Regex("^\\[\\d+]\\s+\\d+:\\S.*")
        // Regex to detect cursor-home at start of chunk
        private val CURSOR_HOME_PATTERN = Regex("^\u001b\\[(H|1;1H|\\?25[lh])")
        // Pattern for status text in raw chunks (ANSI-stripped):
        // thinking_symbol followed by short text, with or without ellipsis
        // \\s* because cursor-forward [C] gets stripped, leaving no whitespace
        private val STATUS_TEXT_PATTERN = Regex("[$THINKING_SYMBOLS_STR]\\s*([A-Z][a-z].{1,38}?)(?:[.…\u2024\u2025]+|\\s*$)")
        // Tool progress: "⎿  Running… (2s · timeout 10m)" — transient timer updates
        private val TOOL_PROGRESS_PATTERN = Regex("^⎿\\s+\\S+…\\s*\\(\\d+s.*\\)$")
        // Shell prompt patterns: ➜, $, %, # at line start (with optional leading whitespace)
        // NOT ❯ or > — those are Claude Code prompts, handled separately.
        // These get buffered so partial command echoes are collapsed into the final version.
        private val SHELL_PROMPT_PATTERN = Regex("^(➜|\\$|%|#)\\s.*")
        private const val MIN_DIFF_INTERVAL_MS = 80L
        private const val DEDUP_WINDOW = 20
        private const val TAG = "OutputProcessor"
    }

    // Whether the active tmux window is running Claude Code.
    // When false, Claude-specific filters (thinking detection, status area,
    // fence lines, etc.) are bypassed so non-Claude windows render cleanly.
    @Volatile
    var isClaudeWindow: Boolean = false

    // Throttle: skip expensive diffs for rapid-fire small chunks (thinking animation)
    private var lastDiffTime = 0L

    /**
     * Process a raw SSH output chunk. This is the main entry point,
     * called for every chunk emitted by SshManager.outputFlow.
     */
    fun processRawOutput(raw: String) {
        val chunkType = classifyChunk(raw)
        val escaped = raw.replace("\u001b", "⎋").replace("\n", "↵").replace("\r", "⏎")
        Log.d(TAG, "chunk len=${raw.length} type=$chunkType: ${escaped.take(200)}")

        // Feed to screen interpreter
        interpreter.feed(raw)

        // Always check for tmux bar — it's cheap (one row read + regex),
        // and the bar can appear in any chunk, not just classified TMUX_BAR ones.
        // On initial `tmux a`, the bar arrives embedded in pane redraws.
        extractTmuxBar()

        when (chunkType) {
            ChunkType.TMUX_BAR -> {
                return
            }
            ChunkType.FULL_REDRAW -> {
            }
            ChunkType.INCREMENTAL -> {
                // Small incremental chunks (thinking animation frames) arrive very
                // rapidly (~10/sec). Skip expensive diff if we diffed recently —
                // just update thinking state. The next larger chunk or the next
                // chunk after the throttle window will pick up any content changes.
                if (raw.length < 60) {
                    if (isClaudeWindow) detectThinkingOnScreen()
                    val now = System.currentTimeMillis()
                    if (now - lastDiffTime < MIN_DIFF_INTERVAL_MS) {
                        return
                    }
                }
            }
        }

        if (isClaudeWindow) detectThinkingOnScreen()

        lastDiffTime = System.currentTimeMillis()
        val rawLines = diffScreens(chunkType)
        val newLines = postProcessLines(rawLines)
        if (newLines.isNotEmpty()) {
            for (line in newLines) {
                Log.d(TAG, "emit: '${line.toString().take(120)}'")
            }
            _contentFlow.tryEmit(newLines)
            val plainText = newLines.joinToString("\n") { it.toString() }
            if (plainText.isNotBlank()) {
                _plainTextFlow.tryEmit(plainText + "\n")
            }
        }
    }

    private fun classifyChunk(raw: String): ChunkType {
        val len = raw.length

        // Check for tmux bar: small chunk with black-on-green color codes
        if (len < 300) {
            val stripped = stripAnsiCodes(raw)
            if (raw.contains("\u001b[42m") && stripped.contains("[") &&
                "\\[\\d+\\]".toRegex().containsMatchIn(stripped)) {
                return ChunkType.TMUX_BAR
            }
        }

        // Full redraw: large chunk with cursor-home + erase sequences
        if (len > 400) {
            val hasCursorHome = raw.contains("\u001b[H") || raw.contains("\u001b[1;1H")
            val hasEraseSequences = raw.contains("\u001b[K") || raw.contains("\u001b[J") ||
                raw.contains("\u001b[2J")
            if (hasCursorHome && hasEraseSequences) {
                return ChunkType.FULL_REDRAW
            }
        }

        return ChunkType.INCREMENTAL
    }

    // Deduplication: track recently emitted lines to avoid repeats
    private val recentLines = ArrayDeque<String>(DEDUP_WINDOW)

    // Shell prompt buffering: hold prompt lines until non-prompt content arrives,
    // so partial command echoes collapse into the final version.
    private var pendingPrompt: SpannableStringBuilder? = null

    /**
     * Post-process content lines for phone display:
     * - Drop fence lines (status area safety net)
     * - Drop lone thinking symbols
     * - Deduplicate recently emitted lines
     * - Trim fence lines to displayCols width
     */
    private fun postProcessLines(lines: List<SpannableStringBuilder>): List<SpannableStringBuilder> {
        val result = mutableListOf<SpannableStringBuilder>()
        for (line in lines) {
            val text = line.toString()

            // Drop tmux bar lines that leaked into content (always, regardless of window)
            if (TMUX_BAR_LINE.matches(text)) {
                Log.v(TAG, "filter TMUX_BAR: '$text'")
                continue
            }

            val trimmed = text.trimStart()

            // Claude Code-specific filters: only apply when active window is Claude
            if (isClaudeWindow) {
                // Drop lines that are only a thinking symbol (leaked animation frame)
                if (LONE_THINKING_SYMBOL.matches(text)) {
                    Log.v(TAG, "filter LONE_SYMBOL: '$text'")
                    continue
                }

                // Drop standalone fence lines — these are status area UI, not content.
                // Content fences (e.g. in code blocks) are shorter and mixed with text.
                if (isFenceLine(text) && text.trim().length > 20) {
                    Log.v(TAG, "filter FENCE: '$text'")
                    continue
                }

                // Drop standalone prompt lines (❯ with no meaningful text after)
                if ((trimmed.startsWith("❯") || trimmed.startsWith(">")) &&
                    trimmed.removePrefix("❯").removePrefix(">").isBlank()) {
                    Log.v(TAG, "filter PROMPT: '$text'")
                    continue
                }

                // Drop status info lines that leaked through detectStatusArea
                // (pipe-separated stats like "CPU: 51% | RAM: 11% | GPU0: 0%")
                if (isStatusInfoLine(text)) {
                    Log.v(TAG, "filter STATUS_INFO: '$text'")
                    continue
                }

                // Drop "ctrl+b ctrl+b (twice) to run in background" hint
                if (trimmed.contains("ctrl+b") && trimmed.contains("run in background")) {
                    Log.v(TAG, "filter BG_HINT: '$text'")
                    continue
                }

                // Drop tool progress lines (transient timer updates)
                // e.g. "  ⎿  Running… (2s · timeout 10m)"
                if (TOOL_PROGRESS_PATTERN.matches(trimmed)) {
                    Log.v(TAG, "filter TOOL_PROGRESS: '$text'")
                    continue
                }

                // Filter status lines (e.g. "✶ Compacting conversation…") → show in thinking bar
                val statusMatch = STATUS_LINE_PATTERN.find(text)
                if (statusMatch != null) {
                    val rawStatus = statusMatch.groupValues[1].trim()
                    val statusText = ELLIPSIS.replace(rawStatus, "").trim()
                    Log.d(TAG, "filter STATUS: '$text' → statusText='$statusText'")
                    if (statusText.isNotEmpty()) {
                        handleThinking(statusText)
                    }
                    continue
                }
            }

            // Buffer shell prompt lines so partial command echoes collapse.
            // Each new prompt version replaces the buffer; it flushes when
            // non-prompt content arrives.
            if (SHELL_PROMPT_PATTERN.matches(trimmed)) {
                Log.v(TAG, "buffer PROMPT: '$text'")
                pendingPrompt = line
                continue
            }

            // Non-prompt line: flush any buffered prompt first
            val flushed = pendingPrompt
            if (flushed != null) {
                val flushedText = flushed.toString().trim()
                if (flushedText.isNotEmpty() && flushedText !in recentLines) {
                    Log.d(TAG, "flush PROMPT: '$flushedText'")
                    recentLines.addLast(flushedText)
                    if (recentLines.size > DEDUP_WINDOW) recentLines.removeFirst()
                    result.add(flushed)
                }
                pendingPrompt = null
            }

            // Deduplicate: skip if this exact line was recently emitted
            val textKey = text.trim()
            if (textKey.isNotEmpty() && textKey in recentLines) {
                Log.v(TAG, "filter DEDUP: '$text'")
                continue
            }
            if (textKey.isNotEmpty()) {
                recentLines.addLast(textKey)
                if (recentLines.size > DEDUP_WINDOW) recentLines.removeFirst()
            }

            result.add(line)
        }
        return result
    }

    /** Strip selector/cursor characters for fuzzy line comparison. */
    private fun stripSelector(text: String): String {
        return text.replace("❯", " ").replace(">", " ").trimStart()
    }

    /**
     * Detect partial cursor overwrites: the cursor moved to a position on a row
     * and wrote a few characters, creating a chimera of old and new content.
     * E.g. "self_awareness ls" → cursor writes "self_model" at col 0 → "self_modelareness ls"
     *
     * Heuristic: if both lines have content and share >60% of characters in the same
     * positions, the change is a partial overwrite, not genuinely new content.
     */
    /**
     * Detect partial cursor overwrites: the cursor moved to a position on a row
     * and wrote a few characters, creating a chimera of old and new content.
     *
     * @param threshold Fraction of matching chars required. Use 0.6 for non-scrolling
     *   diffs (same row, minor changes expected) and 0.4 for scrolling diffs (rows
     *   after the overlap should be entirely new — even 40% overlap is suspicious).
     */
    private fun isPartialOverwrite(prev: String, curr: String, threshold: Float = 0.6f): Boolean {
        if (prev.isBlank() || curr.isBlank()) return false
        val len = minOf(prev.length, curr.length)
        if (len < 5) return false
        var same = 0
        for (i in 0 until len) {
            if (prev[i] == curr[i]) same++
        }
        return same.toFloat() / len > threshold
    }

    /** Strip leading bullet (●) for detecting bullet-blink animation. */
    private fun stripBullet(text: String): String {
        return text.trimStart().removePrefix("●").trimStart()
    }

    /** Check if a row on the current screen has a thinking symbol in the first few columns. */
    private fun isThinkingRow(row: Int): Boolean {
        for (c in 0 until minOf(3, cols)) {
            if (screen.cells[row][c].char in THINKING_SYMBOLS) return true
        }
        return false
    }

    /** Check if a snapshot row has a thinking symbol in the first few columns. */
    private fun isThinkingRowInSnapshot(row: Array<VirtualScreen.Cell>): Boolean {
        for (c in 0 until minOf(3, row.size)) {
            if (row[c].char in THINKING_SYMBOLS) return true
        }
        return false
    }

    /** A line looks like Claude Code status info if it has pipe separators with stats.
     *  Must also contain stats-like keywords to avoid false positives on command lines
     *  that use pipes (e.g. "grep -E '5000|8000'"). */
    private fun isStatusInfoLine(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 5) return false
        val pipeCount = trimmed.count { it == '|' }
        val hasStats = trimmed.contains('%') || trimmed.contains("GB") ||
            trimmed.contains("tokens") || trimmed.contains("CPU") ||
            trimmed.contains("RAM") || trimmed.contains("GPU")
        // Pipes + stats keywords = status info line
        if (pipeCount >= 1 && hasStats) return true
        return false
    }

    /** A line is a "fence" if ≥60% of its non-whitespace chars are fence characters. */
    private fun isFenceLine(text: String): Boolean {
        val nonSpace = text.count { it != ' ' }
        if (nonSpace == 0) return false
        val fenceCount = text.count { it in FENCE_CHARS }
        return fenceCount.toFloat() / nonSpace >= 0.6f
    }

    /**
     * Detect Claude Code's status area at the bottom of the screen.
     * Pattern: fence, "> " prompt, fence, status line(s) — above the tmux bar row.
     * Returns the first row of the status area, or `maxRow` if not found.
     * When updateStatusBar is true, also extracts and emits the status bar text.
     * Only pass updateStatusBar=true on FULL_REDRAW to avoid chimera rows
     * (cursor-positioned content partially overwriting status info rows).
     */
    /** Check if a line is a prompt line (❯ or >) */
    private fun isPromptLine(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("❯") || trimmed.startsWith(">")
    }

    /** Check if a line is part of the status area (fence or action hint).
     *  NOTE: Prompt lines (❯/>) are NOT included here — they'd swallow
     *  answer options like "❯ Yes" in confirmation dialogs. Bare prompts
     *  are filtered in postProcessLines instead. */
    private fun isStatusAreaLine(text: String): Boolean {
        val trimmed = text.trimStart()
        return (isFenceLine(text) && text.length > 20) ||
            trimmed.startsWith("⏵") // accept/reject hints
    }

    private fun detectStatusArea(maxRow: Int, updateStatusBar: Boolean = false): Int {
        // Scan upward from the bottom to find the top of the status area.
        // Claude Code layout (bottom of screen):
        //   Row N:   ──────────── (top fence)
        //   Row N+1: ❯            (prompt — uses ❯ or >)
        //   Row N+2: ──────────── (bottom fence, possibly doubled)
        //   Row N+3: status info (path | CPU | RAM | GPU...)
        //   Row N+4: more status...
        //   Row N+5: ⏵⏵ accept edits on · ...
        //   Last:    [0] bash [1] claude* (tmux bar — already excluded)
        var topStatusRow = -1

        for (r in (maxRow - 1) downTo maxOf(0, maxRow - 10)) {
            val text = screen.getRowText(r)
            if (isStatusAreaLine(text) || isStatusInfoLine(text)) {
                topStatusRow = r
                continue
            } else if (text.isBlank() && topStatusRow >= 0) {
                // Blank line within the status area — include it
                topStatusRow = r
                continue
            } else if (topStatusRow >= 0) {
                break // First content row above the status area
            }
        }

        if (topStatusRow < 0) {
            if (updateStatusBar) _statusBarFlow.value = null
            return maxRow
        }

        // Only update _statusBarFlow on FULL_REDRAW to avoid chimera rows.
        // Incremental chunks may have cursor-positioned content partially
        // overwriting status info rows (e.g. "GPU1: 02 bashes.0GB").
        if (updateStatusBar) {
            val statusLines = mutableListOf<String>()
            for (r in topStatusRow until maxRow) {
                val text = screen.getRowText(r)
                if (isStatusInfoLine(text)) {
                    statusLines.add(text)
                }
            }

            _statusBarFlow.value = if (statusLines.isNotEmpty()) {
                statusLines.joinToString(" ") { it.trim() }
                    .replace(Regex("\\s{2,}"), " ")
            } else {
                null
            }
        }

        return topStatusRow
    }

    /** Same detection but on a raw snapshot array (for previous screen). */
    private fun detectStatusAreaInSnapshot(snapshot: Array<Array<VirtualScreen.Cell>>, maxRow: Int): Int {
        var topStatusRow = -1
        for (r in (maxRow - 1) downTo maxOf(0, maxRow - 10)) {
            val text = getRowText(snapshot[r])
            if (isStatusAreaLine(text) || isStatusInfoLine(text)) {
                topStatusRow = r
                continue
            } else if (text.isBlank() && topStatusRow >= 0) {
                topStatusRow = r
                continue
            } else if (topStatusRow >= 0) {
                break
            }
        }
        return if (topStatusRow >= 0) topStatusRow else maxRow
    }

    /**
     * Diff current screen against previous snapshot.
     * Returns only genuinely new lines (not present in previous screen).
     */
    private fun diffScreens(chunkType: ChunkType = ChunkType.FULL_REDRAW): List<SpannableStringBuilder> {
        val prev = previousSnapshot
        previousSnapshot = screen.snapshot()

        if (prev == null) {
            // First screen - emit all non-blank lines
            return extractNonBlankLines()
        }

        // Exclude last row (tmux bar). Detect Claude's status area only on Claude windows.
        val maxRow = rows - 1
        val contentRows = if (isClaudeWindow) {
            detectStatusArea(maxRow, updateStatusBar = chunkType == ChunkType.FULL_REDRAW)
        } else {
            if (chunkType == ChunkType.FULL_REDRAW) _statusBarFlow.value = null
            maxRow
        }

        val prevContentRows = if (isClaudeWindow) {
            detectStatusAreaInSnapshot(prev, maxRow)
        } else maxRow

        // Build line lists; blank thinking rows only on Claude windows
        val prevLines = (0 until prevContentRows).map {
            if (isClaudeWindow && isThinkingRowInSnapshot(prev[it])) "" else getRowText(prev[it])
        }
        val currLines = (0 until contentRows).map {
            if (isClaudeWindow && isThinkingRow(it)) "" else screen.getRowText(it)
        }

        // Find the longest suffix of prevLines that matches a prefix of currLines
        // This tells us how much the content scrolled
        var overlapLen = 0
        val maxOverlap = minOf(prevLines.size, currLines.size)
        for (overlap in maxOverlap downTo 1) {
            val prevSuffix = prevLines.subList(prevLines.size - overlap, prevLines.size)
            val currPrefix = currLines.subList(0, overlap)
            if (prevSuffix == currPrefix) {
                overlapLen = overlap
                break
            }
        }

        // New lines = everything in currLines after the overlap
        val newStartRow = overlapLen
        val newLines = mutableListOf<SpannableStringBuilder>()

        if (overlapLen == 0) {
            // Non-scrolling redraw. Check if this is menu navigation
            // (most changed lines differ only by selector character).
            val changedRows = mutableListOf<Int>()
            val selectorChangedRows = mutableListOf<Int>()
            val bulletChangedRows = mutableListOf<Int>()

            for (r in 0 until contentRows) {
                val lineText = currLines[r]
                if (lineText.isBlank()) continue
                if (r < prevLines.size) {
                    val prevLine = prevLines[r]
                    if (prevLine == lineText && VirtualScreen.rowsEqual(prev[r], screen.cells[r])) {
                        continue // Identical — not changed
                    }
                }
                changedRows.add(r)
                // Check if this line differs only by selector char
                if (r < prevLines.size && stripSelector(currLines[r]) == stripSelector(prevLines[r])) {
                    selectorChangedRows.add(r)
                }
                // Check if this line differs only by leading ● (bullet blink)
                if (r < prevLines.size && stripBullet(currLines[r]) == stripBullet(prevLines[r])) {
                    bulletChangedRows.add(r)
                }
            }

            if (changedRows.size > 0 && selectorChangedRows.size == changedRows.size) {
                // Pure menu navigation — emit only the currently selected line
                for (r in selectorChangedRows) {
                    val text = currLines[r].trimStart()
                    if (text.startsWith("❯") || text.startsWith(">")) {
                        newLines.add(screen.getRowStyled(r))
                        break
                    }
                }
                return newLines
            }

            if (changedRows.size > 0 && bulletChangedRows.size == changedRows.size) {
                // Pure bullet blink — suppress entirely
                return newLines
            }

            // Not menu/bullet animation — emit genuinely new lines,
            // but skip rows that are partial cursor overwrites (chimeras).
            // A partial overwrite is when the new row shares a long common
            // prefix or suffix with the old row but has a spliced middle.
            for (r in changedRows) {
                if (r < prevLines.size && isPartialOverwrite(prevLines[r], currLines[r])) {
                    Log.v(TAG, "skip partial overwrite row $r: '${currLines[r]}'")
                    continue
                }
                newLines.add(screen.getRowStyled(r))
            }
        } else {
            for (r in newStartRow until contentRows) {
                val lineText = currLines[r]
                if (lineText.isBlank()) continue
                // In the scrolling path, rows below the overlap are "new" but may
                // still be chimeras from partial cursor overwrites. Compare against
                // the row that previously occupied this screen position.
                if (r < prevLines.size && isPartialOverwrite(prevLines[r], currLines[r], threshold = 0.4f)) {
                    Log.v(TAG, "skip partial overwrite (scroll) row $r: '${currLines[r]}'")
                    continue
                }
                newLines.add(screen.getRowStyled(r))
            }
        }

        return newLines
    }

    private fun extractNonBlankLines(): List<SpannableStringBuilder> {
        val lines = mutableListOf<SpannableStringBuilder>()
        for (r in 0 until rows - 1) { // Skip last row (potential tmux bar)
            if (isClaudeWindow && isThinkingRow(r)) continue // Skip thinking animation row
            val text = screen.getRowText(r)
            if (text.isNotBlank()) {
                lines.add(screen.getRowStyled(r))
            }
        }
        return lines
    }

    private fun getRowText(row: Array<VirtualScreen.Cell>): String {
        val sb = StringBuilder(row.size)
        for (cell in row) {
            sb.append(cell.char)
        }
        return sb.toString().trimEnd()
    }

    // --- Thinking state machine ---
    private var lastStatusText: String? = null

    private fun handleThinking(statusText: String?) {
        thinkingTimeoutJob?.cancel()

        if (statusText != null) {
            lastStatusText = statusText
        }

        when {
            statusText != null -> {
                thinkingState = ThinkingState.STATUS_TEXT
                _thinkingFlow.value = ThinkingUpdate(true, null, statusText)
            }
            else -> {
                // Animation frame: keep showing last status text if we have one
                thinkingState = if (lastStatusText != null) ThinkingState.STATUS_TEXT else ThinkingState.ANIMATING
                _thinkingFlow.value = ThinkingUpdate(true, null, lastStatusText)
            }
        }

        // Reset timeout: if no thinking chunks for 2 seconds, end thinking
        thinkingTimeoutJob = scope.launch {
            delay(2000)
            thinkingState = ThinkingState.IDLE
            lastStatusText = null
            _thinkingFlow.value = ThinkingUpdate(false, null, null)
        }
    }

    /**
     * Scan the virtual screen for thinking symbols. If found, extract
     * the full text on that row as status text. This is more reliable
     * than chunk-based detection since Claude Code writes the symbol
     * and status text as separate small chunks using VPA positioning.
     */
    private fun detectThinkingOnScreen() {
        // Find the LOWEST row with a thinking symbol in the leftmost columns (0-2).
        // Scan bottom-to-top because the active thinking symbol is near the bottom
        // of the screen (just above the status area). Old stale symbols from previous
        // frames may exist higher up and should be ignored.
        var thinkingRow = -1
        for (r in (rows - 2) downTo 0) {
            for (c in 0 until minOf(3, cols)) {
                if (screen.cells[r][c].char in THINKING_SYMBOLS) {
                    thinkingRow = r
                    break
                }
            }
            if (thinkingRow >= 0) break
        }

        if (thinkingRow >= 0) {
            this.thinkingRow = thinkingRow
            // Extract the full text on this row (after the symbol)
            val rowText = screen.getRowText(thinkingRow).trim()
            // Strip the leading symbol and whitespace
            var statusText = rowText.dropWhile { it in THINKING_SYMBOLS || it.isWhitespace() }.trim()
            // Truncate at first gap of 3+ spaces — anything after is old content
            // that wasn't cleared (e.g. status bar text from a previous screen state)
            val gapIdx = statusText.indexOf("   ")
            if (gapIdx > 0) statusText = statusText.substring(0, gapIdx).trim()
            // Reject garbled status text:
            // - Too short: fragments from character-by-character writing ("C", "on", "nsi er")
            // - Doesn't start uppercase: real status always starts with a word like "Thinking", "Running"
            //   Rejects "+9 lines ctrl-o for more" and other non-status content
            // - Contains /: file paths from old row content ("Consety/tasks/...")
            // - Contains |: status bar pipes from old row content
            if (statusText.length < 4 || statusText.firstOrNull()?.isUpperCase() != true ||
                statusText.contains('/') || statusText.contains('|')) {
                statusText = ""
            }
            Log.d(TAG, "thinking row=$thinkingRow rowText='$rowText' status='$statusText'")
            handleThinking(statusText.ifEmpty { null })
        } else if (thinkingState != ThinkingState.IDLE) {
            this.thinkingRow = -1
            // No thinking symbols on screen anymore — end thinking
            thinkingTimeoutJob?.cancel()
            thinkingState = ThinkingState.IDLE
            lastStatusText = null
            _thinkingFlow.value = ThinkingUpdate(false, null, null)
        }
    }

    // --- Tmux bar extraction ---

    private fun extractTmuxBar() {
        // The tmux bar is typically the last row of the screen.
        // Tmux format: "[session] 0:bash  1:claude*  2:htop-"
        //   - One [session] bracket at the start
        //   - Then space-separated window entries: N:name
        //   - Active window has * suffix, last window has - suffix
        val lastRow = screen.getRowText(rows - 1)
        Log.d(TAG, "extractTmuxBar lastRow='${lastRow.take(80)}'")
        if (lastRow.isBlank()) return

        // Must start with [session_name] to be a tmux bar
        val sessionMatch = "^\\[([^\\]]+)]\\s+".toRegex().find(lastRow) ?: return

        // Extract window entries from the rest of the line.
        // Windows are left-aligned, separated by 1-2 spaces. Right-side status
        // (hostname, time) is separated by a large gap (3+ spaces).
        // Truncate at the first gap of 3+ spaces to avoid matching e.g. "14:32".
        val afterSession = lastRow.substring(sessionMatch.range.last + 1)
        val gapIndex = afterSession.indexOf("   ")
        val windowsText = if (gapIndex >= 0) afterSession.substring(0, gapIndex) else afterSession

        val windowPattern = "(\\d+):(\\S+)".toRegex()
        val matches = windowPattern.findAll(windowsText).toList()

        if (matches.isEmpty()) return

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

        val activeIndex = windows.indexOfFirst { it.isActive }.takeIf { it >= 0 } ?: 0
        val activeWindow = windows.getOrNull(activeIndex)
        if (activeWindow != null) {
            isClaudeWindow = activeWindow.name.contains("claude", ignoreCase = true)
        }
        Log.d(TAG, "tmuxBar parsed: windows=${windows.map { "${it.index}:${it.name}${if (it.isActive) "*" else ""}" }} isClaudeWindow=$isClaudeWindow")
        _tmuxBarFlow.value = TmuxBarUpdate(windows, activeIndex)
    }

    private fun stripAnsiCodes(raw: String): String {
        return raw.replace(Regex("\u001b\\[[0-9;]*[a-zA-Z]"), "")
            .replace(Regex("\u001b\\][^\u0007]*\u0007"), "")
            .replace(Regex("\u001b[()][A-Z0-9]"), "")
    }

    /** Reset diff state (e.g. after tmux window switch). */
    fun resetDiffState() {
        previousSnapshot = null
    }

    fun destroy() {
        thinkingTimeoutJob?.cancel()
        scope.cancel()
    }

    // --- Data classes ---

    enum class ChunkType {
        TMUX_BAR,
        FULL_REDRAW,
        INCREMENTAL
    }
}

data class ThinkingUpdate(
    val isThinking: Boolean,
    val symbol: Char?,
    val statusText: String?
)

data class TmuxBarUpdate(
    val windows: List<TmuxWindow>,
    val activeIndex: Int
)

data class TmuxWindow(
    val index: Int,
    val name: String,
    val isActive: Boolean
)
