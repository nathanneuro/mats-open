package com.claudeportal.app.terminal

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
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
 * Incoming lines go into a pending queue. A display timer (every ~100ms) pulls
 * from the queue and appends a batch. If the queue has more than a screen's
 * worth of lines, it skips to the latest screen-sized piece. This prevents
 * rapid unreadable scrolling during high-throughput output (e.g. cat large file).
 *
 * When the user scrolls up ("history mode"), the view freezes in place —
 * new content is appended but the scroll position stays locked. Only the
 * user or the scroll-to-bottom FAB can resume auto-scroll.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    private val textView: TextView = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        setTextColor(0xFFD3D7CF.toInt()) // Light grey
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(16, 8, 16, 8)
        // Text selection disabled — it forces an Editable backing store which
        // makes append/trim O(n) with span fixup, causing ANR on large text.
        setTextIsSelectable(false)
        // Disable content capture to prevent OOM — Android's ContentCapture copies
        // the entire SpannableStringBuilder on every text change
        importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO
    }

    private var autoScrollEnabled = true
    private var userTouching = false
    private var suppressScrollDetection = false
    private var selectionMode = false

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

    // Disk-paged scrollback tracking
    private var loadingOlder = false
    private var historyDiskBytesLoaded = 0L
    private var noMoreOlderHistory = false

    // Batched display queue
    private val pendingLines = ConcurrentLinkedQueue<SpannableStringBuilder>()
    private val handler = Handler(Looper.getMainLooper())
    private var batchTimerRunning = false

    // History mode buffer: lines received while user is scrolling back.
    // Kept in memory (not appended to TextView) to avoid expensive relayouts.
    // Flushed when history mode exits.
    private val historyModeBuffer = mutableListOf<SpannableStringBuilder>()

    // Skeleton-keyed dedup: maps each recently-displayed line's skeleton
    // (digits replaced by '#') to its exact text. When a new line shares
    // a skeleton with an existing entry, the older line is removed from the
    // view and the new one appears at the bottom — so repetitive lines
    // ("Step 5: loss=0.5" → "Step 6: loss=0.3", or `tail -f` re-emissions)
    // visually "update in place" while always being current at the bottom.
    // Disabled in dirty-history mode so the raw fallback shows everything.
    private val recentTextsBySkeleton = LinkedHashMap<String, String>()
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
        // Max recent line texts to remember for skeleton dedup
        private const val MAX_RECENT_LINES = 500
        // Cap on total bytes paged from disk so the TextView doesn't OOM.
        private const val MAX_HISTORY_DISK_BYTES = 1_500_000L

        /** Replace digit sequences (including decimals like 3.14) with # */
        private val SKELETON_NUMBERS = Regex("\\d+\\.?\\d*")
        /** Collapse runs of whitespace so spacing variations don't split skeletons. */
        private val SKELETON_WHITESPACE = Regex("\\s+")
        /** Leading UI markers that vary frame-to-frame (selectors, bullets, arrows). */
        private val SKELETON_LEADING_MARKERS =
            Regex("^[❯>●○◑◐◒◓◔◕✶✻✽·✢*⏵▶▸→\\-•◦‣⁃]+\\s*")

        fun skeleton(text: String): String {
            val trimmed = text.trim()
            val noLeader = SKELETON_LEADING_MARKERS.replace(trimmed, "")
            val numbersStripped = SKELETON_NUMBERS.replace(noLeader, "#")
            return SKELETON_WHITESPACE.replace(numbersStripped, " ")
        }
    }

    init {
        setBackgroundColor(0xFF1E1E1E.toInt()) // Dark background
        addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        isFillViewport = true

        setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            // History-mode entry detection requires an active touch and not
            // being in a suppressed window; paging-from-disk does not, so it
            // must run before the early return.
            val maxScroll = textView.height - height
            val atBottom = scrollY >= maxScroll - 50

            // Only page in older content from disk once the user has scrolled
            // all the way to the top of what's currently loaded. Triggering
            // earlier kept growing the in-memory buffer while plenty of
            // already-loaded content was still off-screen above.
            if (!autoScrollEnabled && scrollY <= 0 && oldScrollY > 0) {
                tryLoadOlder()
            }

            if (!userTouching || suppressScrollDetection) return@setOnScrollChangeListener

            if (scrollY < oldScrollY && !atBottom) {
                // User scrolled up — enter history mode
                if (autoScrollEnabled) {
                    autoScrollEnabled = false
                    // Clear touch flag and suppress scroll detection so layout
                    // changes from hiding keyboard/bars don't re-trigger mode.
                    // The scroll gesture is semantically "done" at this point.
                    userTouching = false
                    suppressScrollDetection = true
                    onHistoryModeChanged?.invoke(true)
                    postDelayed({ suppressScrollDetection = false }, 1000)
                }
            // Scrolling down does NOT exit history mode — only the
            // scroll-to-bottom FAB (via scrollToBottom()) can do that.
        }
        }
    }

    /**
     * Page in the next older history chunk from disk. Triggered when the user
     * has scrolled near the top of the in-memory buffer in history mode.
     * Prepends to the TextView and offsets scrollY so the user's view stays
     * locked on the same content (no jump). Capped at MAX_HISTORY_DISK_BYTES.
     */
    private fun tryLoadOlder() {
        if (loadingOlder || noMoreOlderHistory) return
        if (historyDiskBytesLoaded >= MAX_HISTORY_DISK_BYTES) return
        val cb = onLoadOlder ?: return
        loadingOlder = true
        onLoadOlderStateChanged?.invoke(true)
        val loadStartedAt = System.currentTimeMillis()
        val skipFromEndBytes = currentTextByteSize()
        cb(skipFromEndBytes) { chunk ->
            post {
                try {
                    if (chunk.isNullOrEmpty()) {
                        noMoreOlderHistory = true
                        return@post
                    }
                    val priorTextHeight = textView.height
                    val priorScroll = scrollY
                    val builder = SpannableStringBuilder(chunk)
                    if (textView.length() > 0 && !chunk.endsWith('\n')) {
                        builder.append('\n')
                    }
                    builder.append(textView.text)
                    textView.text = builder
                    historyDiskBytesLoaded += chunk.toByteArray(Charsets.UTF_8).size.toLong()
                    // Restore visual position: the user was looking at the same
                    // content, but it has shifted down by the prepended height.
                    post {
                        val delta = textView.height - priorTextHeight
                        if (delta > 0) scrollTo(0, priorScroll + delta)
                    }
                } finally {
                    loadingOlder = false
                    // Keep the spinner visible for a minimum window so the
                    // user actually sees it even when the read was instant
                    // or returned nothing.
                    val elapsed = System.currentTimeMillis() - loadStartedAt
                    val minVisibleMs = 500L
                    val remaining = (minVisibleMs - elapsed).coerceAtLeast(0L)
                    postDelayed({ onLoadOlderStateChanged?.invoke(false) }, remaining)
                }
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        handleTouchTracking(ev)
        // Don't run long-press detection while already selecting — let the
        // selectable TextView handle drags / handle-grabs natively.
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
                // Mark as user-touching on actual drag, not on taps.
                userTouching = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Delay clearing so fling scroll events still count as user-initiated
                if (userTouching) {
                    postDelayed({ userTouching = false }, 500)
                }
            }
        }
    }

    /**
     * Enter selection / copy mode at the given scroll-view coordinates.
     * Freezes live updates (history mode), turns the TextView selectable so
     * Android's native action bar (Copy / Select All / Share) appears, and
     * places the selection caret at the touched word.
     *
     * Selectable mode is normally off because it forces an Editable backing
     * store with O(n) span fixup on every append — fine while frozen.
     */
    private fun enterSelectionMode(scrollX: Float, scrollY: Float) {
        if (selectionMode) return
        selectionMode = true

        // Freeze live output: same path as user-initiated scroll-up.
        if (autoScrollEnabled) {
            autoScrollEnabled = false
            userTouching = false
            suppressScrollDetection = true
            onHistoryModeChanged?.invoke(true)
            postDelayed({ suppressScrollDetection = false }, 1000)
        }

        textView.setTextIsSelectable(true)

        // Translate scrollview-local coords into TextView-local coords.
        val tvX = scrollX - textView.left
        val tvY = scrollY - textView.top + this.scrollY

        // Defer one frame so the selectable transition completes before
        // we ask Android to start its selection action mode.
        post {
            val offset = textView.getOffsetForPosition(tvX, tvY)
            val text = textView.text
            if (offset in 0..text.length && text is Spannable) {
                // Select the whitespace-bounded word at the offset.
                var start = offset
                var end = offset
                while (start > 0 && !text[start - 1].isWhitespace()) start--
                while (end < text.length && !text[end].isWhitespace()) end++
                if (start == end && start < text.length) end = start + 1
                try {
                    Selection.setSelection(text, start, end)
                    textView.requestFocus()
                    // Triggers Android's selection action mode (Copy / Share).
                    textView.performLongClick()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun exitSelectionMode() {
        if (!selectionMode) return
        selectionMode = false
        // Drop selectable backing store so live appends are cheap again.
        textView.setTextIsSelectable(false)
        textView.importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO
    }

    fun setFontSize(sp: Float) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    /**
     * Append deduplicated lines from the OutputProcessor.
     * Lines are queued and flushed in batches for smooth display.
     * Under heavy load, oldest pending lines are dropped to prevent OOM.
     */
    fun appendLines(lines: List<SpannableStringBuilder>) {
        for (line in lines) {
            pendingLines.add(line)
        }
        // Backpressure: if pending queue is huge, drop oldest lines.
        // This prevents OOM when output arrives faster than we can render
        // (e.g. 20k lines/sec from `cat` or rapid logging). The dropped
        // lines still exist on disk via HistoryBuffer.
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
            // In live mode, ensure we're at the bottom even if no new lines —
            // layout changes (status bar resize, keyboard) can shift scroll position.
            if (autoScrollEnabled) {
                ensureScrolledToBottom()
            }
            if (pendingLines.isNotEmpty() || autoScrollEnabled) {
                // Keep ticking in live mode for scroll correction
                handler.postDelayed(this, BATCH_INTERVAL_MS)
            } else {
                batchTimerRunning = false
            }
        }
    }

    private fun flushPendingLines() {
        if (pendingLines.isEmpty()) return

        // Drain all pending lines
        val batch = mutableListOf<SpannableStringBuilder>()
        while (true) {
            val line = pendingLines.poll() ?: break
            batch.add(line)
        }

        if (batch.isEmpty()) return

        // History mode: stash lines in memory instead of touching the TextView.
        // This avoids expensive relayouts that cause ANR while the user is reading.
        if (!autoScrollEnabled) {
            historyModeBuffer.addAll(batch)
            // Cap the buffer to prevent OOM — keep latest lines only
            while (historyModeBuffer.size > MAX_PENDING_LINES) {
                historyModeBuffer.removeAt(0)
            }
            return
        }

        appendBatchToView(batch)
    }

    /** Append a batch of lines to the TextView and scroll.
     *  Lines whose skeleton matches the last displayed line replace it in-place,
     *  so rapidly updating output (tail -f, progress bars) doesn't flood the view. */
    private fun appendBatchToView(batch: List<SpannableStringBuilder>) {
        // If we have a huge batch (way more than a screen), keep only the
        // last screen's worth for display. This prevents the TextView from
        // being asked to layout thousands of lines at once, which causes
        // jank and OOM. The full content is on disk via HistoryBuffer.
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

            // Blank lines: always append, never dedup
            if (text.isBlank()) {
                if (textView.length() > 0) textView.append("\n")
                textView.append(line)
                continue
            }

            // Skeleton dedup: if a recently-displayed line had the same
            // skeleton (same structure, possibly different digits), drop
            // the older occurrence so the fresh copy appears at the bottom.
            // Newest version always wins. Subsumes exact-text dedup since
            // skeleton(text) == text for digit-free lines.
            if (dedupEnabled) {
                val skel = skeleton(text)
                if (skel.isNotEmpty()) {
                    val oldText = recentTextsBySkeleton.remove(skel)
                    if (oldText != null) {
                        removeLineFromTextView(oldText)
                    }
                    recentTextsBySkeleton[skel] = text
                    while (recentTextsBySkeleton.size > MAX_RECENT_LINES) {
                        val it = recentTextsBySkeleton.entries.iterator()
                        it.next()
                        it.remove()
                    }
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

    private fun trimIfNeeded() {
        val len = textView.length()
        if (len > MAX_CHARS) {
            // Replace entire text with just the tail — avoids the expensive
            // editable.delete(0, N) which copies the whole buffer and triggers
            // a full relayout with span fixup.
            val keepFrom = len - TRIM_TO
            val tail = textView.text.subSequence(keepFrom, len)
            textView.text = tail
            // Lines that were trimmed off the top no longer occupy the view,
            // so they must not block re-emission via the dedup map. Forget
            // it entirely; lines still on screen will be re-tracked on the
            // next append.
            recentTextsBySkeleton.clear()
        }
    }

    /**
     * Find and remove an exact-match line from the TextView text. Used by
     * the move-to-bottom dedup so the freshest copy of a re-emitted line
     * always appears at the bottom rather than being stranded mid-buffer.
     * Span colors are preserved by copying through SpannableStringBuilder.
     */
    private fun removeLineFromTextView(text: String): Boolean {
        if (text.isEmpty()) return false
        val current = textView.text ?: return false
        val s = current.toString()
        var idx = s.indexOf(text)
        while (idx >= 0) {
            val lineStart = idx
            val lineEnd = idx + text.length
            val precededByNewline = lineStart == 0 || s[lineStart - 1] == '\n'
            val followedByNewline = lineEnd == s.length || s[lineEnd] == '\n'
            if (precededByNewline && followedByNewline) {
                val builder = SpannableStringBuilder(current)
                when {
                    lineEnd < s.length -> builder.delete(lineStart, lineEnd + 1)
                    lineStart > 0 -> builder.delete(lineStart - 1, lineEnd)
                    else -> builder.delete(lineStart, lineEnd)
                }
                textView.text = builder
                return true
            }
            idx = s.indexOf(text, idx + 1)
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

    /** Snap to bottom if not already there. Skips during active touch to avoid
     *  fighting the user's scroll gesture. */
    private fun ensureScrolledToBottom() {
        if (userTouching) return
        val maxScroll = textView.height - height
        if (maxScroll > 0 && scrollY < maxScroll - 10) {
            scrollTo(0, maxScroll)
        }
    }

    /**
     * Replace all content (e.g., when loading saved session history).
     */
    fun setContent(styled: SpannableStringBuilder) {
        textView.text = styled
        recentTextsBySkeleton.clear()
        resetDiskPaging()
        post { fullScroll(FOCUS_DOWN) }
    }

    fun clear() {
        exitSelectionMode()
        pendingLines.clear()
        historyModeBuffer.clear()
        textView.text = ""
        autoScrollEnabled = true
        recentTextsBySkeleton.clear()
        resetDiskPaging()
    }

    private fun resetDiskPaging() {
        loadingOlder = false
        historyDiskBytesLoaded = 0L
        noMoreOlderHistory = false
    }

    /**
     * Toggle whether `appendLines` collapses semi-duplicate lines via the
     * skeleton dedup. Off = raw view (every line shown); on = clean view.
     * Wired to MainActivity's broom toggle so the dirty-history fallback
     * stays a true superset.
     */
    fun setDedupEnabled(enabled: Boolean) {
        dedupEnabled = enabled
        if (!enabled) recentTextsBySkeleton.clear()
    }

    fun scrollToBottom() {
        exitSelectionMode()
        autoScrollEnabled = true
        suppressScrollDetection = true
        onHistoryModeChanged?.invoke(false)

        // If history mode paged in older content from disk, drop it now so
        // we don't carry MB of text into live mode (slow trim, slow scroll).
        if (historyDiskBytesLoaded > 0L && textView.length() > MAX_CHARS) {
            val len = textView.length()
            val keepFrom = len - TRIM_TO
            textView.text = textView.text.subSequence(keepFrom, len)
            recentTextsBySkeleton.clear()
        }
        resetDiskPaging()

        // Flush lines that arrived during history mode
        if (historyModeBuffer.isNotEmpty()) {
            val buffered = ArrayList(historyModeBuffer)
            historyModeBuffer.clear()
            appendBatchToView(buffered)
        }

        post { fullScroll(FOCUS_DOWN) }
        postDelayed({ suppressScrollDetection = false }, 600)
        // Restart batch timer for live-mode scroll correction
        startBatchTimer()
    }

    fun isViewingHistory(): Boolean = !autoScrollEnabled

    /** UTF-8 byte size of the text currently in the view. Used by disk-paging
     *  to know how far from the end of the source file the view starts. */
    fun currentTextByteSize(): Long =
        textView.text.toString().toByteArray(Charsets.UTF_8).size.toLong()

    fun calculateColumns(): Int {
        val paint = textView.paint
        val charWidth = paint.measureText("M")
        return if (charWidth > 0) ((width - textView.paddingLeft - textView.paddingRight) / charWidth).toInt() else 80
    }

    fun calculateRows(): Int {
        val lineHeight = textView.lineHeight
        return if (lineHeight > 0) (height / lineHeight) else 24
    }

}
