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
 * - NAP: [start]..[end]; [end] is null while the nap is in progress. value is null.
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
    enum class Type { MOOD, ENERGY, SLEEP, NAP, NOTE, EMOTIONS }

    init {
        when (type) {
            Type.MOOD, Type.ENERGY -> require(value in 1..5 && end == null) { "bad $type entry: $this" }
            Type.SLEEP -> require(value in 1..5 && end != null && end > start) { "bad SLEEP entry: $this" }
            Type.NAP -> require(value == null && (end == null || end > start)) { "bad NAP entry: $this" }
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
    EmotionFamily("Joy", 0xFFE8C547, listOf("happy", "content", "joyful", "cheerful", "delighted", "elated", "blissful", "giddy", "light-hearted", "grateful", "hopeful", "optimistic", "proud", "triumphant", "fulfilled", "satisfied", "amused", "playful")),
    EmotionFamily("Peace", 0xFF94C47D, listOf("calm", "peaceful", "serene", "relaxed", "at ease", "grounded", "centered", "settled", "safe", "relieved", "restored", "unhurried")),
    EmotionFamily("Connection", 0xFFE88AA8, listOf("loving", "affectionate", "tender", "warm", "close", "intimate", "connected", "belonging", "accepted", "understood", "seen", "supported", "cared for", "appreciated", "trusting", "admiring", "compassionate", "protective", "nostalgic")),
    EmotionFamily("Interest", 0xFF6CC3B5, listOf("curious", "intrigued", "fascinated", "absorbed", "in flow", "focused", "engaged", "eager", "excited", "inspired", "creative", "determined", "amazed", "in awe")),
    EmotionFamily("Surprise", 0xFFB9D46A, listOf("surprised", "startled", "shocked", "confused", "bewildered", "disoriented", "perplexed", "torn", "ambivalent", "unsettled")),
    EmotionFamily("Sadness", 0xFF6F95D8, listOf("sad", "sorrowful", "melancholy", "wistful", "homesick", "lonely", "isolated", "left out", "abandoned", "disappointed", "discouraged", "dejected", "defeated", "despondent", "despairing", "hopeless", "grieving", "heartbroken", "empty", "forlorn")),
    EmotionFamily("Fear", 0xFF9E86D8, listOf("worried", "nervous", "uneasy", "apprehensive", "tense", "on edge", "anxious", "rattled", "insecure", "vulnerable", "threatened", "scared", "panicky", "frantic", "dread", "overwhelmed", "helpless", "out of control", "paranoid")),
    EmotionFamily("Anger", 0xFFE0725A, listOf("annoyed", "irritable", "cranky", "impatient", "short-tempered", "frustrated", "exasperated", "aggravated", "resentful", "bitter", "envious", "jealous", "betrayed", "indignant", "defensive", "hostile", "spiteful", "angry", "furious")),
    EmotionFamily("Disgust", 0xFFA9A65A, listOf("disgusted", "repulsed", "revolted", "sickened", "appalled", "contemptuous", "judgmental", "averse")),
    EmotionFamily("Shame", 0xFFC98A5B, listOf("embarrassed", "foolish", "exposed", "self-conscious", "small", "inadequate", "inferior", "like a fraud", "self-critical", "guilty", "remorseful", "regretful", "ashamed", "humiliated", "like a burden", "unworthy", "undeserving", "worthless", "self-loathing")),
    EmotionFamily("Withdrawal", 0xFF8E96A3, listOf("bored", "listless", "flat", "unmotivated", "stuck", "foggy", "wanting to hide", "avoidant", "withdrawn", "disengaged", "checked out", "detached", "indifferent", "apathetic", "numb", "dissociated", "depleted", "burnt out", "jaded", "cynical", "going through the motions")),
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

/** A nap on [date] from [from] to [to]; if [to] is earlier than [from], it ran past midnight into [date]+1. */
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
    val moods: List<Int>,
    val energies: List<Int>,
    val notes: List<String>,
    val emotions: List<String>,
) {
    val sleepMinutes: Long? get() = night?.let { Duration.ofMillis(it.end!! - it.start).toMinutes() }
    val napMinutes: Long get() = naps.sumOf { Duration.ofMillis(it.end!! - it.start).toMinutes() }
    val moodMean: Double? get() = moods.takeIf { it.isNotEmpty() }?.average()
    val energyMean: Double? get() = energies.takeIf { it.isNotEmpty() }?.average()
}

/** One summary per day that has anything logged, newest first. Naps still in progress are left out. */
fun daySummaries(entries: List<Entry>, zone: ZoneId): List<DaySummary> {
    fun dayOf(e: Entry): LocalDate =
        (if (e.type == Entry.Type.SLEEP) e.end!! else e.start).toLocal(zone).toLocalDate()

    val byDay = entries.filterNot { it.type == Entry.Type.NAP && it.end == null }.groupBy(::dayOf)
    return byDay.keys.sortedDescending().map { d ->
        val es = byDay.getValue(d).sortedBy { it.start }
        DaySummary(
            date = d,
            // Two nights logged for one morning means a correction; the later-logged one wins.
            night = es.lastOrNull { it.type == Entry.Type.SLEEP },
            naps = es.filter { it.type == Entry.Type.NAP },
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
    val header = "date,bedtime,wake_time,sleep_hours,sleep_quality_1to5,naps,nap_minutes," +
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
