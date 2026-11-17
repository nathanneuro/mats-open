package com.sleepmood.app

import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * One logged thing. Times are epoch milliseconds.
 * - MOOD, ENERGY: value 1..5 at time [start]; [end] is null.
 * - SLEEP: a night, [start] = bedtime, [end] = wake time, value = quality 1..5.
 * - NAP, MEDITATION: [start]..[end]; [end] is null while it is in progress. value is null.
 * - NOTE: free text at time [start].
 * - EMOTIONS: the strong emotions felt at time [start], as names from [EMOTION_FAMILIES] joined by ", ".
 */
data class Entry(
    val id: String,
    val type: Type,
    val start: Long,
    val end: Long? = null,
    val value: Int? = null,
    val text: String? = null,
) {
    enum class Type { MOOD, ENERGY, SLEEP, NAP, NOTE, EMOTIONS, MEDITATION }

    init {
        when (type) {
            Type.MOOD, Type.ENERGY -> require(value in 1..5 && end == null) { "bad $type entry: $this" }
            Type.SLEEP -> require(value in 1..5 && end != null && end > start) { "bad SLEEP entry: $this" }
            Type.NAP, Type.MEDITATION -> require(value == null && (end == null || end > start)) { "bad $type entry: $this" }
            Type.NOTE -> require(!text.isNullOrBlank() && end == null) { "bad NOTE entry: $this" }
            Type.EMOTIONS -> require(end == null && value == null && !text.isNullOrBlank()) {
                "bad EMOTIONS entry: $this"
            }
        }
    }

    val emotions: List<String> get() = if (type == Type.EMOTIONS) text!!.split(", ") else emptyList()

    fun toJson(): String = JSONObject().apply {
        put("id", id); put("type", type.name); put("start", start)
        end?.let { put("end", it) }
        value?.let { put("value", it) }
        text?.let { put("text", it) }
    }.toString()

    companion object {
        fun new(type: Type, start: Long, end: Long? = null, value: Int? = null, text: String? = null) =
            Entry(UUID.randomUUID().toString(), type, start, end, value, text)

        fun fromJson(line: String): Entry {
            val o = JSONObject(line)
            return Entry(
                id = o.getString("id"),
                type = Type.valueOf(o.getString("type")),
                start = o.getLong("start"),
                end = if (o.has("end")) o.getLong("end") else null,
                value = if (o.has("value")) o.getInt("value") else null,
                text = if (o.has("text")) o.getString("text") else null,
            )
        }
    }
}

/**
 * Emotions to pick from, grouped by family the way an emotion wheel groups them, with the family's colour.
 * Within a family, roughly mild to intense. Names must stay unique and comma-free (entries store them joined by ", ").
 * Entries are not checked against this list on load, so renaming or dropping a name never breaks old data.
 */
data class EmotionFamily(val name: String, val color: Long, val emotions: List<String>)

val EMOTION_FAMILIES = listOf(
    EmotionFamily("Joy", 0xFFE8C547, listOf("happy", "delighted", "amused", "playful", "silly", "mischievous", "carefree", "celebratory", "proud", "triumphant", "satisfied", "grateful", "hopeful")),
    EmotionFamily("Peace", 0xFF94C47D, listOf("calm", "relaxed", "relieved", "grounded", "safe", "cozy", "rested", "at home", "patient", "unhurried", "accepting", "forgiving", "content")),
    EmotionFamily("Connection", 0xFFE88AA8, listOf("loving", "affectionate", "tender", "close", "connected", "belonging", "welcomed", "understood", "supported", "cared for", "appreciated", "trusting", "admiring", "compassionate", "protective", "generous", "devoted")),
    EmotionFamily("Wonder", 0xFF6CC3B5, listOf("curious", "fascinated", "absorbed", "in flow", "inspired", "creative", "eager", "adventurous", "open-minded", "in awe", "moved", "enchanted", "reverent")),
    EmotionFamily("Strength", 0xFFF0A050, listOf("confident", "capable", "determined", "motivated", "focused", "productive", "brave", "assertive", "decisive", "empowered", "resilient", "steady", "independent", "ambitious", "defiant")),
    EmotionFamily("Mixed", 0xFFB9D46A, listOf("bittersweet", "nostalgic", "wistful", "conflicted", "ambivalent", "humbled", "vindicated", "impressed", "anticipating", "in suspense", "tempted", "surprised", "confused", "disoriented")),
    EmotionFamily("Sadness", 0xFF6F95D8, listOf("sad", "lonely", "homesick", "grieving", "heartbroken", "melancholy", "disappointed", "disillusioned", "discouraged", "defeated", "hopeless", "empty", "abandoned", "regretful")),
    EmotionFamily("Fear", 0xFF9E86D8, listOf("worried", "nervous", "anxious", "on edge", "dread", "scared", "panicky", "insecure", "vulnerable", "overwhelmed", "helpless", "out of control", "suspicious")),
    EmotionFamily("Restlessness", 0xFFD47FD0, listOf("restless", "agitated", "jittery", "wired", "scattered", "distracted", "overstimulated", "pressured", "rushed", "trapped")),
    EmotionFamily("Anger", 0xFFE0725A, listOf("irritable", "annoyed", "frustrated", "impatient", "resentful", "bitter", "jealous", "envious", "betrayed", "indignant", "provoked", "defensive", "hostile", "spiteful", "furious")),
    EmotionFamily("Disgust", 0xFFA9A65A, listOf("disgusted", "repulsed", "appalled", "contemptuous", "judgmental", "cynical", "fed up")),
    EmotionFamily("Social pain", 0xFF5FB0E8, listOf("misunderstood", "unappreciated", "invisible", "rejected", "left out", "out of place", "criticized", "disrespected", "used", "smothered", "obligated")),
    EmotionFamily("Shame", 0xFFC98A5B, listOf("embarrassed", "self-conscious", "foolish", "exposed", "inadequate", "like a fraud", "self-critical", "guilty", "ashamed", "humiliated", "like a burden", "worthless", "self-loathing")),
    EmotionFamily("Withdrawal", 0xFF8E96A3, listOf("bored", "flat", "unmotivated", "stuck", "foggy", "wanting to hide", "avoidant", "isolating", "escapist", "checked out", "numb", "dissociated", "burnt out", "going through the motions")),
)
val ALL_EMOTIONS: Set<String> = EMOTION_FAMILIES.flatMap { it.emotions }.toSet()

