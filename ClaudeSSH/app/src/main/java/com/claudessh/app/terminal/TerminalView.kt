package com.claudessh.app.terminal

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.NestedScrollView

/**
 * Scrollable terminal history view.
 *
 * This wraps a TextView inside a ScrollView, presenting terminal output as a scrollable
 * document. Scrolling is purely a local UI operation and does NOT send any keypresses
 * to the remote server. This is the key difference from traditional terminal scrollback.
 *
 * The view auto-scrolls to the bottom when new output arrives, unless the user has
 * manually scrolled up to read history.
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
    }

    private var isUserScrolling = false
    private var autoScrollEnabled = true

    init {
        setBackgroundColor(0xFF1E1E1E.toInt()) // Dark background
        addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        isFillViewport = true

        setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY < oldScrollY) {
                // User scrolled up
                isUserScrolling = true
                autoScrollEnabled = false
            }

            // Re-enable auto-scroll if user scrolled to bottom
            val maxScroll = textView.height - height
            if (scrollY >= maxScroll - 50) {
                isUserScrolling = false
                autoScrollEnabled = true
            }
        }
    }

    fun setFontSize(sp: Float) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    /**
     * Append styled text from the ANSI parser to the display.
     * Auto-scrolls to bottom unless user is manually browsing history.
     */
    fun appendOutput(styled: SpannableStringBuilder) {
        textView.append(styled)

        if (autoScrollEnabled) {
            post {
                fullScroll(FOCUS_DOWN)
            }
        }
    }

    /**
     * Replace all content (e.g., when loading saved session history).
     */
    fun setContent(styled: SpannableStringBuilder) {
        textView.text = styled
        post {
            fullScroll(FOCUS_DOWN)
        }
    }

    /**
     * Clear the display.
     */
    fun clear() {
        textView.text = ""
        autoScrollEnabled = true
    }

    /**
     * Scroll to the very bottom and re-enable auto-scroll.
     */
    fun scrollToBottom() {
        autoScrollEnabled = true
        post {
            fullScroll(FOCUS_DOWN)
        }
    }

    /**
     * Check if the user is currently browsing history (scrolled up from bottom).
     */
    fun isViewingHistory(): Boolean = isUserScrolling

    /**
     * Get the number of columns that fit at the current font size.
     * Used to set the PTY size on the SSH channel.
     */
    fun calculateColumns(): Int {
        val paint = textView.paint
        val charWidth = paint.measureText("M")
        return if (charWidth > 0) ((width - textView.paddingLeft - textView.paddingRight) / charWidth).toInt() else 80
    }

    /**
     * Get the number of rows visible at the current font size.
     */
    fun calculateRows(): Int {
        val lineHeight = textView.lineHeight
        return if (lineHeight > 0) (height / lineHeight) else 24
    }
}
