package com.claudeportal.app.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.LeadingMarginSpan
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
        setTextIsSelectable(true)
        // Disable content capture to prevent OOM — Android's ContentCapture copies
        // the entire SpannableStringBuilder on every text change
        importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO
    }

    // Latest-line accent: thin left-edge bar on the most recently appended batch
    private var accentSpanStart = -1
    private var accentSpanEnd = -1
    private var accentSpan: LatestLineSpan? = null

    private var autoScrollEnabled = true
    private var userTouching = false
    private var suppressScrollDetection = false

    /** Called when history mode changes: true = viewing history, false = live. */
    var onHistoryModeChanged: ((Boolean) -> Unit)? = null

    // Batched display queue
    private val pendingLines = ConcurrentLinkedQueue<SpannableStringBuilder>()
    private val handler = Handler(Looper.getMainLooper())
    private var batchTimerRunning = false

    companion object {
        private const val MAX_CHARS = 100_000
        private const val TRIM_TO = 75_000
        private const val BATCH_INTERVAL_MS = 100L
        private const val VISIBLE_ROWS_ESTIMATE = 30
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
     */
    fun appendLines(lines: List<SpannableStringBuilder>) {
        for (line in lines) {
            pendingLines.add(line)
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

        val skipAnimation = batch.size > VISIBLE_ROWS_ESTIMATE

        // In history mode, save scroll position before appending
        val savedScrollY = if (!autoScrollEnabled) scrollY else -1

        val combined = SpannableStringBuilder()
        for ((i, line) in batch.withIndex()) {
            if (i > 0 || textView.length() > 0) {
                combined.append("\n")
            }
            combined.append(line)
        }

        // Remove previous latest-line accent
        val editable = textView.editableText
        val oldSpan = accentSpan
        if (oldSpan != null && editable != null) {
            editable.removeSpan(oldSpan)
        }

        val batchStart = textView.length()
        textView.append(combined)

        // Apply accent bar to this batch
        val newEditable = textView.editableText
        if (newEditable != null) {
            val barWidth = 3 * resources.displayMetrics.density
            val span = LatestLineSpan(0x60D97706, barWidth)  // semi-transparent Claude orange
            newEditable.setSpan(span, batchStart, newEditable.length, 0)
            accentSpan = span
            accentSpanStart = batchStart
            accentSpanEnd = newEditable.length
        }

        trimIfNeeded()

        if (!autoScrollEnabled && savedScrollY >= 0) {
            // History mode: lock scroll position so the view doesn't jump
            post { scrollTo(0, savedScrollY) }
        } else if (autoScrollEnabled) {
            if (skipAnimation) {
                post { fullScroll(FOCUS_DOWN) }
            } else {
                smoothScrollToBottom()
            }
        }
    }

    private fun trimIfNeeded() {
        val editable = textView.editableText
        if (editable != null && editable.length > MAX_CHARS) {
            val deleteEnd = editable.length - TRIM_TO
            editable.delete(0, deleteEnd)
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
        textView.text = ""
        accentSpan = null
        accentSpanStart = -1
        accentSpanEnd = -1
        autoScrollEnabled = true
    }

    fun scrollToBottom() {
        autoScrollEnabled = true
        suppressScrollDetection = true
        onHistoryModeChanged?.invoke(false)
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

    /**
     * A LeadingMarginSpan that draws a thin colored bar in the left margin.
     * Applied to the most recently appended batch of lines.
     */
    private class LatestLineSpan(private val barColor: Int, private val barWidth: Float) :
        LeadingMarginSpan {
        private val paint = Paint().apply {
            color = barColor
            style = Paint.Style.FILL
        }

        override fun getLeadingMargin(first: Boolean): Int = 0  // no indent

        override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int,
            top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int,
            first: Boolean, layout: android.text.Layout?
        ) {
            c.drawRect(x.toFloat(), top.toFloat(), x + barWidth, bottom.toFloat(), paint)
        }
    }
}
