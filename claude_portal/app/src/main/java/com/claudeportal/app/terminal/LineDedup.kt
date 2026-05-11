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
    // Digit clusters NOT immediately preceded by a letter. Letter-attached
    // digits (the '0' in 'GPU0', the '4090' in 'RTX4090') are part of an
    // identifier and must survive skeletonization so sibling labels like
    // GPU0/GPU1 don't collapse to the same key. Standalone digits (values,
    // counts, timestamps, line numbers, percentages) are still fuzzed so
    // repeated metric lines with changing values still dedup.
    private val NUMBERS = Regex("(?<![A-Za-z])\\d+\\.?\\d*")
    private val NONALNUM = Regex("[^A-Za-z0-9#]+")
    private const val SKELETON_MAX_LEN = 80

    private val TABLE_JOINT_CHARS = setOf(
        '├', '┤', '┬', '┴', '┼',
        '┌', '┐', '└', '┘',
        '╔', '╗', '╚', '╝', '╠', '╣', '╦', '╩', '╬'
    )

    // Sentinel that brackets the user's tmux status bar. Must match the
    // STATUS_SENTINEL in OutputProcessor — kept private here to avoid a
    // dependency on OutputProcessor, which would create a cycle.
    private const val STATUS_SENTINEL = 'ƕ'

    /** True iff the line is part of the user's tmux status bar. The bar is
     *  shown live in the dedicated status bar UI above the terminal, so it
     *  should never be retained in scrollback. Without this, the bar's
     *  many `|` separators trigger isTableRow → exact-match keying →
     *  every GPU%/ctx% tick survives dedup and piles up. */
    fun isStatusBarLine(text: String): Boolean = text.contains(STATUS_SENTINEL)

    /** Aggressive structural fingerprint for non-table content.
     *
     *  Numbers in the *value* portion of a line are fuzzed to `#` so repeated
     *  metric/progress reports with changing values still dedup (this is
     *  what keeps Claude Code's mangled-text dedup aggressive — "Running…
     *  (3s)" and "Running… (4s)" still collapse).
     *
     *  Numbers in the *label* portion — the part before the first colon, when
     *  that part is a short letter-dominant token like "GPU 0" or "Step 1" —
     *  are preserved. Without this, `GPU 0: 21022/24564 MB` and
     *  `GPU 1: 22004/24564 MB` would skeletonize to the same key and one
     *  would silently overwrite the other. Timestamps like `12:34:56` and
     *  bare-number prefixes fail the label-like test (digit-dominant) and
     *  fall through to full fuzzing, so those still collapse across values. */
    fun skeleton(text: String): String {
        val lowered = text.lowercase().trim()
        if (lowered.isEmpty()) return ""
        val colonIdx = lowered.indexOf(':')
        val labelPart: String
        val valuePart: String
        if (colonIdx > 0 && isLabelLike(lowered, colonIdx)) {
            labelPart = lowered.substring(0, colonIdx)
            valuePart = lowered.substring(colonIdx)
        } else {
            labelPart = ""
            valuePart = lowered
        }
        val valueFuzzed = NUMBERS.replace(valuePart, "#")
        val combined = labelPart + valueFuzzed
        val collapsed = NONALNUM.replace(combined, " ").trim()
        return if (collapsed.length > SKELETON_MAX_LEN) {
            collapsed.substring(0, SKELETON_MAX_LEN)
        } else collapsed
    }

    /** Whether the substring `text[0, colonIdx)` looks like a metric/identifier
     *  label: short, contains at least one letter, and not digit-dominant.
     *  Used to decide whether to preserve digits in that prefix during
     *  skeletonization (so `GPU 0` / `GPU 1` get distinct keys) vs treating
     *  the colon as incidental punctuation (so `12:34:56` timestamps fully
     *  fuzz and dedup across different times). */
    private fun isLabelLike(text: String, colonIdx: Int): Boolean {
        if (colonIdx > 30) return false
        var letters = 0
        var digits = 0
        for (i in 0 until colonIdx) {
            val c = text[i]
            if (c.isLetter()) letters++
            else if (c.isDigit()) digits++
        }
        if (letters == 0) return false
        return digits.toFloat() / colonIdx < 0.5f
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

    // --- Generous overlap merge ----------------------------------------------
    //
    // Beyond skeleton-key dedup, two lines also count as the same line when
    // one's ASCII-letter sequence is a contiguous run inside the other's, or
    // the tail of one's letters overlaps the head of the other's. Only ASCII
    // letters are compared — digits, symbols and whitespace never count against
    // similarity, they're just carried into the merged result. This collapses
    // Claude Code's partial-render artifacts into the information-maximising
    // union, e.g.
    //   "-- The build has completed and is rea"
    // + "The build has completed and is ready to launch on your say so."
    // → "-- The build has completed and is ready to launch on your say so."
    // and, when a value inside the overlap conflicts (a job's countdown),
    //   "The job is running and has 10min 30sec remaining."
    // + "has 9min 20sec remaining."
    // → "The job is running and has 9min 20sec remaining."  (newer side wins).

    /** Minimum length of the shared letter run for two lines to be merged.
     *  Short coincidental overlaps ("GPU 0…" / "GPU 1…" → "gpu…") stay distinct. */
    private const val MIN_MERGE_LETTERS = 8

    /** How many recently-seen lines to scan for an overlap with each new one. */
    const val MERGE_SCAN_WINDOW = 24

    /** Lower-cased ASCII letters of [text] plus a parallel map from each
     *  letter's position in the projection back to its index in [text]. */
    private fun letterProjection(text: String): Pair<String, IntArray> {
        val sb = StringBuilder(text.length)
        val idx = IntArray(text.length)
        var n = 0
        for (i in text.indices) {
            val c = text[i]
            when (c) {
                in 'A'..'Z' -> { sb.append(c + 32); idx[n++] = i }
                in 'a'..'z' -> { sb.append(c); idx[n++] = i }
            }
        }
        return sb.toString() to idx.copyOf(n)
    }

    private data class Alignment(val ox: Int, val oy: Int, val m: Int)

    /** Longest common run lx[ox, ox+m) == ly[oy, oy+m) that is anchored to the
     *  start of at least one string and the end of at least one — i.e. full
     *  containment or a head/tail overlap, never an interior-only match like a
     *  shared prefix of two otherwise-different lines. Null if no such run is
     *  at least [MIN_MERGE_LETTERS] long. */
    private fun bestAlignment(lx: String, ly: String): Alignment? {
        if (lx.length < MIN_MERGE_LETTERS || ly.length < MIN_MERGE_LETTERS) return null
        var best: Alignment? = null
        fun consider(a: Alignment) {
            if (a.m < MIN_MERGE_LETTERS) return
            val startAnchored = a.ox == 0 || a.oy == 0
            val endAnchored = a.ox + a.m == lx.length || a.oy + a.m == ly.length
            if (!startAnchored || !endAnchored) return
            if (best == null || a.m > best!!.m) best = a
        }
        ly.indexOf(lx).let { if (it >= 0) consider(Alignment(0, it, lx.length)) }
        lx.indexOf(ly).let { if (it >= 0) consider(Alignment(it, 0, ly.length)) }
        val maxK = minOf(lx.length, ly.length)
        // suffix(lx) == prefix(ly)
        for (k in maxK downTo MIN_MERGE_LETTERS) {
            if (lx.regionMatches(lx.length - k, ly, 0, k)) { consider(Alignment(lx.length - k, 0, k)); break }
        }
        // prefix(lx) == suffix(ly)
        for (k in maxK downTo MIN_MERGE_LETTERS) {
            if (lx.regionMatches(0, ly, ly.length - k, k)) { consider(Alignment(0, ly.length - k, k)); break }
        }
        return best
    }

    /** Splice [x] and [y] into the information-maximising union when their
     *  letter sequences overlap (see above). [yIsNewer] decides which side's
     *  characters win inside the shared region. Returns the merged text, or
     *  null when the two don't overlap enough to be considered the same line.
     *  Table rows and symbol-only lines never merge. */
    fun mergeOverlap(x: String, y: String, yIsNewer: Boolean): String? {
        if (x.isBlank() || y.isBlank()) return null
        if (isTableRow(x) || isTableRow(y)) return null
        if (isSymbolOnly(x) || isSymbolOnly(y)) return null
        val (lx, mapX) = letterProjection(x)
        val (ly, mapY) = letterProjection(y)
        val al = bestAlignment(lx, ly) ?: return null
        val (ox, oy, m) = al

        // HEAD — everything left of the common region. By the start-anchor
        // rule at most one side reaches further left; if neither does, both
        // begin at the common region's first letter and we keep whichever
        // leading non-letter run is longer.
        val headX = x.substring(0, mapX[ox])
        val headY = y.substring(0, mapY[oy])
        val head = when {
            ox > 0 -> headX
            oy > 0 -> headY
            else -> if (headY.length >= headX.length) headY else headX
        }

        // COMMON — the overlap, verbatim from the newer line so a changed
        // value inside it reflects the latest render.
        val (cs, cMap, cOff) = if (yIsNewer) Triple(y, mapY, oy) else Triple(x, mapX, ox)
        val common = cs.substring(cMap[cOff], cMap[cOff + m - 1] + 1)

        // TAIL — everything right of the common region (incl. the non-letter
        // "glue" right after the last shared letter so words don't fuse).
        val xHasTail = ox + m < lx.length
        val yHasTail = oy + m < ly.length
        val tail = when {
            xHasTail -> x.substring(mapX[ox + m - 1] + 1)
            yHasTail -> y.substring(mapY[oy + m - 1] + 1)
            else -> {
                val (ts, tMap, tEnd) =
                    if (yIsNewer) Triple(y, mapY, oy + m - 1) else Triple(x, mapX, ox + m - 1)
                ts.substring(tMap[tEnd] + 1)
            }
        }

        return head + common + tail
    }

    /** One forward pass over [lines]: each line that overlap-merges with one of
     *  the previous [MERGE_SCAN_WINDOW] survivors replaces that survivor with
     *  the merged union (the survivor moves to the position of the newer line
     *  — newest wins). Blank lines pass through untouched. */
    fun mergePass(lines: List<String>): List<String> {
        if (lines.size < 2) return lines
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (line.isBlank() || isTableRow(line) || isSymbolOnly(line)) { out.add(line); continue }
            var hit = -1
            var merged: String? = null
            var scanned = 0
            var i = out.size - 1
            while (i >= 0 && scanned < MERGE_SCAN_WINDOW) {
                val cand = out[i]
                if (cand.isNotBlank() && !isTableRow(cand) && !isSymbolOnly(cand)) {
                    scanned++
                    val mrg = mergeOverlap(cand, line, yIsNewer = true)
                    if (mrg != null) { hit = i; merged = mrg; break }
                }
                i--
            }
            if (merged != null) {
                out.removeAt(hit)
                out.add(merged)
            } else {
                out.add(line)
            }
        }
        return out
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
            if (isStatusBarLine(line) || isSymbolOnly(line)) {
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
        return mergePass(out)
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
            if (isStatusBarLine(line) || isSymbolOnly(line)) {
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
        return mergePass(kept.subList(lo, hi)).joinToString("\n")
    }
}
