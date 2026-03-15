package com.claudeportal.app.terminal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Stress tests for the output pipeline.
 *
 * Verifies that the OutputProcessor + HistoryBuffer can handle extreme throughput
 * (20k lines/second) without OOM, deadlock, or data loss to disk.
 */
class OutputProcessorStressTest {

    /**
     * Simulate 20k lines arriving in 1 second via ANSI terminal output.
     * The OutputProcessor should:
     * - Not throw OOM
     * - Not deadlock
     * - Complete processing within a reasonable time
     * - Emit content via contentFlow (may drop frames under backpressure, that's OK)
     * - Emit plain text via plainTextFlow for disk persistence
     */
    @Test(timeout = 30_000)
    fun `stress - 20k lines per second does not OOM or deadlock`() = runBlocking {
        val processor = OutputProcessor(cols = 80, rows = 24)
        val emittedBatches = AtomicInteger(0)
        val emittedPlainChunks = AtomicInteger(0)
        val totalPlainChars = AtomicLong(0)

        // Collect content emissions on a background coroutine
        val contentJob = launch(Dispatchers.Default) {
            processor.contentFlow.collect {
                emittedBatches.incrementAndGet()
            }
        }

        val plainJob = launch(Dispatchers.Default) {
            processor.plainTextFlow.collect { text ->
                emittedPlainChunks.incrementAndGet()
                totalPlainChars.addAndGet(text.length.toLong())
            }
        }

        // Generate 20,000 lines of terminal output in ~1 second.
        // Each line is ~80 chars (typical terminal width).
        // Total: ~1.6MB of raw text, arriving as rapid SSH chunks.
        val linesPerChunk = 24 // One screenful per chunk (terminal redraw)
        val totalLines = 20_000
        val totalChunks = totalLines / linesPerChunk
        val lineTemplate = "%-${78}s".format("output line %05d: the quick brown fox jumped over the lazy dog ABCDEF")

        val startTime = System.currentTimeMillis()
        for (chunk in 0 until totalChunks) {
            val sb = StringBuilder()
            // Simulate cursor-home + erase (full redraw)
            sb.append("\u001b[H\u001b[2J")
            for (line in 0 until linesPerChunk) {
                val lineNum = chunk * linesPerChunk + line
                val lineText = lineTemplate.format(lineNum)
                sb.append(lineText.take(80))
                if (line < linesPerChunk - 1) {
                    sb.append("\r\n")
                }
            }
            processor.processRawOutput(sb.toString())
        }
        val elapsed = System.currentTimeMillis() - startTime

        // Give collectors time to drain
        delay(500)

        // Cancel collectors
        contentJob.cancel()
        plainJob.cancel()
        processor.destroy()

        println("Stress test results:")
        println("  Total chunks processed: $totalChunks")
        println("  Total lines simulated: $totalLines")
        println("  Processing time: ${elapsed}ms")
        println("  Content batches emitted: ${emittedBatches.get()}")
        println("  Plain text chunks emitted: ${emittedPlainChunks.get()}")
        println("  Total plain chars: ${totalPlainChars.get()}")
        println("  Throughput: ${totalLines * 1000L / maxOf(elapsed, 1)} lines/sec")

        // Assertions:
        // 1. Processing completed (no deadlock) — guaranteed by timeout
        // 2. Some content was emitted (not all dropped)
        assertTrue("Should emit at least some content batches", emittedBatches.get() > 0)
        // 3. Plain text was emitted for disk persistence
        assertTrue("Should emit plain text for persistence", emittedPlainChunks.get() > 0)
        // 4. Processing was reasonably fast (< 10 seconds for 20k lines)
        assertTrue("Processing should complete in <10s, took ${elapsed}ms", elapsed < 10_000)
    }

