package com.claudeportal.app.terminal

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for LineDedup's generous overlap-merge: when one line's ASCII-letter
 * sequence is a contiguous run inside the other's (or their letter runs overlap
 * head-to-tail), the two are merged into the information-maximising union.
 * Non-letters (digits, symbols, whitespace) don't count toward the match —
 * they're only carried into the result, and on a conflict inside the overlap
 * the newer line's characters win.
 */
class LineDedupMergeTest {

    private fun merge(older: String, newer: String) =
        LineDedup.mergeOverlap(older, newer, yIsNewer = true)

    @Test fun prefixSubsetMergesPreservingBothEnds() {
        // Newer line is a (decorated) prefix of the older full line.
        assertEquals(
            "-- The build has completed and is ready to launch on your say so.",
            merge(
                "The build has completed and is ready to launch on your say so.",
                "-- The build has completed and is rea"
            )
        )
        // Order-independent for the non-conflicting case.
        assertEquals(
            "-- The build has completed and is ready to launch on your say so.",
            merge(
                "-- The build has completed and is rea",
                "The build has completed and is ready to launch on your say so."
            )
        )
    }

    @Test fun suffixSubsetMergesWithNewerValuesWinning() {
        assertEquals(
            "The job is running and has 9min 20sec remaining.",
            merge(
                "The job is running and has 10min 30sec remaining.",
                "has 9min 20sec remaining."
            )
        )
    }

    @Test fun partialRenderCompletesInPlace() {
        assertEquals(
            "Reading file foo.py",
            merge("Reading file foo.p", "Reading file foo.py")
        )
    }

    @Test fun headTailOverlapStitches() {
        // Suffix of older ("...wrapped onto") overlaps prefix of newer.
        assertEquals(
            "a long line that wrapped onto the next visual row",
            merge("a long line that wrapped onto", "wrapped onto the next visual row")
        )
    }

    @Test fun shortCoincidentalOverlapDoesNotMerge() {
        // "gpu"/"mb" letters are well under the 8-letter floor.
        assertNull(merge("GPU 0: 21022/24564 MB", "GPU 1: 22004/24564 MB"))
    }

    @Test fun sharedInteriorPrefixOfTwoDifferentLinesDoesNotMerge() {
        assertNull(merge("Reading file foo.py", "Reading file bar.py"))
    }

    @Test fun tableRowsNeverMerge() {
        assertNull(merge("│ step │ loss │ 1 │ 0.5 │", "│ step │ loss │ 2 │ 0.4 │"))
    }

    @Test fun symbolOnlyLinesNeverMerge() {
        assertNull(merge("──────────────", "────────"))
    }

    @Test fun mergePassCollapsesAdjacentPartials() {
        val out = LineDedup.mergePass(
            listOf(
                "unrelated line one",
                "The build has completed and is rea",
                "The build has completed and is ready to go.",
                "unrelated line two"
            )
        )
        // The two partials collapse to the union, which lands where the newer
        // partial was (between the two unrelated lines).
        assertEquals(
            listOf(
                "unrelated line one",
                "The build has completed and is ready to go.",
                "unrelated line two"
            ),
            out
        )
    }
}
