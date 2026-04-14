package com.claudeportal.app.terminal

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.util.AttributeSet
import android.util.TypedValue
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

    companion object {
        // Keep the TextView small to avoid expensive relayout on append/trim.
        // Older content lives on disk via HistoryBuffer.
        private const val MAX_CHARS = 15_000
        private const val TRIM_TO = 10_000
        private const val BATCH_INTERVAL_MS = 500L
        private const val VISIBLE_ROWS_ESTIMATE = 30
        // Max pending lines before we start dropping oldest (backpressure)
        private const val MAX_PENDING_LINES = 500
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
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // Only mark as user-touching on actual drag, not on taps.
                // Taps can cause spurious scroll events (from trim or layout)
                // that would falsely trigger history mode.
                userTouching = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Delay clearing so fling scroll events still count as user-initiated
                if (userTouching) {
                    postDelayed({ userTouching = false }, 500)
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
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
            if (pendingLines.isNotEmpty()) {
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

    /** Append a batch of lines to the TextView and scroll. */
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

        val combined = SpannableStringBuilder()
        for ((i, line) in displayBatch.withIndex()) {
            if (i > 0 || textView.length() > 0) {
                combined.append("\n")
            }
            combined.append(line)
        }

        textView.append(combined)
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

    /**
     * Replace all content (e.g., when loading saved session history).
     */
    fun setContent(styled: SpannableStringBuilder) {
        textView.text = styled
        post { fullScroll(FOCUS_DOWN) }
    }

    fun clear() {
        pendingLines.clear()
        historyModeBuffer.clear()
        textView.text = ""
        autoScrollEnabled = true
    }

    fun scrollToBottom() {
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