/**
 * The night that ends on [wakeDate] at [wake], having started at [bed].
 * A bedtime later in the day than the wake time (23:30 -> 07:00) is taken to be the evening before;
 * an earlier one (01:30 -> 09:00) the same date.
 */
fun nightInterval(wakeDate: LocalDate, bed: LocalTime, wake: LocalTime, zone: ZoneId): Pair<Long, Long> {
    val wakeAt = LocalDateTime.of(wakeDate, wake)
    val bedDate = if (bed < wake) wakeDate else wakeDate.minusDays(1)
    val bedAt = LocalDateTime.of(bedDate, bed)
    return bedAt.atZone(zone).toInstant().toEpochMilli() to wakeAt.atZone(zone).toInstant().toEpochMilli()
}

/** A nap or meditation on [date] from [from] to [to]; if [to] is earlier than [from], it ran past midnight into [date]+1. */
fun napInterval(date: LocalDate, from: LocalTime, to: LocalTime, zone: ZoneId): Pair<Long, Long> {
    val s = LocalDateTime.of(date, from)
    val e = LocalDateTime.of(if (to > from) date else date.plusDays(1), to)
    return s.atZone(zone).toInstant().toEpochMilli() to e.atZone(zone).toInstant().toEpochMilli()
}

fun Long.toLocal(zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zone)

/**
 * Everything about one calendar day. The night belongs to the day you woke up on; naps, moods,
 * energy ratings and notes to the day they started.
 */
data class DaySummary(
    val date: LocalDate,
    val night: Entry?,
    val naps: List<Entry>,
    val meditations: List<Entry>,
    val moods: List<Int>,
    val energies: List<Int>,
    val notes: List<String>,
    val emotions: List<String>,
) {
    val sleepMinutes: Long? get() = night?.let { Duration.ofMillis(it.end!! - it.start).toMinutes() }
    val napMinutes: Long get() = naps.sumOf { Duration.ofMillis(it.end!! - it.start).toMinutes() }
    val meditationMinutes: Long get() = meditations.sumOf { Duration.ofMillis(it.end!! - it.start).toMinutes() }
    val moodMean: Double? get() = moods.takeIf { it.isNotEmpty() }?.average()
    val energyMean: Double? get() = energies.takeIf { it.isNotEmpty() }?.average()
}

