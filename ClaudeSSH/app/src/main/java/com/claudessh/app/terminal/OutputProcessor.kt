package com.claudessh.app.terminal

import android.text.SpannableStringBuilder
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
        private val STATUS_LINE_PATTERN = Regex(
            "^\\s*[$THINKING_SYMBOLS_STR]\\s+([A-Z][a-z]{2,}(?:\\s+\\w+){0,3})[.…\u2024\u2025]{1,3}\\s*$" +
            "|^\\s*([A-Z][a-z]{2,}(?:\\s+\\w+){0,3})[.…\u2024\u2025]{1,3}\\s*$"
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
        private const val MIN_DIFF_INTERVAL_MS = 80L
    }

    // Throttle: skip expensive diffs for rapid-fire small chunks (thinking animation)
    private var lastDiffTime = 0L

    /**
     * Process a raw SSH output chunk. This is the main entry point,
     * called for every chunk emitted by SshManager.outputFlow.
     */
    fun processRawOutput(raw: String) {
        val chunkType = classifyChunk(raw)

        // Feed to screen interpreter
        interpreter.feed(raw)

        when (chunkType) {
            ChunkType.TMUX_BAR -> {
                extractTmuxBar()
                return
            }
            ChunkType.FULL_REDRAW -> {
                extractTmuxBar()
            }
            ChunkType.INCREMENTAL -> {
                // Small incremental chunks (thinking animation frames) arrive very
                // rapidly (~10/sec). Skip expensive diff if we diffed recently —
                // just update thinking state. The next larger chunk or the next
                // chunk after the throttle window will pick up any content changes.
                if (raw.length < 60) {
                    detectThinkingOnScreen()
                    val now = System.currentTimeMillis()
                    if (now - lastDiffTime < MIN_DIFF_INTERVAL_MS) {
                        return
                    }
                }
            }
        }

        detectThinkingOnScreen()

        lastDiffTime = System.currentTimeMillis()
        val rawLines = diffScreens()
        val newLines = postProcessLines(rawLines)
        if (newLines.isNotEmpty()) {
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

    /**
     * Post-process content lines for phone display:
     * - Trim fence lines to displayCols width
     * - Filter lone thinking symbols that leaked past chunk classification
     */
    private fun postProcessLines(lines: List<SpannableStringBuilder>): List<SpannableStringBuilder> {
        val result = mutableListOf<SpannableStringBuilder>()
        for (line in lines) {
            val text = line.toString()

            // Drop lines that are only a thinking symbol (leaked animation frame)
            if (LONE_THINKING_SYMBOL.matches(text)) continue

            // Drop tmux bar lines that leaked into content
            if (TMUX_BAR_LINE.matches(text)) continue

            // Filter status lines (e.g. "✶ Compacting conversation…") → show in thinking bar
            val statusMatch = STATUS_LINE_PATTERN.find(text)
            if (statusMatch != null) {
                // Strip trailing dots/ellipsis for clean display
                val rawStatus = statusMatch.groupValues[1].trim()
                val statusText = ELLIPSIS.replace(rawStatus, "").trim()
                if (statusText.isNotEmpty()) {
                    handleThinking(statusText)
                }
                continue
            }

            // Trim fence lines to phone width
            if (text.length > displayCols && isFenceLine(text)) {
                val trimmed = SpannableStringBuilder(line, 0, displayCols)
                result.add(trimmed)
                continue
            }

            result.add(line)
        }
        return result
    }

    /** Strip selector/cursor characters for fuzzy line comparison. */
    private fun stripSelector(text: String): String {
        return text.replace("❯", " ").replace(">", " ").trimStart()
    }

    /** A line looks like Claude Code status info if it has 2+ pipe separators. */
    private fun isStatusInfoLine(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 5) return false
        return trimmed.count { it == '|' } >= 2
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
     * Also extracts and emits the status text.
     */
    private fun detectStatusArea(maxRow: Int): Int {
        // Scan upward from the bottom to find the top fence of the status area.
        // Claude Code layout (bottom of screen):
        //   Row N:   ──────────── (top fence)
        //   Row N+1: ❯            (prompt — uses ❯ or >)
        //   Row N+2: ──────────── (bottom fence, possibly doubled)
        //   Row N+3: ──────────── (optional second fence)
        //   Row N+4: status info (path | CPU | RAM | GPU...)
        //   Row N+5: more status...
        //   Row N+6: ⏵⏵ accept edits on · ...
        //   Last:    [0] bash [1] claude* (tmux bar — already excluded)
        var topFenceRow = -1

        for (r in (maxRow - 1) downTo maxOf(0, maxRow - 8)) {
            val text = screen.getRowText(r)
            if (isFenceLine(text) && text.length > 20) {
                // Check if the row below is a prompt or another fence or blank
                if (r + 1 < maxRow) {
                    val below = screen.getRowText(r + 1).trimStart()
                    if (below.startsWith(">") || below.startsWith("❯") ||
                        below.isEmpty() || (isFenceLine(below) && below.length > 20)) {
                        topFenceRow = r
                        // Keep scanning up — we want the TOPMOST fence in the cluster
                        continue
                    }
                }
                if (topFenceRow >= 0) break // Found a non-fence/non-prompt, stop
            } else if (topFenceRow >= 0) {
                break // First non-fence row above the area, stop
            }
        }

        // If fence scan didn't find anything, try finding status lines by | separators
        // Claude Code status looks like: "path | CPU 12% | RAM 4.2G | GPU 85%"
        if (topFenceRow < 0) {
            for (r in (maxRow - 1) downTo maxOf(0, maxRow - 6)) {
                val text = screen.getRowText(r)
                if (isStatusInfoLine(text)) {
                    // Found a status line — scan upward for fences/prompt above it
                    topFenceRow = r
                    for (above in (r - 1) downTo maxOf(0, r - 4)) {
                        val aboveText = screen.getRowText(above)
                        val trimmed = aboveText.trimStart()
                        if (isFenceLine(aboveText) && aboveText.length > 20) {
                            topFenceRow = above
                        } else if (trimmed.startsWith(">") || trimmed.startsWith("❯")) {
                            topFenceRow = above
                        } else {
                            break
                        }
                    }
                    break
                }
            }
        }

        if (topFenceRow < 0) {
            _statusBarFlow.value = null
            return maxRow
        }

        // Collect status lines: everything below fences/prompt that isn't a fence
        val statusLines = mutableListOf<String>()
        for (r in (topFenceRow + 1) until maxRow) {
            val text = screen.getRowText(r)
            // Skip fences and the prompt line
            if (isFenceLine(text) && text.length > 20) continue
            val trimmed = text.trimStart()
            if (trimmed.startsWith(">") || trimmed.startsWith("❯")) continue
            if (text.isNotBlank()) {
                statusLines.add(text)
            }
        }
        // Also check if topFenceRow itself is a status info line (when no fence above)
        val topText = screen.getRowText(topFenceRow)
        if (isStatusInfoLine(topText)) {
            statusLines.add(0, topText)
        }

        _statusBarFlow.value = if (statusLines.isNotEmpty()) {
            // Collapse into single line, collapse whitespace
            statusLines.joinToString(" ") { it.trim() }
                .replace(Regex("\\s{2,}"), " ")
        } else {
            null
        }

        return topFenceRow
    }

    /** Same detection but on a raw snapshot array (for previous screen). */
    private fun detectStatusAreaInSnapshot(snapshot: Array<Array<VirtualScreen.Cell>>, maxRow: Int): Int {
        var topFenceRow = -1
        for (r in (maxRow - 1) downTo maxOf(0, maxRow - 8)) {
            val text = getRowText(snapshot[r])
            if (isFenceLine(text) && text.length > 20) {
                if (r + 1 < maxRow) {
                    val below = getRowText(snapshot[r + 1]).trimStart()
                    if (below.startsWith(">") || below.startsWith("❯") ||
                        below.isEmpty() || (isFenceLine(below) && below.length > 20)) {
                        topFenceRow = r
                        continue
                    }
                }
                if (topFenceRow >= 0) break
            } else if (topFenceRow >= 0) {
                break
            }
        }
        // Fallback: look for | separated status lines
        if (topFenceRow < 0) {
            for (r in (maxRow - 1) downTo maxOf(0, maxRow - 6)) {
                val text = getRowText(snapshot[r])
                if (isStatusInfoLine(text)) {
                    topFenceRow = r
                    for (above in (r - 1) downTo maxOf(0, r - 4)) {
                        val aboveText = getRowText(snapshot[above])
                        val trimmed = aboveText.trimStart()
                        if (isFenceLine(aboveText) && aboveText.length > 20) {
                            topFenceRow = above
                        } else if (trimmed.startsWith(">") || trimmed.startsWith("❯")) {
                            topFenceRow = above
                        } else {
                            break
                        }
                    }
                    break
                }
            }
        }
        return if (topFenceRow >= 0) topFenceRow else maxRow
    }

    /**
     * Diff current screen against previous snapshot.
     * Returns only genuinely new lines (not present in previous screen).
     */
    private fun diffScreens(): List<SpannableStringBuilder> {
        val prev = previousSnapshot
        previousSnapshot = screen.snapshot()

        if (prev == null) {
            // First screen - emit all non-blank lines
            return extractNonBlankLines()
        }

        // Exclude last row (tmux bar) and detect status area
        val maxRow = rows - 1
        val contentRows = detectStatusArea(maxRow)

        val prevContentRows = detectStatusAreaInSnapshot(prev, maxRow)

        // Build line lists, but mark thinking row as blank so it's excluded from diff
        val tRow = thinkingRow
        val prevLines = (0 until prevContentRows).map {
            if (it == tRow) "" else getRowText(prev[it])
        }
        val currLines = (0 until contentRows).map {
            if (it == tRow) "" else screen.getRowText(it)
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

            // Not menu navigation — emit genuinely new lines
            for (r in changedRows) {
                newLines.add(screen.getRowStyled(r))
            }
        } else {
            for (r in newStartRow until contentRows) {
                val lineText = currLines[r]
                if (lineText.isBlank()) continue
                newLines.add(screen.getRowStyled(r))
            }
        }

        return newLines
    }

    private fun extractNonBlankLines(): List<SpannableStringBuilder> {
        val lines = mutableListOf<SpannableStringBuilder>()
        for (r in 0 until rows - 1) { // Skip last row (potential tmux bar)
            if (r == thinkingRow) continue // Skip thinking animation row
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
        // Find the first row with a thinking symbol in the leftmost columns (0-2).
        // Claude Code places the animation symbol at column 0 or 1.
        // Only checking first few columns avoids false positives from * or · in content.
        var thinkingRow = -1
        for (r in 0 until rows - 1) {
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
            val statusText = rowText.dropWhile { it in THINKING_SYMBOLS || it.isWhitespace() }.trim()
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
