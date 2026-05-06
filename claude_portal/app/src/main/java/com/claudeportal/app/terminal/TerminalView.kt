package com.claudeportal.app.terminal

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Scrollable terminal history view with batched display.
 *
 * Incoming lines go into a pending queue. A display timer pulls from the
 * queue and appends a batch. If the queue has more than a screen's worth
 * of lines it skips to the latest screen-sized piece.
 *
 * When the user scrolls up ("history mode") the view freezes in place —
 * new content is appended in memory but the scroll position stays locked.
 * Older content is paged in from disk with a per-chunk dedup against
 * what's currently on screen (newest wins everywhere). A chunk that is
 * entirely duplicates is dropped silently and the next older chunk is
 * fetched immediately, so the user doesn't have to scroll past empty
 * space to reach genuinely new history.
 *
 * Line-height stability: Claude Code "blinks" bullet symbols on/off as a
 * thinking-state animation; the bullet glyph (●) is rendered through a
 * font fallback whose metrics differ from monospace, so without
 * intervention the line's vertical extent oscillates as the bullet
 * appears and disappears. Every non-table line carries a
 * FixedLineHeightSpan that pins its height to a constant px so glyph
 * changes can't perturb the layout. Tables retain their natural
 * RelativeSizeSpan compression because the user's "graph shrink" knob is
 * meant to apply there.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    private val textView: TextView = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        setTextColor(0xFFD3D7CF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(16, 8, 16, 8)
        // Text selection disabled — it forces an Editable backing store which
        // makes append/trim O(n) with span fixup, causing ANR on large text.
        setTextIsSelectable(false)
        // Disable content capture to prevent OOM — Android's ContentCapture copies
        // the entire SpannableStringBuilder on every text change
        importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO
        // Strip extra ascender/descender padding so FixedLineHeightSpan can
        // fully control per-line height without the framework adding extras.
        includeFontPadding = false
    }

    private var autoScrollEnabled = true
    private var userTouching = false
    private var suppressScrollDetection = false
    private var selectionMode = false

    /** Locked per-line height in pixels. Recomputed when font size changes. */
    private var stableLineHeightPx: Int = 0

    /** Horizontal compression factor for detected table rows. Adjustable via
     *  the "graph shrink %" setting. */
    private var tableShrinkRatio: Float = 0.38f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            enterSelectionMode(e.x, e.y)
        }
    })

    /** Called when history mode changes: true = viewing history, false = live. */
    var onHistoryModeChanged: ((Boolean) -> Unit)? = null

    /**
     * Asks the host for the next older chunk to prepend in history mode.
     * Receives the byte size of the text currently in the view (which is the
     * tail of the disk file); the host should read the chunk preceding that
     * tail. Returns plain text, or null when there is nothing more.
     * Called on the main thread; the host may run the read off-thread and
     * post back via View.post.
     */
    var onLoadOlder: ((skipFromEndBytes: Long, callback: (String?) -> Unit) -> Unit)? = null

    /** Called on the main thread when a disk-paging load starts and ends.
     *  The "end" callback fires even if no older content was found, so the
     *  host can show a brief spinner that confirms the attempt was made. */
    var onLoadOlderStateChanged: ((loading: Boolean) -> Unit)? = null

    // Disk-paged scrollback tracking. We track the cumulative byte offset
    // we've pulled from the underlying file independently of the textView
    // size, because dedup may shrink what we actually prepend.
    private var loadingOlder = false
    private var historyDiskBytesLoaded = 0L
    private var historyDiskOffsetFromEnd = 0L
    private var noMoreOlderHistory = false

    // Batched display queue
    private val pendingLines = ConcurrentLinkedQueue<SpannableStringBuilder>()
    private val handler = Handler(Looper.getMainLooper())
    private var batchTimerRunning = false

    // History mode buffer: lines received while user is scrolling back.
    // Kept in memory (not appended to TextView) to avoid expensive relayouts.
    // Flushed when history mode exits.
    private val historyModeBuffer = mutableListOf<SpannableStringBuilder>()

    // Live dedup of recently-displayed lines. Keyed by LineDedup.keyFor — that
    // means table rows dedup by exact text (so "step 1" and "step 2" rows are
    // both kept) while non-table rows dedup by skeleton (digits → '#'). On a
    // hit we edit the existing line in place (newest-wins replacement) so the
    // line stays anchored in the scroll; on a miss we append at the bottom.
    // Disabled in dirty-history mode so the raw fallback shows everything.
    private val recentTextsByKey = LinkedHashMap<String, String>()
    private var dedupEnabled = true

    companion object {
        // Keep the TextView small to avoid expensive relayout on append/trim.
        // Older content lives on disk via HistoryBuffer.
        private const val MAX_CHARS = 6_000
        private const val TRIM_TO = 4_000
        private const val BATCH_INTERVAL_MS = 500L
        private const val VISIBLE_ROWS_ESTIMATE = 30
        // Max pending lines before we start dropping oldest (backpressure)
        private const val MAX_PENDING_LINES = 300
        // Max recent line texts to remember for runtime dedup
        private const val MAX_RECENT_LINES = 500
        // Cap on total bytes paged from disk so the TextView doesn't OOM.
        private const val MAX_HISTORY_DISK_BYTES = 1_500_000L

        /** Multiplier applied to the font's px size to derive the locked
         *  per-line height. 1.25 leaves a hair of breathing room without
         *  introducing inter-line gaps that would themselves wiggle. */
        private const val LINE_HEIGHT_MULTIPLIER = 1.25f

        /** Re-exported for other modules that already imported this name. */
        fun isTableRow(text: String): Boolean = LineDedup.isTableRow(text)
        fun skeleton(text: String): String = LineDedup.skeleton(text)
    }

    init {
        setBackgroundColor(0xFF1E1E1E.toInt())
        addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        isFillViewport = true
        recomputeLineHeight()

        setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            // History-mode entry detection requires an active touch and not
            // being in a suppressed window; paging-from-disk does not, so it
            // must run before the early return.
            val maxScroll = textView.height - height
            val atBottom = scrollY >= maxScroll - 50

            // Only page in older content from disk once the user has scrolled
            // all the way to the top of what's currently loaded.
            if (!autoScrollEnabled && scrollY <= 0 && oldScrollY > 0) {
                tryLoadOlder()
            }

            if (!userTouching || suppressScrollDetection) return@setOnScrollChangeListener

            if (scrollY < oldScrollY && !atBottom) {
                // User scrolled up — enter history mode
                if (autoScrollEnabled) {
                    autoScrollEnabled = false
                    userTouching = false
                    suppressScrollDetection = true
                    onHistoryModeChanged?.invoke(true)
                    postDelayed({ suppressScrollDetection = false }, 1000)
                }
            }
        }
    }

    /**
     * Page in the next older history chunk from disk. Triggered when the
     * user has scrolled to the top of in-memory content. The loaded chunk
     * is deduped against what's already on screen before being prepended;
     * if the chunk is entirely duplicates (or pure-decoration table rows),
     * we silently advance the disk offset and try the next older chunk
     * immediately so the user doesn't have to scroll past nothing.
     */
    private fun tryLoadOlder() {
        if (loadingOlder || noMoreOlderHistory) return
        if (historyDiskBytesLoaded >= MAX_HISTORY_DISK_BYTES) return
        val cb = onLoadOlder ?: return
        loadingOlder = true
        onLoadOlderStateChanged?.invoke(true)
        val loadStartedAt = System.currentTimeMillis()
        // First call: anchor offset to whatever's already in the view (which
        // came from the live stream / setContent and corresponds to the file
        // tail). Subsequent calls advance by the raw chunk size we pulled.
        if (historyDiskOffsetFromEnd == 0L && historyDiskBytesLoaded == 0L) {
            historyDiskOffsetFromEnd = currentTextByteSize()
        }

        fun tryNext() {
            if (historyDiskBytesLoaded >= MAX_HISTORY_DISK_BYTES) {
                finishLoadOlder(loadStartedAt); return
            }
            cb(historyDiskOffsetFromEnd) { chunk ->
                post {
                    if (chunk.isNullOrEmpty()) {
                        noMoreOlderHistory = true
                        finishLoadOlder(loadStartedAt)
                        return@post
                    }
                    val chunkBytes = chunk.toByteArray(Charsets.UTF_8).size.toLong()
                    historyDiskOffsetFromEnd += chunkBytes
                    historyDiskBytesLoaded += chunkBytes
                    val knownKeys = LineDedup.keysIn(textView.text.toString())
                    val deduped = LineDedup.dedupChunkAgainstKnown(chunk, knownKeys)
                    if (deduped.isEmpty()) {
                        // All duplicates / decoration — silently roll forward
                        // to the next older chunk without disturbing the view.
                        tryNext()
                        return@post
                    }
                    prependDedupedChunk(deduped)
                    finishLoadOlder(loadStartedAt)
                }
            }
        }
        tryNext()
    }

    private fun prependDedupedChunk(chunk: String) {
        val priorTextHeight = textView.height
        val priorScroll = scrollY
        val builder = SpannableStringBuilder(chunk)
        if (textView.length() > 0 && !chunk.endsWith('\n')) {
            builder.append('\n')
        }
        builder.append(textView.text)
        applyLineSpansTo(builder)
        textView.text = builder
        // Restore visual position: the user was looking at the same content,
        // but it has shifted down by the prepended height.
        post {
            val delta = textView.height - priorTextHeight
            if (delta > 0) scrollTo(0, priorScroll + delta)
        }
    }

    private fun finishLoadOlder(startedAt: Long) {
        loadingOlder = false
        val elapsed = System.currentTimeMillis() - startedAt
        val minVisibleMs = 500L
        val remaining = (minVisibleMs - elapsed).coerceAtLeast(0L)
        postDelayed({ onLoadOlderStateChanged?.invoke(false) }, remaining)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        handleTouchTracking(ev)
        if (!selectionMode) gestureDetector.onTouchEvent(ev)
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        handleTouchTracking(ev)
        if (!selectionMode) gestureDetector.onTouchEvent(ev)
        return super.onTouchEvent(ev)
    }

    private fun handleTouchTracking(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                userTouching = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (userTouching) {
                    postDelayed({ userTouching = false }, 500)
                }
            }
        }
    }

    private fun enterSelectionMode(scrollX: Float, scrollY: Float) {
        if (selectionMode) return
        selectionMode = true

        if (autoScrollEnabled) {
            autoScrollEnabled = false
            userTouching = false
            suppressScrollDetection = true
            onHistoryModeChanged?.invoke(true)
            postDelayed({ suppressScrollDetection = false }, 1000)
        }

        textView.setTextIsSelectable(true)

        val tvX = scrollX - textView.left
        val tvY = scrollY - textView.top + this.scrollY

        post {
            val offset = textView.getOffsetForPosition(tvX, tvY)
            val text = textView.text
            if (offset in 0..text.length && text is Spannable) {
                var start = offset
                var end = offset
                while (start > 0 && !text[start - 1].isWhitespace()) start--
                while (end < text.length && !text[end].isWhitespace()) end++
                if (start == end && start < text.length) end = start + 1
                try {
                    Selection.setSelection(text, start, end)
                    textView.requestFocus()
                    textView.performLongClick()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun exitSelectionMode() {
        if (!selectionMode) return
        selectionMode = false
        textView.setTextIsSelectable(false)
        textView.importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO
    }

    fun setFontSize(sp: Float) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        recomputeLineHeight()
    }

    /** Set the table-row shrink ratio. Range [0.1, 1] — 100% disables
     *  compression; smaller values shrink tables to fit wide content on a
     *  phone screen. Re-styles tables already on screen so the change is
     *  visible without waiting for a fresh emit. */
    fun setTableShrinkRatio(ratio: Float) {
        val clamped = ratio.coerceIn(0.1f, 1f)
        if (kotlin.math.abs(clamped - tableShrinkRatio) < 0.005f) return
        tableShrinkRatio = clamped
        restyleCurrentText()
    }

    private fun recomputeLineHeight() {
        val px = (textView.textSize * LINE_HEIGHT_MULTIPLIER).toInt().coerceAtLeast(1)
        if (px == stableLineHeightPx) return
        stableLineHeightPx = px
        restyleCurrentText()
    }

    /** Strip and re-apply line-height / shrink spans on the existing
     *  TextView content. applyLineSpansTo cleans both span types before
     *  adding fresh ones, so this is safe to call repeatedly without
     *  stacking RelativeSizeSpans (each compounds multiplicatively, which
     *  caused tables to shrink to nothing when settings changed). */
    private fun restyleCurrentText() {
        val cur = textView.text as? android.text.Spannable ?: return
        applyLineSpansTo(cur)
    }

    /**
     * Append deduplicated lines from the OutputProcessor.
     */
    fun appendLines(lines: List<SpannableStringBuilder>) {
        for (line in lines) {
            pendingLines.add(line)
        }
        while (pendingLines.size > MAX_PENDING_LINES) {
            pendingLines.poll()
        }
        startBatchTimer()
    }

    private fun startBatchTimer() {
        if (batchTimerRunning) return
        batchTimerRunning = true
        handler.postDelayed(batchRunnable, BATCH_INTERVAL_MS)
    }

    private val batchRunnable = object : Runnable {
        override fun run() {
            flushPendingLines()
            if (autoScrollEnabled) {
                ensureScrolledToBottom()
            }
            if (pendingLines.isNotEmpty() || autoScrollEnabled) {
                handler.postDelayed(this, BATCH_INTERVAL_MS)
            } else {
                batchTimerRunning = false
            }
        }
    }

    private fun flushPendingLines() {
        if (pendingLines.isEmpty()) return

        val batch = mutableListOf<SpannableStringBuilder>()
        while (true) {
            val line = pendingLines.poll() ?: break
            batch.add(line)
        }

        if (batch.isEmpty()) return

        if (!autoScrollEnabled) {
            historyModeBuffer.addAll(batch)
            while (historyModeBuffer.size > MAX_PENDING_LINES) {
                historyModeBuffer.removeAt(0)
            }
            return
        }

        appendBatchToView(batch)
    }

    /** Append a batch of lines to the TextView, applying dedup with newest-
     *  wins replacement and per-line styling. Pure-decoration table rows
     *  are dropped before they reach the view. */
    private fun appendBatchToView(batch: List<SpannableStringBuilder>) {
        val displayBatch: List<SpannableStringBuilder>
        val skipAnimation: Boolean
        if (batch.size > VISIBLE_ROWS_ESTIMATE * 3) {
            displayBatch = batch.subList(batch.size - VISIBLE_ROWS_ESTIMATE * 2, batch.size)
            skipAnimation = true
        } else {
            displayBatch = batch
            skipAnimation = batch.size > VISIBLE_ROWS_ESTIMATE
        }

        for (line in displayBatch) {
            val text = line.toString()

            if (text.isBlank()) {
                if (textView.length() > 0) textView.append("\n")
                applyLineStyling(line, text)
                textView.append(line)
                continue
            }

            // Pure decoration table rows (borders/corners only) carry no
            // information — drop entirely so dedup doesn't pile them up.
            if (LineDedup.isPureTableDecoration(text)) {
                android.util.Log.v("TerminalView", "drop PURE_DECORATION: '${text.take(60)}'")
                continue
            }

            applyLineStyling(line, text)

            if (dedupEnabled) {
                val key = LineDedup.keyFor(text)
                if (key == null && !LineDedup.isTableRow(text)) {
                    // Non-table line with no skeleton (single bullet / lone
                    // glyph) — drop as junk.
                    android.util.Log.v("TerminalView", "dedup DROP no-key: '${text.take(60)}'")
                    continue
                }
                if (key != null) {
                    val oldText = recentTextsByKey[key]
                    if (oldText == text) {
                        // Identical line already on screen — skip but refresh recency.
                        recentTextsByKey.remove(key)
                        recentTextsByKey[key] = text
                        continue
                    }
                    if (oldText != null) {
                        if (replaceLineInTextView(oldText, line)) {
                            recentTextsByKey.remove(key)
                            recentTextsByKey[key] = text
                            evictOldRecents()
                            continue
                        }
                        recentTextsByKey.remove(key)
                    }
                    recentTextsByKey[key] = text
                    evictOldRecents()
                }
            }

            if (textView.length() > 0) textView.append("\n")
            textView.append(line)
        }

        trimIfNeeded()

        if (autoScrollEnabled) {
            if (skipAnimation) {
                post { fullScroll(FOCUS_DOWN) }
            } else {
                smoothScrollToBottom()
            }
        }
    }

    /** Apply line-height / table-shrink spans to a single line's builder. */
    private fun applyLineStyling(line: SpannableStringBuilder, text: String) {
        if (line.length == 0) return
        if (LineDedup.isTableRow(text)) {
            line.setSpan(
                RelativeSizeSpan(tableShrinkRatio),
                0, line.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } else {
            // Pin every non-table line to a fixed per-line height so the
            // bullet-blink animation can't perturb vertical layout.
            line.setSpan(
                FixedLineHeightSpan(stableLineHeightPx),
                0, line.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun evictOldRecents() {
        while (recentTextsByKey.size > MAX_RECENT_LINES) {
            val it = recentTextsByKey.entries.iterator()
            it.next()
            it.remove()
        }
    }

    private fun trimIfNeeded() {
        val len = textView.length()
        if (len > MAX_CHARS) {
            val keepFrom = len - TRIM_TO
            val tail = textView.text.subSequence(keepFrom, len)
            textView.text = tail
            rebuildDedupFromView()
        }
    }

    /**
     * Walk the entire visible history and remove every line whose dedup key
     * matches a *later* line (newest wins). Spans on surviving lines are
     * preserved because we delete in-place via Editable.delete on the
     * existing text — we never re-tokenize and rebuild. Called when the
     * user toggles broom from raw → clean mode, signalling "clean this up
     * for me" on already-rendered content.
     */
    fun cleanupHistory() {
        val source = textView.text as? android.text.Spannable ?: return
        val str = source.toString()
        if (str.isEmpty()) return

        data class LineRange(val start: Int, val end: Int)
        val lines = mutableListOf<LineRange>()
        var lineStart = 0
        for (i in str.indices) {
            if (str[i] == '\n') {
                lines.add(LineRange(lineStart, i))
                lineStart = i + 1
            }
        }
        lines.add(LineRange(lineStart, str.length))

        val keyToLatestIdx = HashMap<String, Int>()
        for ((idx, range) in lines.withIndex()) {
            if (range.start == range.end) continue
            val line = str.substring(range.start, range.end)
            if (line.isBlank()) continue
            if (LineDedup.isPureTableDecoration(line)) continue
            val key = LineDedup.keyFor(line) ?: continue
            keyToLatestIdx[key] = idx
        }

        val survives = BooleanArray(lines.size)
        var droppedCount = 0
        for ((idx, range) in lines.withIndex()) {
            if (range.start == range.end) {
                survives[idx] = true
                continue
            }
            val line = str.substring(range.start, range.end)
            if (line.isBlank()) {
                survives[idx] = true
                continue
            }
            if (LineDedup.isPureTableDecoration(line)) {
                survives[idx] = false
                droppedCount++
                continue
            }
            val key = LineDedup.keyFor(line)
            survives[idx] = if (key == null) {
                // No skeleton, not a table — drop (junk lone glyph etc.)
                false
            } else {
                keyToLatestIdx[key] == idx
            }
            if (!survives[idx]) droppedCount++
        }

        if (droppedCount == 0) return

        val builder = SpannableStringBuilder()
        var first = true
        for ((idx, range) in lines.withIndex()) {
            if (!survives[idx]) continue
            if (!first) builder.append('\n')
            first = false
            if (range.start < range.end) {
                builder.append(source.subSequence(range.start, range.end))
            }
        }

        applyLineSpansTo(builder)
        textView.setText(builder, TextView.BufferType.EDITABLE)
        rebuildDedupFromView()
    }

    /** Repopulate the runtime dedup map from on-screen lines. Newer (lower
     *  in the file) occurrences overwrite earlier ones so the map matches
     *  what the user sees. */
    private fun rebuildDedupFromView() {
        recentTextsByKey.clear()
        if (!dedupEnabled) return
        val lines = textView.text.toString().split('\n')
        for (line in lines) {
            if (line.isBlank()) continue
            if (LineDedup.isPureTableDecoration(line)) continue
            val key = LineDedup.keyFor(line) ?: continue
            recentTextsByKey[key] = line
        }
        evictOldRecents()
    }

    /** Walk the entire current text and apply per-line styling spans
     *  (FixedLineHeightSpan for non-tables, RelativeSizeSpan for tables).
     *  Existing spans of either type are stripped first so repeated calls
     *  (settings change, chunk prepend, content swap) do not stack.
     *  Stacked RelativeSizeSpans compound multiplicatively — that was
     *  shrinking tables to nothing when the user adjusted the slider. */
    private fun applyLineSpansTo(builder: android.text.Spannable) {
        val len = builder.length
        if (len == 0) return
        // Wipe any pre-existing instances of either span type across the
        // whole range so we never double-apply.
        for (span in builder.getSpans(0, len, RelativeSizeSpan::class.java)) {
            builder.removeSpan(span)
        }
        for (span in builder.getSpans(0, len, FixedLineHeightSpan::class.java)) {
            builder.removeSpan(span)
        }
        val str = builder.toString()
        var lineStart = 0
        for (i in str.indices) {
            if (str[i] == '\n') {
                if (i > lineStart) {
                    val line = str.substring(lineStart, i)
                    applySpanForLine(builder, lineStart, i, line)
                }
                lineStart = i + 1
            }
        }
        if (lineStart < str.length) {
            val line = str.substring(lineStart, str.length)
            applySpanForLine(builder, lineStart, str.length, line)
        }
    }

    private fun applySpanForLine(builder: android.text.Spannable, start: Int, end: Int, line: String) {
        if (LineDedup.isTableRow(line)) {
            builder.setSpan(
                RelativeSizeSpan(tableShrinkRatio),
                start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } else {
            builder.setSpan(
                FixedLineHeightSpan(stableLineHeightPx),
                start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun replaceLineInTextView(oldText: String, newText: CharSequence): Boolean {
        if (oldText.isEmpty()) return false
        val editable = textView.editableText ?: return false
        val s = editable.toString()
        var idx = s.indexOf(oldText)
        while (idx >= 0) {
            val lineStart = idx
            val lineEnd = idx + oldText.length
            val precededByNewline = lineStart == 0 || s[lineStart - 1] == '\n'
            val followedByNewline = lineEnd == s.length || s[lineEnd] == '\n'
            if (precededByNewline && followedByNewline) {
                editable.replace(lineStart, lineEnd, newText)
                return true
            }
            idx = s.indexOf(oldText, idx + 1)
        }
        return false
    }

    private fun smoothScrollToBottom() {
        post {
            val targetY = textView.height - height
            if (targetY > scrollY) {
                smoothScrollTo(0, targetY)
            }
        }
    }

    private fun ensureScrolledToBottom() {
        if (userTouching) return
        val maxScroll = textView.height - height
        if (maxScroll > 0 && scrollY < maxScroll - 10) {
            scrollTo(0, maxScroll)
        }
    }

    /**
     * Replace all content (e.g., when loading saved session history). The
     * incoming text is treated as already deduped (HistoryBuffer maintains
     * that invariant), but we still strip pure-decoration rows defensively.
     */
    fun setContent(styled: SpannableStringBuilder) {
        applyLineSpansTo(styled)
        textView.text = styled
        recentTextsByKey.clear()
        rebuildDedupFromView()
        resetDiskPaging()
        post { fullScroll(FOCUS_DOWN) }
    }

    fun clear() {
        exitSelectionMode()
        pendingLines.clear()
        historyModeBuffer.clear()
        textView.text = ""
        autoScrollEnabled = true
        recentTextsByKey.clear()
        resetDiskPaging()
    }

    private fun resetDiskPaging() {
        loadingOlder = false
        historyDiskBytesLoaded = 0L
        historyDiskOffsetFromEnd = 0L
        noMoreOlderHistory = false
    }

    /**
     * Toggle whether `appendLines` collapses semi-duplicate lines via the
     * runtime dedup. Off = raw view (every line shown); on = clean view.
     */
    fun setDedupEnabled(enabled: Boolean) {
        dedupEnabled = enabled
        if (!enabled) recentTextsByKey.clear()
    }

    fun scrollToBottom() {
        exitSelectionMode()
        autoScrollEnabled = true
        suppressScrollDetection = true
        onHistoryModeChanged?.invoke(false)

        if (historyDiskBytesLoaded > 0L && textView.length() > MAX_CHARS) {
            val len = textView.length()
            val keepFrom = len - TRIM_TO
            textView.text = textView.text.subSequence(keepFrom, len)
            recentTextsByKey.clear()
        }
        resetDiskPaging()

        if (historyModeBuffer.isNotEmpty()) {
            val buffered = ArrayList(historyModeBuffer)
            historyModeBuffer.clear()
            appendBatchToView(buffered)
        }

        post { fullScroll(FOCUS_DOWN) }
        postDelayed({ suppressScrollDetection = false }, 600)
        startBatchTimer()
    }

    fun isViewingHistory(): Boolean = !autoScrollEnabled

    fun currentTextByteSize(): Long =
        textView.text.toString().toByteArray(Charsets.UTF_8).size.toLong()

    fun calculateColumns(): Int {
        val paint = textView.paint
        val charWidth = paint.measureText("M")
        return if (charWidth > 0) ((width - textView.paddingLeft - textView.paddingRight) / charWidth).toInt() else 80
    }

    fun calculateRows(): Int {
        val lineHeight = if (stableLineHeightPx > 0) stableLineHeightPx else textView.lineHeight
        return if (lineHeight > 0) (height / lineHeight) else 24
    }

}