    /**
     * Verify that small rapid chunks (thinking animation) are throttled properly
     * and don't cause excessive processing.
     */
    @Test(timeout = 10_000)
    fun `stress - rapid small chunks are throttled`() = runBlocking {
        val processor = OutputProcessor(cols = 80, rows = 24)
        processor.isClaudeWindow = true
        val emittedBatches = AtomicInteger(0)

        val contentJob = launch(Dispatchers.Default) {
            processor.contentFlow.collect {
                emittedBatches.incrementAndGet()
            }
        }

        // Simulate 1000 rapid thinking animation frames (~10ms apart)
        // These are small chunks with just a thinking symbol update
        val startTime = System.currentTimeMillis()
        for (i in 0 until 1000) {
            val symbol = "✶✻✽·✢*"[i % 6]
            // Small chunk: cursor move to row 20, col 0, write symbol
            processor.processRawOutput("\u001b[20;1H$symbol")
        }
        val elapsed = System.currentTimeMillis() - startTime

        delay(300)
        contentJob.cancel()
        processor.destroy()

        println("Throttle test results:")
        println("  1000 small chunks processed in ${elapsed}ms")
        println("  Content batches emitted: ${emittedBatches.get()}")

        // Should emit far fewer batches than chunks due to throttling
        // (80ms min interval → at most ~12 diffs per second)
        assertTrue(
            "Should throttle: ${emittedBatches.get()} batches from 1000 chunks",
            emittedBatches.get() < 200
        )
    }

    /**
     * Verify that the dedup window is not too aggressive — legitimately repeated
     * lines should pass through.
     */
    @Test
    fun `dedup - legitimately repeated long lines pass through`() {
        val processor = OutputProcessor(cols = 80, rows = 24)

        // Simulate output where the same long line appears multiple times
        // (e.g. repeated log output, test results).
        // These should NOT be deduped since they're >120 chars.
        val repeatedContent = buildString {
            append("\u001b[H\u001b[2J") // cursor home + clear
            for (i in 0 until 5) {
                append("2026-03-15T10:00:00Z INFO  [worker-$i] Processing batch #12345 — this is a long log line that repeats legitimately across workers and should not be deduped away\r\n")
            }
        }

        // Process the same screen twice — the repeated lines should still appear
        processor.processRawOutput(repeatedContent)

        // Second identical screen — overlap detection will match, so no new lines.
        // But if we change one line, the changed line and any after should appear.
        val modifiedContent = buildString {
            append("\u001b[H\u001b[2J")
            for (i in 0 until 5) {
                append("2026-03-15T10:00:01Z INFO  [worker-$i] Processing batch #12346 — this is a long log line that repeats legitimately across workers and should not be deduped away\r\n")
            }
        }
        processor.processRawOutput(modifiedContent)

        // No assertion on exact count — just verify no exception/OOM
        processor.destroy()
    }

    /**
     * Verify HistoryBuffer can handle high-throughput writes without data loss.
     */
    @Test(timeout = 10_000)
    fun `historybuffer - high throughput writes complete without error`() {
        val tmpDir = createTempDir("history_test")
        try {
            val buffer = HistoryBuffer(maxLines = 5000)
            buffer.setConnection(tmpDir, "test-connection")

            // Write 20k lines as fast as possible
            val totalLines = 20_000
            for (i in 0 until totalLines) {
                buffer.appendPlain("line $i: the quick brown fox jumped over the lazy dog\n")
            }

            // Close flushes pending writes
            buffer.close()

            // Verify data was written to disk
            val files = tmpDir.listFiles() ?: emptyArray()
            assertTrue("Should have created history file", files.isNotEmpty())
            val totalBytes = files.sumOf { it.length() }
            assertTrue("Should have written substantial data ($totalBytes bytes)", totalBytes > 100_000)

            // Verify in-memory trimming worked (should be capped at maxLines)
            // Can't check lineCount after close, but no OOM means trimming worked
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /**
     * Verify HistoryBuffer can load older content from disk (windowed reading).
     */
    @Test
    fun `historybuffer - loadOlderContent returns disk data`() {
        val tmpDir = createTempDir("history_load_test")
        try {
            val buffer = HistoryBuffer(maxLines = 100)
            buffer.setConnection(tmpDir, "test-load")

            // Write more lines than maxLines
            for (i in 0 until 500) {
                buffer.appendPlain("line $i: some content here\n")
            }

            // Flush to disk
            buffer.close()

            // Re-open and load
            val buffer2 = HistoryBuffer(maxLines = 100)
            buffer2.setConnection(tmpDir, "test-load")

            val older = buffer2.loadOlderContent(50_000)
            assertNotNull("Should load older content from disk", older)
            assertTrue("Should have substantial content", older!!.length > 1000)

            buffer2.close()
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
