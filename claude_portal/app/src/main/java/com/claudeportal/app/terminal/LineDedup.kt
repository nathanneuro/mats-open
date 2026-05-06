package com.claudeportal.app.terminal

/**
 * Newest-wins line deduplication. Used by both TerminalView (live render) and
 * HistoryBuffer (persistent disk file) so that what reaches storage matches
 * what reaches the screen.
 *
 * Two keying strategies:
 *  - Non-table rows: skeleton (digits → '#', case-folded, non-alnum collapsed,
 *    truncated). Aggressive — collapses minor re-renders / progress lines.
 *  - Table rows (≥2 unicode pipes, ≥3 ascii pipes, or any joint glyph): exact
 *    line text. Strict, so two table rows that differ only in their numbers
 *    are both kept (e.g. "│ step │ loss │ 1 │ 0.5 │" vs "│ step │ loss │ 2 │ 0.4 │").
 *
 * Pure-decoration table rows (borders / corners / whitespace only — no letter
 * or digit) carry no information; they are dropped so dedup doesn't pile up
 * runs of "├──┼──┤" between data rows that have all gone away.
 */
object LineDedup {
    private val NUMBERS = Regex("\\d+\\.?\\d*")
    private val NONALNUM = Regex("[^A-Za-z0-9#]+")
    private const val SKELETON_MAX_LEN = 80

    private val TABLE_JOINT_CHARS = setOf(
        '├', '┤', '┬', '┴', '┼',
        '┌', '┐', '└', '┘',
        '╔', '╗', '╚', '╝', '╠', '╣', '╦', '╩', '╬'
    )

    /** Aggressive structural fingerprint for non-table content. */
    fun skeleton(text: String): String {
        val lowered = text.lowercase().trim()
        if (lowered.isEmpty()) return ""
        val numbersStripped = NUMBERS.replace(lowered, "#")
        val collapsed = NONALNUM.replace(numbersStripped, " ").trim()
        return if (collapsed.length > SKELETON_MAX_LEN) {
            collapsed.substring(0, SKELETON_MAX_LEN)
        } else collapsed
    }

    /** True iff the line contains table joint glyphs or enough column separators
     *  to be treated as a table row. */
    fun isTableRow(text: String): Boolean {
        var unicodePipes = 0
        var asciiPipes = 0
        for (ch in text) {
            if (ch in TABLE_JOINT_CHARS) return true
            if (ch == '│') unicodePipes++
            else if (ch == '|') asciiPipes++
        }
        return unicodePipes >= 2 || asciiPipes >= 3
    }

    /** A line that has no letters or digits — only symbols / punctuation /
     *  whitespace. Carries no information whether it's a table fence
     *  (├──┼──┤), a lone bullet (●), a separator (────), a stray prompt
     *  marker (❯), or a fence (===). Always dropped. Blank lines are not
     *  classified as symbol-only — they have semantic value as separators. */
    fun isSymbolOnly(text: String): Boolean {
        if (text.isBlank()) return false
        for (ch in text) {
            if (ch.isLetterOrDigit()) return false
        }
        return true
    }

    /** Compatibility alias — pure-decoration tables are a strict subset of
     *  symbol-only lines now. */
    fun isPureTableDecoration(text: String): Boolean = isSymbolOnly(text)

    /** Compute a dedup key for a line, or null if the line should be kept
     *  un-deduped (e.g. blank, or content with no skeleton). */
    fun keyFor(text: String): String? {
        if (isTableRow(text)) return "T:" + text.trimEnd()
        val skel = skeleton(text)
        return if (skel.isEmpty()) null else "N:$skel"
    }

    /** Apply newest-wins dedup to a list of lines:
     *   - Symbol-only lines are dropped entirely.
     *   - Blank/whitespace-only runs collapse to a single blank.
     *   - Tables dedup by exact text, non-tables by skeleton. */
    fun dedup(lines: List<String>): List<String> {
        if (lines.isEmpty()) return lines
        val lastIdxByKey = HashMap<String, Int>(lines.size)
        val keysByIdx = HashMap<Int, String>(lines.size)
        val drop = HashSet<Int>()
        for ((idx, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            if (isSymbolOnly(line)) {
                drop.add(idx)
                continue
            }
            val key = keyFor(line) ?: continue
            keysByIdx[idx] = key
            lastIdxByKey[key] = idx
        }
        val out = ArrayList<String>(lines.size)
        var lastWasBlank = false
        for ((idx, line) in lines.withIndex()) {
            if (idx in drop) continue
            if (line.isBlank()) {
                if (lastWasBlank) continue
                out.add(line)
                lastWasBlank = true
                continue
            }
            val key = keysByIdx[idx]
            if (key == null || lastIdxByKey[key] == idx) {
                out.add(line)
                lastWasBlank = false
            }
        }
        // Trim trailing/leading blanks.
        while (out.isNotEmpty() && out.first().isBlank()) out.removeAt(0)
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
        return out
    }

    /** Build the set of dedup keys present in `text` (newline-separated). Used
     *  to filter chunks loaded from disk against what's already on screen. */
    fun keysIn(text: String): HashSet<String> {
        val keys = HashSet<String>()
        var i = 0
        val s = text
        while (i < s.length) {
            var j = s.indexOf('\n', i)
            if (j < 0) j = s.length
            if (j > i) {
                val line = s.substring(i, j)
                if (line.isNotBlank() && !isPureTableDecoration(line)) {
                    keyFor(line)?.let { keys.add(it) }
                }
            }
            i = j + 1
        }
        return keys
    }

    /** Strip symbol-only lines, collapse blank-line runs, and drop any line
     *  whose dedup key is already in `knownKeys` (already on screen) or
     *  shadowed by a later occurrence inside the chunk itself. Newest
     *  wins everywhere. Returns the deduped chunk text; may be empty if
     *  the entire chunk was duplicates / decoration. */
    fun dedupChunkAgainstKnown(chunk: String, knownKeys: Set<String>): String {
        if (chunk.isEmpty()) return chunk
        val lines = chunk.split('\n')
        val lastInChunk = HashMap<String, Int>(lines.size)
        val keysByIdx = HashMap<Int, String>(lines.size)
        val drop = HashSet<Int>()
        for ((idx, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            if (isSymbolOnly(line)) {
                drop.add(idx); continue
            }
            val key = keyFor(line) ?: continue
            keysByIdx[idx] = key
            lastInChunk[key] = idx
        }
        val kept = ArrayList<String>(lines.size)
        var lastWasBlank = false
        for ((idx, line) in lines.withIndex()) {
            if (idx in drop) continue
            if (line.isBlank()) {
                if (lastWasBlank) continue
                kept.add(line)
                lastWasBlank = true
                continue
            }
            val key = keysByIdx[idx]
            val keep = key == null || (key !in knownKeys && lastInChunk[key] == idx)
            if (keep) {
                kept.add(line)
                lastWasBlank = false
            }
        }
        // Trim leading / trailing blanks so a chunk that was "blank, dup,
        // blank" doesn't show up as a stripe of empty lines.
        var lo = 0
        var hi = kept.size
        while (lo < hi && kept[lo].isBlank()) lo++
        while (hi > lo && kept[hi - 1].isBlank()) hi--
        if (lo >= hi) return ""
        return kept.subList(lo, hi).joinToString("\n")
    }
}
