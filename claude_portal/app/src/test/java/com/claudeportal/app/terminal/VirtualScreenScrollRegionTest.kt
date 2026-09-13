package com.claudeportal.app.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * DECSTBM scroll-region behaviour. Codex CLI parks its composer and footer
 * below a scroll region and pushes the transcript through it, so a screen
 * model that ignores the region writes new lines straight over the UI block.
 */
class VirtualScreenScrollRegionTest {

    private fun VirtualScreen.write(row: Int, text: String) {
        moveCursorTo(row, 0)
        text.forEach { writeChar(it, null, null, false, false, false) }
    }

    @Test
    fun `line feed at region bottom scrolls only the region`() {
        val screen = VirtualScreen(cols = 20, rows = 6)
        screen.setScrollRegion(0, 3)
        screen.write(0, "one")
        screen.write(1, "two")
        screen.write(2, "three")
        screen.write(3, "four")
        screen.write(4, "COMPOSER")
        screen.write(5, "FOOTER")

        screen.moveCursorTo(3, 0)
        screen.lineFeed()

        assertEquals("two", screen.getRowText(0))
        assertEquals("three", screen.getRowText(1))
        assertEquals("four", screen.getRowText(2))
        assertEquals("", screen.getRowText(3))
        // The block below the region is untouched — this is the whole point.
        assertEquals("COMPOSER", screen.getRowText(4))
        assertEquals("FOOTER", screen.getRowText(5))
    }

    @Test
    fun `reverse line feed at region top scrolls the region down`() {
        val screen = VirtualScreen(cols = 20, rows = 4)
        screen.setScrollRegion(1, 2)
        screen.write(0, "header")
        screen.write(1, "a")
        screen.write(2, "b")
        screen.write(3, "footer")

        screen.moveCursorTo(1, 0)
        screen.reverseLineFeed()

        assertEquals("header", screen.getRowText(0))
        assertEquals("", screen.getRowText(1))
        assertEquals("a", screen.getRowText(2))
        assertEquals("footer", screen.getRowText(3))
    }

    @Test
    fun `line feed inside the region just moves the cursor`() {
        val screen = VirtualScreen(cols = 10, rows = 5)
        screen.setScrollRegion(0, 3)
        screen.write(0, "top")
        screen.moveCursorTo(1, 0)
        screen.lineFeed()

        assertEquals(2, screen.cursorRow)
        assertEquals("top", screen.getRowText(0))
    }

    @Test
    fun `an inverted or out-of-range region falls back to the full screen`() {
        val screen = VirtualScreen(cols = 10, rows = 4)
        screen.setScrollRegion(3, 1)
        assertEquals(0, screen.scrollTop)
        assertEquals(3, screen.scrollBottom)

        screen.setScrollRegion(0, 99)
        assertEquals(0, screen.scrollTop)
        assertEquals(3, screen.scrollBottom)
    }

    @Test
    fun `resetting the region restores whole-screen scrolling`() {
        val screen = VirtualScreen(cols = 10, rows = 4)
        screen.setScrollRegion(0, 1)
        screen.resetScrollRegion()
        screen.write(0, "a")
        screen.write(3, "d")

        screen.moveCursorTo(3, 0)
        screen.lineFeed()

        assertEquals("", screen.getRowText(0))
        assertEquals("d", screen.getRowText(2))
        assertEquals("", screen.getRowText(3))
    }
}
