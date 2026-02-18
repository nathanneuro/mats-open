package com.claudeportal.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.claudeportal.app.models.ArrowPosition

/**
 * Transparent overlay with up/down arrow buttons for sending arrow key escape sequences.
 *
 * These buttons sit on either the left or right edge of the screen (configurable in settings).
 * They are semi-transparent so they don't obstruct the terminal output. Tapping them sends
 * the corresponding arrow key escape sequence to the SSH session - essential since mobile
 * keyboards don't have arrow keys, and these are needed constantly with Claude Code
 * (scrolling through history, navigating menus, etc.).
 */
class ArrowOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onArrowUp: (() -> Unit)? = null
    var onArrowDown: (() -> Unit)? = null
    var position: ArrowPosition = ArrowPosition.RIGHT
        set(value) {
            field = value
            invalidate()
        }
    var buttonOpacity: Float = 0.4f
        set(value) {
            field = value
            bgPaint.alpha = (value * 255).toInt()
            arrowPaint.alpha = (minOf(value + 0.3f, 1.0f) * 255).toInt()
            invalidate()
        }
    var vibrateOnPress: Boolean = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
        alpha = (0.4f * 255).toInt()
        style = Paint.Style.FILL
    }

    private val bgPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF555555.toInt()
        alpha = (0.6f * 255).toInt()
        style = Paint.Style.FILL
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        alpha = (0.7f * 255).toInt()
        style = Paint.Style.FILL
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF666666.toInt()
        alpha = (0.3f * 255).toInt()
        strokeWidth = 2f
    }

    private val buttonSize = 56f  // dp — square buttons
    private val buttonGap = 8f   // dp gap between buttons
    private val bottomMargin = 24f // dp from bottom of view

    private var upPressed = false
    private var downPressed = false

    private val density = context.resources.displayMetrics.density
    private val buttonSizePx = buttonSize * density
    private val buttonGapPx = buttonGap * density
    private val bottomMarginPx = bottomMargin * density
    private val cornerRadius = 12f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Button position (left or right edge, near the bottom)
        val buttonLeft = if (position == ArrowPosition.RIGHT) viewWidth - buttonSizePx - 8 * density else 8 * density
        val buttonRight = buttonLeft + buttonSizePx

        // Down button is at the bottom
        val downBottom = viewHeight - bottomMarginPx
        val downTop = downBottom - buttonSizePx

        // Up button is above the down button with a gap
        val upBottom = downTop - buttonGapPx
        val upTop = upBottom - buttonSizePx

        // Up button
        val upRect = RectF(buttonLeft, upTop, buttonRight, upBottom)
        canvas.drawRoundRect(upRect, cornerRadius, cornerRadius, if (upPressed) bgPressedPaint else bgPaint)

        // Down button
        val downRect = RectF(buttonLeft, downTop, buttonRight, downBottom)
        canvas.drawRoundRect(downRect, cornerRadius, cornerRadius, if (downPressed) bgPressedPaint else bgPaint)

        // Up arrow
        drawArrow(canvas, buttonLeft + buttonSizePx / 2, upTop + buttonSizePx / 2, true)

        // Down arrow
        drawArrow(canvas, buttonLeft + buttonSizePx / 2, downTop + buttonSizePx / 2, false)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, isUp: Boolean) {
        val arrowSize = 12f * density
        val path = Path()
        if (isUp) {
            path.moveTo(cx, cy - arrowSize / 2)
            path.lineTo(cx + arrowSize / 2, cy + arrowSize / 2)
            path.lineTo(cx - arrowSize / 2, cy + arrowSize / 2)
        } else {
            path.moveTo(cx, cy + arrowSize / 2)
            path.lineTo(cx + arrowSize / 2, cy - arrowSize / 2)
            path.lineTo(cx - arrowSize / 2, cy - arrowSize / 2)
        }
        path.close()
        canvas.drawPath(path, arrowPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val buttonLeft = if (position == ArrowPosition.RIGHT) viewWidth - buttonSizePx - 8 * density else 8 * density
        val buttonRight = buttonLeft + buttonSizePx

        val downBottom = viewHeight - bottomMarginPx
        val downTop = downBottom - buttonSizePx
        val upBottom = downTop - buttonGapPx
        val upTop = upBottom - buttonSizePx

        // Only handle touches within the button areas
        if (event.x < buttonLeft || event.x > buttonRight) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y >= upTop && event.y <= upBottom) {
                    upPressed = true
                    if (vibrateOnPress) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    onArrowUp?.invoke()
                } else if (event.y >= downTop && event.y <= downBottom) {
                    downPressed = true
                    if (vibrateOnPress) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    onArrowDown?.invoke()
                } else {
                    return false
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                upPressed = false
                downPressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
