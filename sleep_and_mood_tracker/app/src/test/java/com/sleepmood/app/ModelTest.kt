package com.sleepmood.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ModelTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val nov17 = LocalDate.of(2026, 11, 17)
    private fun at(d: LocalDate, h: Int, m: Int) = LocalDateTime.of(d, LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    @Test fun bedtimeBeforeMidnightIsTheEveningBefore() {
        val (s, e) = nightInterval(nov17, LocalTime.of(23, 30), LocalTime.of(7, 0), zone)
        assertEquals(at(nov17.minusDays(1), 23, 30), s)
        assertEquals(at(nov17, 7, 0), e)
    }

    @Test fun bedtimeAfterMidnightIsTheSameDate() {
        val (s, e) = nightInterval(nov17, LocalTime.of(1, 30), LocalTime.of(9, 0), zone)
        assertEquals(at(nov17, 1, 30), s)
        assertEquals(at(nov17, 9, 0), e)
    }

    @Test fun napPastMidnightEndsNextDay() {
        val (s, e) = napInterval(nov17, LocalTime.of(23, 50), LocalTime.of(0, 20), zone)
        assertEquals(at(nov17, 23, 50), s)
        assertEquals(at(nov17.plusDays(1), 0, 20), e)
    }

    @Test fun nightBelongsToWakeDayAndOpenNapsAreIgnored() {
        val entries = listOf(
            Entry.new(Entry.Type.SLEEP, at(nov17.minusDays(1), 23, 0), end = at(nov17, 7, 0), value = 2),
            Entry.new(Entry.Type.NAP, at(nov17, 13, 0), end = at(nov17, 13, 40)),
            Entry.new(Entry.Type.NAP, at(nov17, 16, 0)), // still in progress
            Entry.new(Entry.Type.MOOD, at(nov17, 9, 0), value = 2),
            Entry.new(Entry.Type.MOOD, at(nov17, 15, 0), value = 4),
            Entry.new(Entry.Type.ENERGY, at(nov17.minusDays(1), 20, 0), value = 1),
        )
        val days = daySummaries(entries, zone)
        assertEquals(listOf(nov17, nov17.minusDays(1)), days.map { it.date })
        val d = days[0]
        assertEquals(480L, d.sleepMinutes)
        assertEquals(1, d.naps.size)
        assertEquals(40L, d.napMinutes)
        assertEquals(3.0, d.moodMean!!, 1e-9)
        assertNull(d.energyMean)
        assertNull(days[1].night)
    }

    @Test fun sleepDiaryCsvRowsAndQuoting() {
        val entries = listOf(
            Entry.new(Entry.Type.SLEEP, at(nov17.minusDays(1), 23, 15), end = at(nov17, 6, 45), value = 3),
            Entry.new(Entry.Type.NOTE, at(nov17, 10, 0), text = "slept badly, \"woke\" twice"),
        )
        val lines = sleepDiaryCsv(entries, zone).trimEnd().lines()
        assertEquals(2, lines.size)
        assertEquals("2026-11-17,23:15,06:45,7.5,3,0,0,0,0,,0,,0,,\"slept badly, \"\"woke\"\" twice\"", lines[1])
    }

    @Test fun jsonRoundTripAndStoreRewrite() {
        val dir = Files.createTempDirectory("sleepmood").toFile()
        val store = Store(File(dir, "entries.jsonl"))
        val a = Entry.new(Entry.Type.NAP, at(nov17, 13, 0))
        val b = Entry.new(Entry.Type.NOTE, at(nov17, 14, 0), text = "line one\nline two")
        store.add(a); store.add(b)
        assertEquals(listOf(a, b), store.load())
        val ended = a.copy(end = at(nov17, 13, 30))
        store.replaceAll(listOf(ended, b))
        assertEquals(listOf(ended, b), store.load())
        dir.deleteRecursively()
    }

    @Test fun emotionsAreCountedPerDayInTheDiary() {
        val entries = listOf(
            Entry.new(Entry.Type.EMOTIONS, at(nov17, 9, 0), text = "irritable, burnt out"),
            Entry.new(Entry.Type.EMOTIONS, at(nov17, 15, 0), text = "hopeful, irritable"),
        )
        assertEquals(listOf("irritable", "burnt out", "hopeful", "irritable"), daySummaries(entries, zone)[0].emotions)
        assertEquals("irritable x2; burnt out; hopeful", emotionCounts(daySummaries(entries, zone)[0].emotions))
        assertEquals("2026-11-17,,,,,0,0,0,0,,0,,0,irritable x2; burnt out; hopeful,", sleepDiaryCsv(entries, zone).trimEnd().lines()[1])
    }

    @Test fun meditationIsSummedSeparatelyFromNaps() {
        val entries = listOf(
            Entry.new(Entry.Type.MEDITATION, at(nov17, 7, 30), end = at(nov17, 7, 50)),
            Entry.new(Entry.Type.MEDITATION, at(nov17, 21, 0), end = at(nov17, 21, 10)),
            Entry.new(Entry.Type.MEDITATION, at(nov17, 22, 0)), // in progress
            Entry.new(Entry.Type.NAP, at(nov17, 13, 0), end = at(nov17, 13, 30)),
        )
        val d = daySummaries(entries, zone)[0]
        assertEquals(2, d.meditations.size)
        assertEquals(30L, d.meditationMinutes)
        assertEquals(30L, d.napMinutes)
        assertEquals("2026-11-17,,,,,1,30,2,30,,0,,0,,", sleepDiaryCsv(entries, zone).trimEnd().lines()[1])
    }

    @Test fun sleepChartRowRunsSixPmToSixPm() {
        val entries = listOf(
            Entry.new(Entry.Type.SLEEP, at(nov17.minusDays(1), 23, 0), end = at(nov17, 7, 0), value = 3),
            Entry.new(Entry.Type.NAP, at(nov17, 14, 0), end = at(nov17, 15, 0)),
            Entry.new(Entry.Type.MEDITATION, at(nov17, 17, 30), end = at(nov17, 18, 30)), // crosses the row edge
        )
        val rows = actogramRows(entries, zone, listOf(nov17, nov17.plusDays(1)))
        val h = 1f / 24
        assertEquals(3, rows[0].size)
        assertEquals(5 * h, rows[0][0].from, 1e-4f); assertEquals(13 * h, rows[0][0].to, 1e-4f)
        assertEquals(20 * h, rows[0][1].from, 1e-4f); assertEquals(21 * h, rows[0][1].to, 1e-4f)
        assertEquals(23.5f * h, rows[0][2].from, 1e-4f); assertEquals(1f, rows[0][2].to, 1e-4f)
        assertEquals(1, rows[1].size)
        assertEquals(Entry.Type.MEDITATION, rows[1][0].type)
        assertEquals(0f, rows[1][0].from, 1e-4f); assertEquals(0.5f * h, rows[1][0].to, 1e-4f)
    }

    @Test fun emotionTotalsAndFamilies() {
        val entries = listOf(
            Entry.new(Entry.Type.EMOTIONS, at(nov17, 9, 0), text = "irritable, burnt out"),
            Entry.new(Entry.Type.EMOTIONS, at(nov17.minusDays(3), 9, 0), text = "irritable, some old word"),
        )
        val byDate = daySummaries(entries, zone).associateBy { it.date }
        // Ties keep the order first logged, oldest day first.
        assertEquals(listOf("irritable" to 2, "some old word" to 1, "burnt out" to 1), emotionTotals(byDate, dayRange(nov17.minusDays(6), nov17)))
        assertEquals(listOf("irritable" to 1, "burnt out" to 1), emotionTotals(byDate, dayRange(nov17.minusDays(1), nov17)))
        assertEquals("Anger", familyOf("irritable")!!.name)
        assertNull(familyOf("some old word"))
    }

    @Test fun whenTextIsRelativeToToday() {
        assertEquals("today 14:02", whenText(at(nov17, 14, 2), zone, nov17))
        assertEquals("yesterday 21:30", whenText(at(nov17.minusDays(1), 21, 30), zone, nov17))
        assertEquals("Sun 15 Nov 08:00", whenText(at(nov17.minusDays(2), 8, 0), zone, nov17))
    }

    @Test fun emotionNamesAreUniqueAndCommaFree() {
        val all = EMOTION_FAMILIES.flatMap { it.emotions }
        assertEquals(all.size, all.toSet().size)
        assertEquals(emptyList<String>(), all.filter { ',' in it })
        assertEquals(all.toSet(), ALL_EMOTIONS)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ratingsOutsideOneToFiveAreRejected() {
        Entry.new(Entry.Type.MOOD, 0, value = 6)
    }
}
