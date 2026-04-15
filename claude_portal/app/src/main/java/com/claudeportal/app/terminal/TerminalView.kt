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

    // Batched display queue
    private val pendingLines = ConcurrentLinkedQueue<SpannableStringBuilder>()
    private val handler = Handler(Looper.getMainLooper())
    private var batchTimerRunning = false

    // History mode buffer: lines received while user is scrolling back.
    // Kept in memory (not appended to TextView) to avoid expensive relayouts.
    // Flushed when history mode exits.
    private val historyModeBuffer = mutableListOf<SpannableStringBuilder>()

    // Skeleton of the last displayed line, for update-in-place dedup.
    // When a new line has the same skeleton as the last line, we replace
    // instead of appending — so progress bars and counters update in place.
    private var lastLineSkeleton: String? = null

    // Exact-text dedup: skip lines already displayed recently.
    // Handles `tail -f` where tmux re-emits the entire screen on each scroll,
    // producing ~20 duplicate lines per genuinely new line.
    private val recentLineTexts = LinkedHashSet<String>()

    companion object {
        // Keep the TextView small to avoid expensive relayout on append/trim.
        // Older content lives on disk via HistoryBuffer.
        private const val MAX_CHARS = 15_000
        private const val TRIM_TO = 10_000
        private const val BATCH_INTERVAL_MS = 500L
        private const val VISIBLE_ROWS_ESTIMATE = 30
        // Max pending lines before we start dropping oldest (backpressure)
        private const val MAX_PENDING_LINES = 500
        // Max recent line texts to remember for exact dedup
        private const val MAX_RECENT_LINES = 100

        /** Replace digit sequences (including decimals like 3.14) with # */
        private val SKELETON_NUMBERS = Regex("\\d+\\.?\\d*")

        fun skeleton(text: String): String =
            SKELETON_NUMBERS.replace(text.trim(), "#")
    }

    init {
        setBackgroundColor(0xFF1E1E1E.toInt()) // Dark background
        addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        isFillViewport = true

        setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            // Only detect history mode from user-initiated scrolls (touch),
            // and not when suppressed (during keyboard/bar show/hide transitions)
            if (!userTouching || suppressScrollDetection) return@setOnScrollChangeListener

            val maxScroll = textView.height - height
            val atBottom = scrollY >= maxScroll - 50

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

            // Skip blank lines for dedup — always append them
            if (text.isBlank()) {
                if (textView.length() > 0) textView.append("\n")
                textView.append(line)
                lastLineSkeleton = null
                continue
            }

            // Exact-text dedup: skip lines we've already displayed recently.
            // This handles `tail -f` where tmux re-emits the whole screen
            // on each scroll (~20 duplicates per 1 new line).
            if (text in recentLineTexts) continue

            val skel = skeleton(text)

            if (skel == lastLineSkeleton && skel.isNotEmpty() && textView.length() > 0) {
                // Same structure, different numbers → replace last line in-place.
                // Handles progress bars and counters that update values.
                val fullText = textView.text
                val lastNewline = fullText.toString().lastIndexOf('\n')
                if (lastNewline >= 0) {
                    val editable = textView.editableText
                    if (editable != null) {
                        editable.replace(lastNewline + 1, editable.length, line)
                    } else {
                        val before = fullText.subSequence(0, lastNewline + 1)
                        val rebuilt = SpannableStringBuilder(before).append(line)
                        textView.text = rebuilt
                    }
                } else {
                    textView.text = line
                }
                // Update the dedup set: remove old version, add new
                recentLineTexts.removeIf { skeleton(it) == skel }
            } else {
                // Normal append
                if (textView.length() > 0) textView.append("\n")
                textView.append(line)
            }

            recentLineTexts.add(text)
            while (recentLineTexts.size > MAX_RECENT_LINES) {
                recentLineTexts.iterator().next().also { recentLineTexts.remove(it) }
            }
            lastLineSkeleton = skel
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
        }
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
        lastLineSkeleton = null
        recentLineTexts.clear()
        post { fullScroll(FOCUS_DOWN) }
    }

    fun clear() {
        exitSelectionMode()
        pendingLines.clear()
        historyModeBuffer.clear()
        textView.text = ""
        autoScrollEnabled = true
        lastLineSkeleton = null
        recentLineTexts.clear()
    }

    fun scrollToBottom() {
        exitSelectionMode()
        autoScrollEnabled = true
        suppressScrollDetection = true
        onHistoryModeChanged?.invoke(false)

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
