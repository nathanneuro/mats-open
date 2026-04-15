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
    private val cols: Int = 120,
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

    // Output flows — buffer capacities are intentionally small. Under heavy load
    // (e.g. 20k lines/sec), tryEmit() will drop oldest emissions rather than
    // queueing unbounded. This is fine: the content lives on disk via HistoryBuffer,
    // and the UI only needs to show the latest screenful.
    private val _contentFlow = MutableSharedFlow<List<SpannableStringBuilder>>(extraBufferCapacity = 32)
    val contentFlow: SharedFlow<List<SpannableStringBuilder>> = _contentFlow

    private val _thinkingFlow = MutableStateFlow(ThinkingUpdate(false, null, null))
    val thinkingFlow: StateFlow<ThinkingUpdate> = _thinkingFlow

    private val _tmuxBarFlow = MutableStateFlow<TmuxBarUpdate?>(null)
    val tmuxBarFlow: StateFlow<TmuxBarUpdate?> = _tmuxBarFlow

    private val _statusBarFlow = MutableStateFlow<String?>(null)
    val statusBarFlow: StateFlow<String?> = _statusBarFlow

    // Plain text flow for history persistence — larger buffer since disk writes
    // are fast and we don't want to lose history.
    private val _plainTextFlow = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val plainTextFlow: SharedFlow<String> = _plainTextFlow

    // Raw (un-deduplicated, un-filtered) parallel flows. These bypass
    // postProcessLines so the dirty history captures everything the screen
    // diff produced — used as a backup when dedup heuristics steal info.
    private val _rawContentFlow = MutableSharedFlow<List<SpannableStringBuilder>>(extraBufferCapacity = 64)
    val rawContentFlow: SharedFlow<List<SpannableStringBuilder>> = _rawContentFlow

    private val _rawPlainTextFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val rawPlainTextFlow: SharedFlow<String> = _rawPlainTextFlow

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
        private const val MIN_DIFF_INTERVAL_MS = 400L
        // Adaptive throttle: if chunks arrive faster than this, increase diff interval
        private const val HIGH_THROUGHPUT_DIFF_INTERVAL_MS = 800L
        private const val HIGH_THROUGHPUT_THRESHOLD_MS = 20L // chunks <20ms apart = deluge
        private const val TAG = "OutputProcessor"
        // Max recent emitted line texts for exact-text dedup
        private const val MAX_RECENT_EMITTED = 100
    }

    // Exact-text dedup: skip lines already emitted recently.
    // Prevents re-emitting the entire visible screen on each tmux full redraw
    // (e.g. `tail -f` scrolls by 1 line but tmux sends all 22 rows).
    private val recentEmitted = LinkedHashSet<String>()

    // Whether the active tmux window is running Claude Code.
    // When false, Claude-specific filters (thinking detection, status area,
    // fence lines, etc.) are bypassed so non-Claude windows render cleanly.
    // Detection: primary = tmux window name contains "claude";
    // fallback = content-based heuristic (seeing Claude UI elements on screen).
    @Volatile
    var isClaudeWindow: Boolean = false

    // Content-based Claude detection: count of recent screen frames that had
    // Claude Code UI elements (thinking symbols in left columns + fence/prompt pattern).
    // If we see Claude UI elements in 3+ of the last 5 frames, assume Claude is running
    // even if the tmux window isn't named "claude".
    private var claudeContentHits = 0
    private var claudeContentChecks = 0
    private var claudeDetectedByName = false

    // Throttle: skip expensive diffs for rapid-fire small chunks (thinking animation)
    private var lastDiffTime = 0L
    private var lastChunkTime = 0L
    private var consecutiveFastChunks = 0
    private var lastStatusUpdateTime = 0L
    // Per-window status bar cache: saves each window's last status so it
    // restores when cycling back, but doesn't bleed between windows.
    private val statusBarByWindow = mutableMapOf<Int, String>()
    private var currentWindowIndex = -1
    // Track "accept edits" visibility — appends " a" to status bar when active
    @Volatile var acceptEditsVisible = false
    private var lastBaseStatus: String? = null // status without the " a" suffix

    /**
     * Process a raw SSH output chunk. This is the main entry point,
     * called for every chunk emitted by SshManager.outputFlow.
     */
    fun processRawOutput(raw: String) {
        val now = System.currentTimeMillis()
        val chunkType = classifyChunk(raw)
        val escaped = raw.replace("\u001b", "⎋").replace("\n", "↵").replace("\r", "⏎")
        Log.d(TAG, "chunk len=${raw.length} type=$chunkType: ${escaped.take(200)}")

        // Track chunk arrival rate for adaptive throttling
        if (now - lastChunkTime < HIGH_THROUGHPUT_THRESHOLD_MS) {
            consecutiveFastChunks++
        } else {
            consecutiveFastChunks = maxOf(0, consecutiveFastChunks - 1)
        }
        lastChunkTime = now

        // Adaptive diff interval: widen the throttle window under high throughput
        // to avoid overwhelming the UI with diffs. Content still goes to disk.
        val effectiveDiffInterval = if (consecutiveFastChunks > 10) {
            HIGH_THROUGHPUT_DIFF_INTERVAL_MS
        } else {
            MIN_DIFF_INTERVAL_MS
        }

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
                // Only throttle small chunks (cursor moves, thinking symbol writes).
                // Larger chunks (>40 bytes) may contain real content like menu options.
                if (raw.length < 40) {
                    if (isClaudeWindow) detectThinkingOnScreen()
                    if (now - lastDiffTime < effectiveDiffInterval) {
                        return
                    }
                }
            }
        }

        if (isClaudeWindow) detectThinkingOnScreen()

        // On full redraws, run content-based Claude detection for windows
        // not named "claude" (e.g. running claude inside a "bash" window)
        if (chunkType == ChunkType.FULL_REDRAW && !claudeDetectedByName) {
            detectClaudeByContent()
        }

        lastDiffTime = System.currentTimeMillis()
        val rawLines = diffScreens(chunkType)

        // Emit raw lines (pre-filter, pre-dedup) for the dirty history.
        // Still drop fully blank lines so the file isn't padded with whitespace.
        val rawNonBlank = rawLines.filter { it.toString().isNotBlank() }
        if (rawNonBlank.isNotEmpty()) {
            _rawContentFlow.tryEmit(rawNonBlank)
            val rawPlain = rawNonBlank.joinToString("\n") { it.toString() }
            _rawPlainTextFlow.tryEmit(rawPlain + "\n")
        }

        val newLines = postProcessLines(rawLines)
        if (newLines.isNotEmpty()) {
            // Exact-text dedup: skip lines already emitted recently.
            val dedupedLines = newLines.filter { line ->
                val text = line.toString()
                if (text.isBlank()) return@filter true
                if (text in recentEmitted) {
                    Log.v(TAG, "dedup skip: '${text.take(80)}'")
                    return@filter false
                }
                recentEmitted.add(text)
                while (recentEmitted.size > MAX_RECENT_EMITTED) {
                    recentEmitted.iterator().next().also { recentEmitted.remove(it) }
                }
                true
            }
            if (dedupedLines.isNotEmpty()) {
                for (line in dedupedLines) {
                    Log.d(TAG, "emit: '${line.toString().take(120)}'")
                }
                _contentFlow.tryEmit(dedupedLines)
                val plainText = dedupedLines.joinToString("\n") { it.toString() }
                if (plainText.isNotBlank()) {
                    _plainTextFlow.tryEmit(plainText + "\n")
                }
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

                // Drop Claude Code context/model indicators: half-circle + word
                // e.g. "◑ medium", "◐ large", "● compact" — these are status area UI
                if (trimmed.length < 20 && Regex("^[◑◐◒◓●○◔◕]\\s+\\w+$").matches(trimmed)) {
                    Log.v(TAG, "filter CONTEXT_INDICATOR: '$text'")
                    continue
                }

                // Drop "accept edits" hint and track it for status bar suffix
                if (trimmed.contains("accept edits")) {
                    Log.v(TAG, "filter ACCEPT_EDITS: '$text'")
                    if (!acceptEditsVisible) {
                        acceptEditsVisible = true
                        refreshStatusBarSuffix()
                    }
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
                    if (statusText.isNotEmpty() && statusText.length <= 40) {
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
                if (flushedText.isNotEmpty()) {
                    Log.d(TAG, "flush PROMPT: '$flushedText'")
                    result.add(flushed)
                }
                pendingPrompt = null
            }

            // Newest text wins: always emit new lines, never suppress in favor
            // of previously emitted content. The terminal renders early-to-late,
            // so the latest version of any line is what the user should see.
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
        val skelPrev = TerminalView.skeleton(prev)
        val skelCurr = TerminalView.skeleton(curr)
        // Same skeleton (only numbers differ) → new data line, not chimera.
        if (skelPrev == skelCurr) return false
        // One skeleton is a prefix of the other → same line with extra/fewer
        // fields (e.g. training step with occasional util/ent/gdc columns).
        if (skelPrev.startsWith(skelCurr) || skelCurr.startsWith(skelPrev)) return false
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
        // User's status line starts with ∆ (U+2206) or Δ (U+0394) sentinel.
        // End sentinel ~ may be truncated if line is too long, so only require start.
        return text.contains('∆') || text.contains('Δ')
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
    private val CIRCLE_SYMBOLS = setOf('◑', '◐', '◒', '◓', '●', '○', '◔', '◕')

    private fun isStatusAreaLine(text: String): Boolean {
        val trimmed = text.trimStart()
        return (isFenceLine(text) && text.length > 20) ||
            trimmed.startsWith("⏵") || // accept/reject hints
            (trimmed.isNotEmpty() && trimmed[0] in CIRCLE_SYMBOLS) // context indicator (◑ medium)
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
        var foundAcceptEdits = false

        for (r in (maxRow - 1) downTo maxOf(0, maxRow - 10)) {
            val text = screen.getRowText(r)
            if (text.contains("accept edits")) foundAcceptEdits = true
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

        // Update accept edits tracking
        if (foundAcceptEdits != acceptEditsVisible) {
            acceptEditsVisible = foundAcceptEdits
            refreshStatusBarSuffix()
        }

        if (topStatusRow < 0) {
            // Don't null out — keep cached value. A missing status area
            // on one parse is likely a transient redraw, not a real change.
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

            // Only update if we found status lines — don't null out
            // a good cached value just because one parse missed them
            if (statusLines.isNotEmpty()) {
                var combined = statusLines.joinToString(" ") { it.trim() }
                    .replace(Regex("\\s{2,}"), " ")
                // Strip sentinels: ∆/Δ at start, ~ and everything after at end
                combined = combined.replace("∆", "").replace("Δ", "")
                val tildeIdx = combined.indexOf('~')
                if (tildeIdx >= 0) combined = combined.substring(0, tildeIdx)
                combined = combined.trim()
                if (combined.isNotEmpty()) {
                    lastBaseStatus = combined
                    emitStatusBar(combined)
                }
            }
        }


        return topStatusRow
    }

    /** Emit status bar with optional " a" suffix for accept edits. */
    private fun emitStatusBar(base: String) {
        lastBaseStatus = base
        val display = if (acceptEditsVisible) "$base a" else base
        _statusBarFlow.value = display
        if (currentWindowIndex >= 0) {
            statusBarByWindow[currentWindowIndex] = display
        }
    }

    /** Re-emit status bar when accept edits state changes. */
    private fun refreshStatusBarSuffix() {
        val base = lastBaseStatus ?: return
        emitStatusBar(base)
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
        // Only update previousSnapshot on FULL_REDRAW. Intermediate incremental
        // chunks (cursor moves, attribute changes) would pollute the snapshot and
        // break overlap detection on the next full redraw.
        if (chunkType == ChunkType.FULL_REDRAW) {
            previousSnapshot = screen.snapshot()
        }

        if (prev == null) {
            // First screen - emit all non-blank lines
            return extractNonBlankLines()
        }

        // Exclude last row (tmux bar). Detect Claude's status area only on Claude windows.
        val maxRow = rows - 1
        val contentRows = if (isClaudeWindow) {
            // Update status bar on full redraws (reliable) and periodically
            // on incremental diffs (every ~2s) to keep it fresh without
            // flooding the main thread.
            val updateStatus = chunkType == ChunkType.FULL_REDRAW ||
                (System.currentTimeMillis() - lastStatusUpdateTime > 2000L)
            if (updateStatus) lastStatusUpdateTime = System.currentTimeMillis()
            detectStatusArea(maxRow, updateStatusBar = updateStatus)
        } else {
            // Non-Claude window: hide status bar (per-window cache
            // handles restore on window switch via notifyTmuxWindowNext)
            _statusBarFlow.value = null
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
            // Only accept a complete status message: symbol + word + ellipsis.
            // Claude Code animates a color gradient across the thinking text,
            // so partial renders only contain the colored fragment ("Think"
            // instead of "Thinking..."). Requiring an ellipsis ensures we only
            // update with the full message. Additional guards:
            // - Too short: fragments from character-by-character writing
            // - Too long (>40): leaked content from another row
            // - Doesn't start uppercase: not a real status word
            // - Contains / or |: stale row content (file paths, status bar pipes)
            val hasEllipsis = statusText.contains("...") || statusText.contains("…") ||
                statusText.contains("\u2024") || statusText.contains("\u2025")
            if (!hasEllipsis || statusText.length < 4 || statusText.length > 40 ||
                statusText.firstOrNull()?.isUpperCase() != true ||
                statusText.contains('/') || statusText.contains('|')) {
                statusText = ""
            }
            Log.d(TAG, "thinking row=$thinkingRow rowText='$rowText' status='$statusText'")
            handleThinking(statusText.ifEmpty { null })
        } else if (thinkingState != ThinkingState.IDLE) {
            this.thinkingRow = -1
            // No thinking symbols on screen — let the 2s timeout handle
            // the transition to IDLE rather than clearing immediately.
            // A single frame without a symbol could be a rendering glitch.
        }
    }

    // --- Tmux bar extraction ---

    // Last known-good window list. Merged with each new parse to handle
    // tmux truncating names on narrow screens.
    private var cachedWindows: List<TmuxWindow>? = null
    // After a user-initiated window switch, ignore parsed active flags
    // for this duration to let tmux finish redrawing the bar.
    private var userSwitchUntil = 0L

    /**
     * Called when the user explicitly switches tmux windows (next/prev button).
     * Advances the active flag in the cache immediately so the UI updates
     * without waiting for a clean tmux bar parse.
     */
    fun notifyTmuxWindowNext() {
        val cached = cachedWindows ?: return
        val currentIdx = cached.indexOfFirst { it.isActive }
        if (currentIdx < 0) return
        val nextIdx = (currentIdx + 1) % cached.size
        // Save current window's status bar before switching
        val currentWinIdx = cached.getOrNull(currentIdx)?.index ?: -1
        val currentStatus = _statusBarFlow.value
        if (currentWinIdx >= 0 && currentStatus != null) {
            statusBarByWindow[currentWinIdx] = currentStatus
        }

        cachedWindows = cached.mapIndexed { i, w -> w.copy(isActive = i == nextIdx) }
        userSwitchUntil = System.currentTimeMillis() + 2000L

        // Restore next window's cached status bar, or clear
        val nextWinIdx = cached.getOrNull(nextIdx)?.index ?: -1
        currentWindowIndex = nextWinIdx
        _statusBarFlow.value = statusBarByWindow[nextWinIdx]
        val updated = cachedWindows!!
        val activeIndex = updated.indexOfFirst { it.isActive }
        val activeWindow = updated.getOrNull(activeIndex)
        if (activeWindow != null) {
            claudeDetectedByName = activeWindow.name.contains("claude", ignoreCase = true)
            isClaudeWindow = claudeDetectedByName
        }
        _tmuxBarFlow.value = TmuxBarUpdate(updated, activeIndex)
    }

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

        // Extract window entries from the full line after the session tag.
        // Tmux bar example:
        //   [0] <ude  1:zshM 2:zsh- 3:claude* 5:claude [0,0] "⠐ Claude Code" 18:32 16-Mar-26
        // Windows are N:name with optional flags (*=active, -=last, M=marked, Z=zoomed).
        // Tmux may truncate long names with < prefix (e.g. "<ude" for "claude").
        // Right-side has pane info [r,c], quoted title, time, date — we just
        // scan the whole line for the N:word pattern and ignore non-window matches.
        val afterSession = lastRow.substring(sessionMatch.range.last + 1)

        // Match N:name with optional trailing flags. Name is \w+ (letters/digits/underscore).
        // This skips timestamps like "18:32" because the "name" part would be all digits,
        // which we filter out below.
        val windowPattern = "\\b(\\d+):(\\w+?)([*\\-MZ]*)(?=\\s|$)".toRegex()
        val matches = windowPattern.findAll(afterSession).toList()

        if (matches.isEmpty()) return

        val windows = matches.mapNotNull { match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val name = match.groupValues[2]
            val flags = match.groupValues[3]
            // Skip entries where "name" is all digits — likely a timestamp (18:32)
            if (name.all { it.isDigit() }) return@mapNotNull null
            val isActive = '*' in flags
            TmuxWindow(
                index = index,
                name = name,
                isActive = isActive
            )
        }

        if (windows.isEmpty()) return

        // Merge parsed windows with cache for a complete picture.
        // Tmux truncates the bar on narrow screens, so some windows may be
        // missing from any given parse. Strategy:
        //   - Merge: union of cached + parsed windows (parsed names win on conflict)
        //   - Active flag: always from the current parse (tmux reliably shows the active window)
        //   - Removal: a window is only removed from cache if we get a "full" parse
        //     (same or more windows than cache) that doesn't include it.
        val cached = cachedWindows
        val userSwitchActive = System.currentTimeMillis() < userSwitchUntil
        val activeFromParse = windows.filter { it.isActive }.map { it.index }.toSet()
        val finalWindows: List<TmuxWindow>

        if (cached != null) {
            val parsedByIndex = windows.associateBy { it.index }
            val cachedByIndex = cached.associateBy { it.index }
            val parsedIndices = parsedByIndex.keys
            val cachedIndices = cachedByIndex.keys

            // Detect new windows: indices in parse but not in cache
            val newIndices = parsedIndices - cachedIndices
            // A "full" parse has >= cached count, meaning it saw the whole bar
            val isFullParse = windows.size >= cached.size

            if (isFullParse) {
                // Trust the parse completely — it's authoritative.
                // Any cached window not in this parse has been removed.
                val removedIndices = cachedIndices - parsedIndices
                for (idx in removedIndices) {
                    statusBarByWindow.remove(idx)
                }
                finalWindows = windows.map { w ->
                    val isActive = if (userSwitchActive) {
                        cachedByIndex[w.index]?.isActive ?: (w.index in activeFromParse)
                    } else {
                        w.index in activeFromParse
                    }
                    w.copy(isActive = isActive)
                }
            } else {
                // Partial parse — merge with cache, but incorporate new windows
                val allIndices = (cachedIndices + parsedIndices).sorted()
                finalWindows = allIndices.map { idx ->
                    val parsed = parsedByIndex[idx]
                    val prev = cachedByIndex[idx]
                    val name = parsed?.name ?: prev?.name ?: "?"
                    val isActive = if (userSwitchActive) {
                        prev?.isActive ?: (idx in activeFromParse)
                    } else {
                        idx in activeFromParse
                    }
                    TmuxWindow(index = idx, name = name, isActive = isActive)
                }
            }
        } else {
            finalWindows = windows
        }
        cachedWindows = finalWindows

        val activeIndex = finalWindows.indexOfFirst { it.isActive }.takeIf { it >= 0 } ?: 0
        val activeWindow = finalWindows.getOrNull(activeIndex)
        if (activeWindow != null) {
            currentWindowIndex = activeWindow.index
            claudeDetectedByName = activeWindow.name.contains("claude", ignoreCase = true)
            if (claudeDetectedByName) {
                isClaudeWindow = true
                claudeContentHits = 0
                claudeContentChecks = 0
                // (status bar is per-window, not restored across switches)
            } else {
                // Not named "claude" — immediately turn off Claude mode.
                // Content-based detection is too prone to false positives
                // (thinking symbols appear in other programs, ❯ is a common prompt).
                isClaudeWindow = false
                claudeContentHits = 0
                claudeContentChecks = 0
            }
        }
        Log.d(TAG, "tmuxBar parsed: windows=${finalWindows.map { "${it.index}:${it.name}${if (it.isActive) "*" else ""}" }} isClaudeWindow=$isClaudeWindow (byName=$claudeDetectedByName)")
        _tmuxBarFlow.value = TmuxBarUpdate(finalWindows, activeIndex)
    }

    private fun stripAnsiCodes(raw: String): String {
        return raw.replace(Regex("\u001b\\[[0-9;]*[a-zA-Z]"), "")
            .replace(Regex("\u001b\\][^\u0007]*\u0007"), "")
            .replace(Regex("\u001b[()][A-Z0-9]"), "")
    }

    /**
     * Content-based Claude detection: scan the screen for Claude Code UI patterns.
     * Called on FULL_REDRAW when window isn't named "claude" to detect Claude
     * running inside a generically-named window (bash, zsh, etc.).
     */
    private fun detectClaudeByContent() {
        if (claudeDetectedByName) return // Already detected by name, skip

        var hits = 0
        // Check for thinking symbol in left columns
        for (r in 0 until rows - 1) {
            for (c in 0 until minOf(3, cols)) {
                if (screen.cells[r][c].char in THINKING_SYMBOLS) { hits++; break }
            }
        }
        // Check for Claude prompt (❯) on any row
        for (r in 0 until rows - 1) {
            val text = screen.getRowText(r).trimStart()
            if (text.startsWith("❯")) { hits++; break }
        }
        // Check for fence lines (Claude status area)
        var fenceCount = 0
        for (r in rows - 6 until rows - 1) {
            if (r < 0) continue
            val text = screen.getRowText(r)
            if (isFenceLine(text) && text.trim().length > 20) fenceCount++
        }
        if (fenceCount >= 2) hits++ // Two fences = status area

        claudeContentChecks++
        if (hits >= 2) claudeContentHits++

        // Rolling window: keep only last 5 checks
        if (claudeContentChecks > 5) {
            claudeContentChecks = 5
            claudeContentHits = minOf(claudeContentHits, 5)
        }

        // Update flag if we have enough data and not detected by name
        if (claudeContentChecks >= 3) {
            val detected = claudeContentHits >= 2
            if (detected != isClaudeWindow) {
                Log.d(TAG, "Claude content detection: $isClaudeWindow → $detected (hits=$claudeContentHits/$claudeContentChecks)")
                isClaudeWindow = detected
            }
        }
    }

    /** Reset diff state (e.g. after tmux window switch). */
    fun resetDiffState() {
        previousSnapshot = null
        claudeContentHits = 0
        claudeContentChecks = 0
    }

    /** Full reset on reconnect — clear all cached state. */
    fun resetAllState() {
        resetDiffState()
        cachedWindows = null
        currentWindowIndex = -1
        userSwitchUntil = 0L
        statusBarByWindow.clear()
        isClaudeWindow = false
        claudeDetectedByName = false
        acceptEditsVisible = false
        lastBaseStatus = null
        thinkingState = ThinkingState.IDLE
        lastStatusText = null
        thinkingTimeoutJob?.cancel()
        _thinkingFlow.value = ThinkingUpdate(false, null, null)
        _tmuxBarFlow.value = null
        _statusBarFlow.value = null
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
