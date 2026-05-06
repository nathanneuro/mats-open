package com.claudeportal.app.terminal

import android.graphics.Paint
import android.text.style.LineHeightSpan

/**
 * Force a line to a fixed pixel height regardless of the font metrics of the
 * glyphs it contains. Without this, lines that contain bullet symbols (●,
 * ◑, ❯, etc.) — which Android renders via a system fallback font with
 * different metrics — measure taller than adjacent monospace lines. When
 * Claude Code "blinks" the bullet on/off, the line's height oscillates and
 * the rendered text appears to wiggle vertically. Holding a fixed height per
 * line keeps the layout still even as glyph metrics change.
 */
class FixedLineHeightSpan(private val heightPx: Int) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence?,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt
    ) {
        if (heightPx <= 0) return
        val origHeight = fm.descent - fm.ascent
        if (origHeight <= 0) return
        // Preserve the ascent/descent ratio of the dominant font, just rescale
        // the total to heightPx so all lines occupy the same vertical slot.
        val scale = heightPx.toFloat() / origHeight
        fm.ascent = (fm.ascent * scale).toInt()
        fm.descent = fm.ascent + heightPx
        fm.top = minOf(fm.top, fm.ascent)
        fm.bottom = maxOf(fm.bottom, fm.descent)
        // Cap top/bottom so they don't blow out the height when fallback fonts
        // report extreme values.
        if (fm.top < fm.ascent) fm.top = fm.ascent
        if (fm.bottom > fm.descent) fm.bottom = fm.descent
    }
}