/** One summary per day that has anything logged, newest first. Naps and meditations still in progress are left out. */
fun daySummaries(entries: List<Entry>, zone: ZoneId): List<DaySummary> {
    fun dayOf(e: Entry): LocalDate =
        (if (e.type == Entry.Type.SLEEP) e.end!! else e.start).toLocal(zone).toLocalDate()

    val byDay = entries.filterNot { (it.type == Entry.Type.NAP || it.type == Entry.Type.MEDITATION) && it.end == null }.groupBy(::dayOf)
    return byDay.keys.sortedDescending().map { d ->
        val es = byDay.getValue(d).sortedBy { it.start }
        DaySummary(
            date = d,
            // Two nights logged for one morning means a correction; the later-logged one wins.
            night = es.lastOrNull { it.type == Entry.Type.SLEEP },
            naps = es.filter { it.type == Entry.Type.NAP },
            meditations = es.filter { it.type == Entry.Type.MEDITATION },
            moods = es.filter { it.type == Entry.Type.MOOD }.map { it.value!! },
            energies = es.filter { it.type == Entry.Type.ENERGY }.map { it.value!! },
            notes = es.filter { it.type == Entry.Type.NOTE }.map { it.text!! },
            emotions = es.filter { it.type == Entry.Type.EMOTIONS }.flatMap { it.emotions },
        )
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun csvCell(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

private fun Double?.fmt(): String = this?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: ""

/** A sleep diary, one row per day, oldest first: the shape sleep clinics ask for. */
fun sleepDiaryCsv(entries: List<Entry>, zone: ZoneId): String {
    val header = "date,bedtime,wake_time,sleep_hours,sleep_quality_1to5,naps,nap_minutes,meditations,meditation_minutes," +
        "mood_mean_1to5,mood_ratings,energy_mean_1to5,energy_ratings,emotions,notes"
    val rows = daySummaries(entries, zone).reversed().map { d ->
        listOf(
            d.date.toString(),
            d.night?.start?.toLocal(zone)?.format(HM) ?: "",
            d.night?.end?.toLocal(zone)?.format(HM) ?: "",
            d.sleepMinutes?.let { (it / 60.0) }.fmt(),
            d.night?.value?.toString() ?: "",
            d.naps.size.toString(),
            d.napMinutes.toString(),
            d.meditations.size.toString(),
            d.meditationMinutes.toString(),
            d.moodMean.fmt(), d.moods.size.toString(),
            d.energyMean.fmt(), d.energies.size.toString(),
            emotionCounts(d.emotions),
            d.notes.joinToString(" | "),
        ).joinToString(",") { csvCell(it) }
    }
    return (listOf(header) + rows).joinToString("\n", postfix = "\n")
}

/** Every entry, oldest first, in local time. */
fun allEntriesCsv(entries: List<Entry>, zone: ZoneId): String {
    val rows = entries.sortedBy { it.start }.map { e ->
        listOf(
            e.type.name.lowercase(),
            e.start.toLocal(zone).format(ISO),
            e.end?.toLocal(zone)?.format(ISO) ?: "",
            e.value?.toString() ?: "",
            e.text ?: "",
        ).joinToString(",") { csvCell(it) }
    }
    return (listOf("type,start,end,value,text") + rows).joinToString("\n", postfix = "\n")
}

/** "irritable x2; burnt out", most frequent first, ties in the order first felt. */
fun emotionCounts(emotions: List<String>): String =
    emotions.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        .joinToString("; ") { if (it.value > 1) "${it.key} x${it.value}" else it.key }

fun formatMinutes(m: Long): String = "%dh%02d".format(m / 60, m % 60)

/** The family an emotion belongs to, or null for a name no longer in the list (old entries keep their names). */
fun familyOf(emotion: String): EmotionFamily? = EMOTION_FAMILIES.firstOrNull { emotion in it.emotions }

/** Every day from [from] to [to] inclusive, oldest first. */
fun dayRange(from: LocalDate, to: LocalDate): List<LocalDate> =
    generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()

/** How often each emotion was logged over [days], most frequent first. */
fun emotionTotals(byDate: Map<LocalDate, DaySummary>, days: List<LocalDate>): List<Pair<String, Int>> =
    days.flatMap { byDate[it]?.emotions.orEmpty() }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key to it.value }

/** A piece of a night, nap or meditation inside one row of the sleep chart, as fractions 0..1 of the row. */
data class Segment(val type: Entry.Type, val from: Float, val to: Float)

/**
 * One row per day for a sleep-diary chart. The row for day D runs from 18:00 on D-1 to 18:00 on D, so a
 * night and the next day's naps sit on one row. Anything crossing a row edge is split between the rows.
 */
fun actogramRows(entries: List<Entry>, zone: ZoneId, days: List<LocalDate>): List<List<Segment>> {
    val timed = entries.filter {
        (it.type == Entry.Type.SLEEP || it.type == Entry.Type.NAP || it.type == Entry.Type.MEDITATION) && it.end != null
    }
    val six = LocalTime.of(18, 0)
    return days.map { d ->
        val ws = LocalDateTime.of(d.minusDays(1), six).atZone(zone).toInstant().toEpochMilli()
        val we = LocalDateTime.of(d, six).atZone(zone).toInstant().toEpochMilli()
        val len = (we - ws).toFloat()
        timed.mapNotNull { e ->
            val s = maxOf(e.start, ws)
            val t = minOf(e.end!!, we)
            if (t > s) Segment(e.type, (s - ws) / len, (t - ws) / len) else null
        }
    }
}

/** "today 14:02", "yesterday 21:30", or "Mon 16 Nov 21:30". */
fun whenText(ms: Long, zone: ZoneId, today: LocalDate): String {
    val t = ms.toLocal(zone)
    val hm = t.format(HM)
    return when (t.toLocalDate()) {
        today -> "today $hm"
        today.minusDays(1) -> "yesterday $hm"
        else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm"))
    }
}
