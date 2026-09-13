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
    cols: Int = 100,
    rows: Int = 24,
    private val displayCols: Int = 40,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    // Virtual screens for diffing. cols/rows are mutable via resize() so the
    // user-tunable "emulated terminal width" setting can take effect on a live
    // session without reconnecting.
    private var cols: Int = cols
    private var rows: Int = rows
    private var screen = VirtualScreen(this.cols, this.rows)
    private var interpreter = AnsiScreenInterpreter(screen)
    private var previousSnapshot: Array<Array<VirtualScreen.Cell>>? = null

    init {
        installInterpreterCallbacks()
    }

    /** Reallocate the virtual screen at a new size. The previous snapshot is
     *  invalidated so the next chunk re-emits its non-blank rows rather than
     *  diffing against a mismatched-shape grid. */
    fun resize(newCols: Int, newRows: Int = rows) {
        if (newCols == cols && newRows == rows) return
        cols = newCols
        rows = newRows
        screen = VirtualScreen(cols, rows)
        interpreter = AnsiScreenInterpreter(screen)
        installInterpreterCallbacks()
        previousSnapshot = null
    }

    /** Re-attach interpreter-level callbacks after the interpreter is
     *  (re)constructed. Currently: forward ESC[3J in non-Claude windows
     *  to the clearScrollback flow so the host can wipe terminal + history. */
    private fun installInterpreterCallbacks() {
        interpreter.onScrollbackErase = {
            if (!isAgentWindow) {
                _clearScrollbackFlow.tryEmit(Unit)
            }
        }
        interpreter.onTitle = { title ->
            // Codex spins a braille frame in the terminal title for the whole
            // duration of a turn — a cheaper and steadier working signal than
            // the on-screen spinner row, which only repaints once a second.
            if (isCodexWindow && CodexUi.titleShowsSpinner(title)) {
                handleThinking(null)
            }
        }
    }

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

    // Fired when the user invokes `clear` (ESC[3J) in a non-Claude window.
    // The host should wipe both the on-screen text and the per-window
    // disk history so scrolling up after `clear` shows nothing — matching
    // bash's semantics.
    private val _clearScrollbackFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val clearScrollbackFlow: SharedFlow<Unit> = _clearScrollbackFlow

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

    // Fires whenever the active window's agent changes (none ⇄ claude ⇄ codex).
    // Hosts use this to turn line dedup on only for Claude-Code windows and to
    // swap the per-agent chrome (border colour, status bar, thinking bar).
    private val _agentWindowFlow = MutableStateFlow(AgentKind.NONE)
    val agentWindowFlow: StateFlow<AgentKind> = _agentWindowFlow

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
        // Claude Code session-feedback prompt — two lines, specific:
        //   "how is claude doing this session? (optional)"
        //   "1:bad   2:fine   3:good   0:dismiss"
        // Number→label mapping is fixed; only whitespace/case/intervening
        // ANSI artifacts vary, so allow any junk between the four pairs.
        private val FEEDBACK_PROMPT_PATTERN = Regex(
            "how.{0,20}claude.{0,20}doing.{0,20}session",
            RegexOption.IGNORE_CASE
        )
        private val FEEDBACK_OPTIONS_PATTERN = Regex(
            "1\\s*:\\s*bad\\b.*?2\\s*:\\s*fine\\b.*?3\\s*:\\s*good\\b.*?0\\s*:\\s*dismiss",
            RegexOption.IGNORE_CASE
        )
        // Backup: digit may be chopped by overdraw. If line has ≥2 of the
        // four labels in "<digit?>: <label>" form, treat as the options row.
        private val FEEDBACK_OPTION_PAIR = Regex(
            "\\d?\\s*:\\s*(bad|fine|good|dismiss)\\b",
            RegexOption.IGNORE_CASE
        )
        // Shell prompt patterns: ➜, $, %, # at line start (with optional leading whitespace)
        // NOT ❯ or > — those are Claude Code prompts, handled separately.
        // These get buffered so partial command echoes are collapsed into the final version.
        private val SHELL_PROMPT_PATTERN = Regex("^(➜|\\$|%|#)\\s.*")
        private const val MIN_DIFF_INTERVAL_MS = 400L
        // Adaptive throttle: if chunks arrive faster than this, increase diff interval
        private const val HIGH_THROUGHPUT_DIFF_INTERVAL_MS = 800L
        private const val HIGH_THROUGHPUT_THRESHOLD_MS = 20L // chunks <20ms apart = deluge
        private const val TAG = "OutputProcessor"
        // Component keywords confirmed alongside the sentinel char (ƕ, U+0195).
        // Both must match to count as a status line; ≥2 keyword hits required.
        private val STATUS_COMPONENT_KEYWORDS = arrayOf("GPU0", "GPU1", "CPU", "RAM", "ctx")
        // Sentinel character bracketing the user's status line (start and end).
        // ƕ (U+0195, LATIN SMALL LETTER HV) is rare enough to avoid log false
        // positives; ∆/Δ were too common in scientific output.
        private const val STATUS_SENTINEL = 'ƕ'
    }

    // Which agent (if any) is running in the active tmux window. When NONE,
    // all agent-specific filters (thinking detection, status area, fence
    // lines, etc.) are bypassed so plain shells render verbatim.
    // Detection: primary = tmux window name ("claude" / "codex");
    // fallback = content-based heuristic (agent UI elements on screen).
    // Codex runs under `node`, so its window is rarely named usefully and
    // content detection carries most of the load.
    @Volatile
    var agentKind: AgentKind = AgentKind.NONE
        internal set(value) {
            if (field == value) return
            field = value
            _agentWindowFlow.value = value
        }

    val isClaudeWindow: Boolean get() = agentKind == AgentKind.CLAUDE
    val isCodexWindow: Boolean get() = agentKind == AgentKind.CODEX
    val isAgentWindow: Boolean get() = agentKind.isAgent

    // Content-based Claude detection: count of recent screen frames that had
    // Claude Code UI elements (thinking symbols in left columns + fence/prompt pattern).
    // If we see Claude UI elements in 3+ of the last 5 frames, assume Claude is running
    // even if the tmux window isn't named "claude".
    private var claudeContentHits = 0
    private var claudeContentChecks = 0
    private var agentDetectedByName = AgentKind.NONE

    // Throttle: skip expensive diffs for rapid-fire small chunks (thinking animation)
    private var lastDiffTime = 0L
    private var lastChunkTime = 0L
    private var consecutiveFastChunks = 0
    private var lastStatusUpdateTime = 0L
    // Per-window status bar cache: saves each window's last status so it
    // restores when cycling back, but doesn't bleed between windows.
    /** Snapshot of a window's status bar AND its current mode flags so the
     *  full state can be restored when the user switches back. Mode flags
     *  outlive a single screen and stay sticky until we positively observe
     *  a different mode (auto/accept/plan are mutually exclusive in Claude
     *  Code; shift-tab cycles between them). */
    private data class WindowStatus(
        val baseStatus: String? = null,
        val auto: Boolean = false,
        val accept: Boolean = false,
        val plan: Boolean = false
    )
    private val statusByWindow = mutableMapOf<Int, WindowStatus>()
    private var currentWindowIndex = -1
    // Suffix flags are toggled only on explicit observation. The user must
    // see us flip a mode by seeing the new mode's hint line — we never
    // infer "off" from absence.
    @Volatile var acceptEditsVisible = false
    @Volatile var autoModeVisible = false
    @Volatile var planModeVisible = false
    private var lastBaseStatus: String? = null // status without the suffixes

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
                    detectThinkingForAgent()
                    if (now - lastDiffTime < effectiveDiffInterval) {
                        return
                    }
                }
            }
        }

        detectThinkingForAgent()

        // On full redraws, run content-based detection for windows whose name
        // doesn't identify an agent (claude inside a "bash" window; codex,
        // which almost always shows up as "node").
        if (chunkType == ChunkType.FULL_REDRAW && agentDetectedByName == AgentKind.NONE) {
            detectAgentByContent()
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
            // No dedup here — TerminalView handles "newest occurrence wins"
            // by removing any older copy of the same line from the view.
            // Filtering at this layer was stranding fresh text whenever an
            // identical line had been emitted earlier in the session.
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

            // Codex-specific filters: its bottom UI block repaints on every
            // frame, so the composer, footer and spinner row would otherwise
            // flood the transcript with near-duplicate lines.
            if (isCodexWindow) {
                // The spinner row → thinking indicator, never the transcript.
                val working = CodexUi.workingStatus(text)
                if (working != null) {
                    Log.v(TAG, "filter CODEX_WORKING: '$text' → '$working'")
                    handleThinking(working)
                    continue
                }

                // Empty composer ("› Ask Codex to do anything") and the
                // model·cwd footer are chrome. The footer doubles as Codex's
                // status line, so it feeds the status bar on its way out.
                if (CodexUi.COMPOSER_PLACEHOLDER.matches(text)) {
                    Log.v(TAG, "filter CODEX_COMPOSER: '$text'")
                    continue
                }
                if (CodexUi.FOOTER_LINE.matches(text)) {
                    Log.v(TAG, "filter CODEX_FOOTER: '$text'")
                    emitStatusBar(trimmed)
                    continue
                }

                // Bare "›" — the composer mid-repaint.
                if (trimmed.length <= 1 && trimmed.startsWith(CodexUi.USER_MARKER)) {
                    Log.v(TAG, "filter CODEX_PROMPT: '$text'")
                    continue
                }

                // Full-width rules Codex draws around each turn.
                if (isFenceLine(text) && text.trim().length > 20 && !isTableFence(text)) {
                    Log.v(TAG, "filter CODEX_RULE: '$text'")
                    continue
                }
            }

            // Claude Code-specific filters: only apply when active window is Claude
            if (isClaudeWindow) {
                // Drop lines that are only a thinking symbol (leaked animation frame)
                if (LONE_THINKING_SYMBOL.matches(text)) {
                    Log.v(TAG, "filter LONE_SYMBOL: '$text'")
                    continue
                }

                // Drop standalone fence lines — these are status area UI, not content.
                // Content fences (e.g. in code blocks) are shorter and mixed with text.
                // Table borders (with joint glyphs ├ ┼ ┤ ┴ etc.) are kept so
                // tables render fully — only pure ─/═/━ runs get filtered.
                if (isFenceLine(text) && text.trim().length > 20 && !isTableFence(text)) {
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

                // Mode hint lines from Claude Code (e.g. "⏵⏵ auto mode on
                // (shift+tab to cycle)"). Modes are mutually exclusive —
                // observing one explicitly turns the others off. We never
                // infer a mode change from absence: the suffix sticks
                // until the user actually switches modes.
                if (trimmed.contains("accept edits")) {
                    Log.v(TAG, "filter ACCEPT_EDITS: '$text'")
                    setMode(accept = true)
                    continue
                }
                if (trimmed.contains("auto mode on")) {
                    Log.v(TAG, "filter AUTO_MODE_ON: '$text'")
                    setMode(auto = true)
                    continue
                }
                if (trimmed.contains("plan mode on")) {
                    Log.v(TAG, "filter PLAN_MODE_ON: '$text'")
                    setMode(plan = true)
                    continue
                }
                if (trimmed.contains("auto mode") || trimmed.contains("plan mode")) {
                    // Cycle help / off variants — drop the line but don't
                    // touch flags (no positive evidence of a new mode).
                    Log.v(TAG, "filter MODE_HINT: '$text'")
                    continue
                }

                // Drop the session-feedback prompt (both the question line
                // and the options line, regardless of which digit maps to
                // which label or what spacing/case is used).
                if (FEEDBACK_PROMPT_PATTERN.containsMatchIn(trimmed) ||
                    FEEDBACK_OPTIONS_PATTERN.containsMatchIn(trimmed) ||
                    FEEDBACK_OPTION_PAIR.findAll(trimmed).count() >= 2) {
                    Log.v(TAG, "filter FEEDBACK_PROMPT: '$text'")
                    continue
                }
                // "thinking with <effort> effort" is a transient status
                // Claude Code spams when extended-thinking is active. Drop
                // any line containing "thinking with" + "effort".
                if (trimmed.contains("thinking with", ignoreCase = true) &&
                    trimmed.contains("effort", ignoreCase = true)) {
                    Log.v(TAG, "filter THINKING_EFFORT: '$text'")
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
        return text.replace("❯", " ").replace(">", " ")
            .replace(CodexUi.USER_MARKER, ' ').trimStart()
    }

    /** True if a row is the active agent's transient spinner line. */
    private fun isAgentSpinnerText(text: String): Boolean =
        isCodexWindow && CodexUi.WORKING_LINE.containsMatchIn(text)

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

    /** A line looks like Claude Code status info if any of:
     *   - has ƕ sentinel + ≥2 component keywords (clean parse)
     *   - contains the "%~" end terminator (the ƕ got chopped by a
     *     partial overwrite, but the terminator survived)
     *   - has ≥3 component keywords (strong content signal alone)
     *  False-positive risk is low since these signals don't appear in
     *  normal Claude Code output. */
    private fun isStatusInfoLine(text: String): Boolean {
        var hits = 0
        for (kw in STATUS_COMPONENT_KEYWORDS) {
            if (text.contains(kw)) hits++
        }
        if (hits >= 3) return true
        if (text.contains("%~")) return true
        if (text.contains(STATUS_SENTINEL) && hits >= 2) return true
        return false
    }

    /**
     * Parse the user's status bar from combined row text. The status is
     * bracketed by ƕ (start) and "%~" (end). Three cases:
     *
     *  - Full bar (ƕ ... %~ found): return the cleaned content between
     *    them. Note the trailing % is part of the data (e.g. "ctx: 45%")
     *    and ~ is the terminator — keep the %, drop only the ~.
     *  - Partial bar (ƕ found, %~ missing): the bar got truncated or
     *    over-written mid-render. Don't ignore it (we'd lose the latest
     *    info) and don't blindly accept the whole tail (it likely contains
     *    chimera fragments from old screen content). Instead, walk through
     *    the well-formed `WORD: value` components and stop at the first
     *    chunk that looks broken. Append "…" so the user can tell the
     *    parse was incomplete.
     *  - No ƕ at all: return null.
     */
    private fun parseStatusBar(combined: String): String? {
        val startIdx = combined.indexOf(STATUS_SENTINEL)
        if (startIdx < 0) return null
        val afterStart = combined.substring(startIdx + 1)
        val endIdx = afterStart.indexOf("%~")
        // endIdx points at '%' — keep through it (substring(0, endIdx + 1)),
        // dropping only the trailing ~ terminator. The % belongs to the
        // last value (typically "ctx: NN%").
        val raw = if (endIdx >= 0) afterStart.substring(0, endIdx + 1) else afterStart
        val cleaned = raw.replace(STATUS_SENTINEL.toString(), "").trim()
        if (cleaned.isEmpty()) return null
        if (endIdx >= 0) return cleaned

        // Partial parse: keep only the consecutive well-formed components.
        // Components are pipe-separated; the first may be a path-like
        // prefix (e.g. "~/projects/foo"), the rest should look like
        // "LABEL: value" or "LABEL=value".
        val pathLike = Regex("^[A-Za-z~/.][\\w./~\\-]*$")
        val labeled = Regex("^[A-Za-z][\\w/]*\\s*[:=]\\s*\\S.*$")
        val chunks = cleaned.split("|").map { it.trim() }
        val kept = mutableListOf<String>()
        for ((i, chunk) in chunks.withIndex()) {
            if (chunk.isEmpty()) continue
            val ok = when {
                i == 0 -> pathLike.matches(chunk) || labeled.matches(chunk)
                else -> labeled.matches(chunk)
            }
            if (ok) kept.add(chunk) else break
        }
        if (kept.isEmpty()) return null
        return kept.joinToString(" | ") + "…"
    }

    /** A line is a "fence" if ≥60% of its non-whitespace chars are fence characters. */
    private fun isFenceLine(text: String): Boolean {
        val nonSpace = text.count { it != ' ' }
        if (nonSpace == 0) return false
        val fenceCount = text.count { it in FENCE_CHARS }
        return fenceCount.toFloat() / nonSpace >= 0.6f
    }

    /** Heuristic: this fence line is actually the top/middle/bottom border of
     *  an ASCII/Unicode table, not a Claude-Code status-area divider. Tables
     *  have joint/corner glyphs (├ ┤ ┬ ┴ ┼ ┌ ┐ └ ┘ ╔ ╗ ╚ ╝ ╠ ╣ ╦ ╩ ╬ +)
     *  which never appear in status fences (those are pure runs of ─/═/━).
     *  Keep table borders so the user sees the full table. */
    private fun isTableFence(text: String): Boolean {
        for (ch in text) {
            when (ch) {
                '├', '┤', '┬', '┴', '┼',
                '┌', '┐', '└', '┘',
                '╔', '╗', '╚', '╝', '╠', '╣', '╦', '╩', '╬',
                '+' -> return true
            }
        }
        return false
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
        return (isFenceLine(text) && text.length > 20 && !isTableFence(text)) ||
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

        for (r in (maxRow - 1) downTo maxOf(0, maxRow - 10)) {
            val text = screen.getRowText(r)
            // Positive sightings of mode hints update the flags here too,
            // since postProcessLines may not see them on every redraw.
            // Absence is intentionally NOT used to clear flags — modes
            // stay sticky until we positively observe a different mode.
            if (text.contains("accept edits")) setMode(accept = true)
            if (text.contains("auto mode on")) setMode(auto = true)
            if (text.contains("plan mode on")) setMode(plan = true)
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

        // No absence-based clearing here on purpose — see setMode().

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
                val combined = statusLines.joinToString(" ") { it.trim() }
                    .replace(Regex("\\s{2,}"), " ")
                val parsed = parseStatusBar(combined)
                if (parsed != null && parsed.isNotEmpty()) {
                    lastBaseStatus = parsed
                    emitStatusBar(parsed)
                }
            }
        }


        return topStatusRow
    }

    /** Emit status bar with optional suffixes: " a" (accept edits),
     *  " auto" (auto mode), " plan" (plan mode). */
    private fun emitStatusBar(base: String) {
        lastBaseStatus = base
        val sb = StringBuilder(base)
        // Mode suffixes are Claude-Code modes (shift+tab cycles them).
        if (isClaudeWindow) {
            if (acceptEditsVisible) sb.append(" a")
            if (autoModeVisible) sb.append(" auto")
            if (planModeVisible) sb.append(" plan")
        }
        val display = sb.toString()
        _statusBarFlow.value = display
        saveCurrentWindowStatus()
    }

    /** Re-emit status bar when a mode flag changes. */
    private fun refreshStatusBarSuffix() {
        val base = lastBaseStatus ?: run {
            // No base yet, but mode change still needs to be persisted
            // so it survives a window switch + restore.
            saveCurrentWindowStatus()
            return
        }
        emitStatusBar(base)
    }

    /** Set mode flags positively (mutually exclusive). Any flag set true
     *  forces the others false. Pass nothing to leave flags unchanged.
     *  Re-emits the status bar if anything actually changed. */
    private fun setMode(auto: Boolean = false, accept: Boolean = false, plan: Boolean = false) {
        if (!auto && !accept && !plan) return
        val newAuto = auto
        val newAccept = accept
        val newPlan = plan
        if (newAuto == autoModeVisible && newAccept == acceptEditsVisible && newPlan == planModeVisible) return
        autoModeVisible = newAuto
        acceptEditsVisible = newAccept
        planModeVisible = newPlan
        refreshStatusBarSuffix()
    }

    /** Persist the active window's status + mode flags so a switch back
     *  restores the same suffix even if no new mode hint is observed. */
    private fun saveCurrentWindowStatus() {
        if (currentWindowIndex < 0) return
        statusByWindow[currentWindowIndex] = WindowStatus(
            baseStatus = lastBaseStatus,
            auto = autoModeVisible,
            accept = acceptEditsVisible,
            plan = planModeVisible
        )
    }

    /** Restore status + mode flags for the named window (called on switch). */
    private fun restoreWindowStatus(windowIdx: Int) {
        currentWindowIndex = windowIdx
        val saved = statusByWindow[windowIdx] ?: WindowStatus()
        autoModeVisible = saved.auto
        acceptEditsVisible = saved.accept
        planModeVisible = saved.plan
        lastBaseStatus = saved.baseStatus
        if (saved.baseStatus != null) {
            emitStatusBar(saved.baseStatus)
        } else {
            _statusBarFlow.value = null
        }
    }

    /**
     * Codex's fixed bottom block: composer row, model·cwd footer, and the
     * spinner row, plus any blank rows woven between them. Everything above
     * it is transcript that scrolled through the DECSTBM region and should
     * reach the user verbatim.
     *
     * Returns the first row of the block, or [maxRow] when none is found.
     * Unlike Claude's status area this also emits the status bar — the footer
     * is cheap to parse and can't produce chimera rows, so there's no reason
     * to throttle it to full redraws.
     */
    private fun detectCodexStatusArea(maxRow: Int): Int {
        val top = detectCodexBottomBlock(maxRow) { screen.getRowText(it) }
        // The footer ("<model> · <cwd>") is Codex's status line. It lives
        // inside the block we just trimmed away, so read it here — nothing
        // downstream will ever see it.
        for (r in top until maxRow) {
            val text = screen.getRowText(r).trim()
            if (CodexUi.FOOTER_LINE.matches(text)) {
                if (text != lastBaseStatus) emitStatusBar(text)
                break
            }
        }
        return top
    }

    private fun detectCodexStatusAreaInSnapshot(
        snapshot: Array<Array<VirtualScreen.Cell>>,
        maxRow: Int
    ): Int = detectCodexBottomBlock(maxRow) { getRowText(snapshot[it]) }

    private inline fun detectCodexBottomBlock(maxRow: Int, rowText: (Int) -> String): Int {
        var topRow = -1
        for (r in (maxRow - 1) downTo maxOf(0, maxRow - 8)) {
            val text = rowText(r)
            when {
                CodexUi.isBottomBlockLine(text) -> topRow = r
                // Blanks below the block (Codex leaves a couple) don't end the
                // scan; blanks inside it belong to the block.
                text.isBlank() -> if (topRow >= 0) topRow = r
                // First real transcript row — stop.
                else -> return if (topRow >= 0) topRow else maxRow
            }
        }
        return if (topRow >= 0) topRow else maxRow
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
        val contentRows = if (isCodexWindow) {
            detectCodexStatusArea(maxRow)
        } else if (isClaudeWindow) {
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

        val prevContentRows = when {
            isCodexWindow -> detectCodexStatusAreaInSnapshot(prev, maxRow)
            isClaudeWindow -> detectStatusAreaInSnapshot(prev, maxRow)
            else -> maxRow
        }

        // Build line lists; blank the agent's spinner row so its per-second
        // repaint doesn't read as new content. Plain shells keep every row.
        val prevLines = (0 until prevContentRows).map {
            val text = getRowText(prev[it])
            if (isAgentSpinnerText(text) || (isClaudeWindow && isThinkingRowInSnapshot(prev[it]))) "" else text
        }
        val currLines = (0 until contentRows).map {
            val text = screen.getRowText(it)
            if (isAgentSpinnerText(text) || (isClaudeWindow && isThinkingRow(it))) "" else text
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

            // Menu-nav collapse, bullet-blink suppression and chimera skipping
            // are Claude-Code UI heuristics. Non-Claude windows render every
            // changed row verbatim — no content-dropping.
            if (isAgentWindow && changedRows.size > 0 && selectorChangedRows.size == changedRows.size) {
                // Pure menu navigation — emit only the currently selected line.
                // Both agents render menus this way; only the marker differs
                // (Claude ❯, Codex ›).
                for (r in selectorChangedRows) {
                    val text = currLines[r].trimStart()
                    if (text.startsWith("❯") || text.startsWith(">") ||
                        text.startsWith(CodexUi.USER_MARKER)) {
                        newLines.add(screen.getRowStyled(r))
                        break
                    }
                }
                return newLines
            }

            if (isClaudeWindow && changedRows.size > 0 && bulletChangedRows.size == changedRows.size) {
                // Pure bullet blink — suppress entirely
                return newLines
            }

            // Not menu/bullet animation — emit genuinely new lines,
            // but skip rows that are partial cursor overwrites (chimeras).
            // A partial overwrite is when the new row shares a long common
            // prefix or suffix with the old row but has a spliced middle.
            for (r in changedRows) {
                if (isClaudeWindow && r < prevLines.size && isPartialOverwrite(prevLines[r], currLines[r])) {
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
                if (isClaudeWindow && r < prevLines.size && isPartialOverwrite(prevLines[r], currLines[r], threshold = 0.4f)) {
                    Log.v(TAG, "skip partial overwrite (scroll) row $r: '${currLines[r]}'")
                    continue
                }
                newLines.add(screen.getRowStyled(r))
            }
        }

        // Safety net: if any sentinel-bearing line is about to leak through
        // to the dedup pipeline (where it will be dropped from scrollback),
        // make sure we've captured the status bar UI from this screen first.
        // The normal path is detectStatusArea above, but it's gated by
        // FULL_REDRAW + 2s throttle to avoid chimera rows; on the off chance
        // a clean status line slips past that gate AND past the status-area
        // structural detection, we don't want LineDedup's drop to swallow
        // the only copy of the data without ever updating the user's bar.
        if (isClaudeWindow &&
            newLines.any { sb -> sb.toString().contains(STATUS_SENTINEL) }) {
            detectStatusArea(maxRow, updateStatusBar = true)
        }

        return newLines
    }

    private fun extractNonBlankLines(): List<SpannableStringBuilder> {
        val lines = mutableListOf<SpannableStringBuilder>()
        for (r in 0 until rows - 1) { // Skip last row (potential tmux bar)
            if (isClaudeWindow && isThinkingRow(r)) continue // Skip thinking animation row
            val text = screen.getRowText(r)
            if (isAgentSpinnerText(text)) continue
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

    /** Route thinking detection to the active agent's screen heuristic. */
    private fun detectThinkingForAgent() {
        when (agentKind) {
            AgentKind.CLAUDE -> detectThinkingOnScreen()
            AgentKind.CODEX -> detectCodexThinkingOnScreen()
            AgentKind.NONE -> {}
        }
    }

    /**
     * Codex prints its spinner as a text row — "• Working (12s • esc to
     * interrupt)" — rather than animating a glyph column. Find it anywhere
     * on screen and surface the verb phrase ("Working", "Reviewing approval
     * request") in the thinking indicator.
     */
    private fun detectCodexThinkingOnScreen() {
        for (r in (rows - 2) downTo 0) {
            val status = CodexUi.workingStatus(screen.getRowText(r)) ?: continue
            handleThinking(status)
            return
        }
        // No spinner row this frame — let the 2 s timeout decide, same as
        // Claude. A single frame can land mid-repaint.
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
        // Window switch — clear the thinking indicator immediately so a
        // stale animation can't bleed across into the new window.
        resetThinkingState()
        // Save current window's full status (base + mode flags) first
        saveCurrentWindowStatus()

        cachedWindows = cached.mapIndexed { i, w -> w.copy(isActive = i == nextIdx) }
        userSwitchUntil = System.currentTimeMillis() + 2000L

        // Restore next window's status + mode flags
        val nextWinIdx = cached.getOrNull(nextIdx)?.index ?: -1
        restoreWindowStatus(nextWinIdx)
        val updated = cachedWindows!!
        val activeIndex = updated.indexOfFirst { it.isActive }
        val activeWindow = updated.getOrNull(activeIndex)
        if (activeWindow != null) {
            agentDetectedByName = AgentKind.fromWindowName(activeWindow.name)
            agentKind = agentDetectedByName
        }
        _tmuxBarFlow.value = TmuxBarUpdate(updated, activeIndex)
    }

    /**
     * Replace the cached tmux window list with an authoritative snapshot
     * from `tmux list-windows`. Used by the W? resync button when the
     * status-bar parser has drifted out of sync with reality. Drops cached
     * status bars for windows that no longer exist, marks the parsed
     * active window as Claude or not, and emits the new tab list.
     */
    fun forceSetWindows(windows: List<TmuxWindow>) {
        if (windows.isEmpty()) return
        // Authoritative resync — drop any in-flight thinking animation so
        // a stale frame doesn't survive into whichever window we land on.
        resetThinkingState()
        // Persist current state before any switch happens
        saveCurrentWindowStatus()
        val keepIndices = windows.map { it.index }.toSet()
        val droppedIndices = statusByWindow.keys - keepIndices
        droppedIndices.forEach { statusByWindow.remove(it) }
        cachedWindows = windows
        userSwitchUntil = 0L
        val activeIndex = windows.indexOfFirst { it.isActive }.takeIf { it >= 0 } ?: 0
        val activeWindow = windows[activeIndex]
        agentDetectedByName = AgentKind.fromWindowName(activeWindow.name)
        agentKind = agentDetectedByName
        claudeContentHits = 0
        claudeContentChecks = 0
        restoreWindowStatus(activeWindow.index)
        _tmuxBarFlow.value = TmuxBarUpdate(windows, activeIndex)
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
                    statusByWindow.remove(idx)
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
            // If the bar parse changed the active window, persist the
            // outgoing window's status + restore the new window's saved
            // status (mode flags survive the switch).
            if (activeWindow.index != currentWindowIndex) {
                saveCurrentWindowStatus()
                restoreWindowStatus(activeWindow.index)
            }
            agentDetectedByName = AgentKind.fromWindowName(activeWindow.name)
            if (agentDetectedByName != AgentKind.NONE) {
                agentKind = agentDetectedByName
                claudeContentHits = 0
                claudeContentChecks = 0
            } else if (!isCodexWindow) {
                // Name says nothing and we're not already in a detected codex
                // window — drop back to NONE. Claude's content heuristics are
                // too prone to false positives (thinking symbols appear in
                // other programs, ❯ is a common prompt) to keep a guess alive
                // across a bar parse. Codex is exempt: its window is named
                // "node", so a NONE verdict here would fight the (unambiguous)
                // content detection on every single bar update.
                agentKind = AgentKind.NONE
                claudeContentHits = 0
                claudeContentChecks = 0
            }
        }
        Log.d(TAG, "tmuxBar parsed: windows=${finalWindows.map { "${it.index}:${it.name}${if (it.isActive) "*" else ""}" }} agentKind=$agentKind (byName=$agentDetectedByName)")
        _tmuxBarFlow.value = TmuxBarUpdate(finalWindows, activeIndex)
    }

    private fun stripAnsiCodes(raw: String): String {
        return raw.replace(Regex("\u001b\\[[0-9;]*[a-zA-Z]"), "")
            .replace(Regex("\u001b\\][^\u0007]*\u0007"), "")
            .replace(Regex("\u001b[()][A-Z0-9]"), "")
    }

    /**
     * Content-based agent detection. Called on FULL_REDRAW when the window
     * name doesn't identify an agent (claude launched inside a "bash" window,
     * or codex, which tmux almost always names "node").
     *
     * Codex is checked first and needs only one sighting: its markers ("Ask
     * Codex to do anything", the ">_ OpenAI Codex" banner, the "esc to
     * interrupt" spinner row) are strings no other program prints. Claude's
     * markers are generic glyphs, so they need the rolling-window vote below.
     */
    private fun detectAgentByContent() {
        if (agentDetectedByName != AgentKind.NONE) return // Already known by name

        val lines = (0 until rows - 1).map { screen.getRowText(it) }
        if (CodexUi.looksLikeCodex(lines)) {
            if (agentKind != AgentKind.CODEX) {
                Log.d(TAG, "Codex content detection: $agentKind → CODEX")
                agentKind = AgentKind.CODEX
                claudeContentHits = 0
                claudeContentChecks = 0
            }
            return
        }
        if (isCodexWindow) {
            // Codex markers are persistent chrome: the composer placeholder or
            // footer is on screen in every idle frame, and the spinner row in
            // every busy one. Losing all of them means codex exited.
            Log.d(TAG, "Codex content detection: CODEX → NONE")
            agentKind = AgentKind.NONE
        }

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
                agentKind = if (detected) AgentKind.CLAUDE else AgentKind.NONE
            }
        }
    }

    /** Reset diff state (e.g. after tmux window switch). Also clears the
     *  thinking state machine — without this, lastStatusText from the
     *  previous window's session leaks into the new one. The new window's
     *  initial redraw can incidentally include a thinking glyph (`·` and
     *  `*` are common), and handleThinking would re-fire the animation
     *  with the stale status text until the 2 s timeout cleared it. */
    fun resetDiffState() {
        previousSnapshot = null
        claudeContentHits = 0
        claudeContentChecks = 0
        resetThinkingState()
    }

    /** Hard reset of the screen state itself. Use this when transitioning
     *  contexts in a way that the next chunk WON'T be a tmux full redraw —
     *  notably, "tmux a" — because diffScreens with no previous snapshot
     *  will otherwise emit every non-blank row of the current screen as
     *  "new", leaking pre-transition content into the new context. */
    fun resetScreenState() {
        previousSnapshot = null
        claudeContentHits = 0
        claudeContentChecks = 0
        screen.eraseEntireScreen()
        screen.resetScrollRegion()
        interpreter.reset()
        resetThinkingState()
    }

    /** Drop any in-flight thinking animation + timeout + cached status
     *  text. Called on every context transition so the indicator never
     *  carries forward state from a window the user is no longer on. */
    private fun resetThinkingState() {
        thinkingTimeoutJob?.cancel()
        thinkingTimeoutJob = null
        thinkingState = ThinkingState.IDLE
        lastStatusText = null
        thinkingRow = -1
        _thinkingFlow.value = ThinkingUpdate(false, null, null)
    }

    /** Full reset on reconnect — clear all cached state. */
    fun resetAllState() {
        resetDiffState()
        cachedWindows = null
        currentWindowIndex = -1
        userSwitchUntil = 0L
        statusByWindow.clear()
        agentKind = AgentKind.NONE
        agentDetectedByName = AgentKind.NONE
        acceptEditsVisible = false
        autoModeVisible = false
        planModeVisible = false
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
