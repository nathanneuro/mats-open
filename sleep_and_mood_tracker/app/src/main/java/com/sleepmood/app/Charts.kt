package com.sleepmood.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val NIGHT_COLOR = Color(0xFF7C9CFF)
val NAP_COLOR = Color(0xFFC792EA) // purple: must not be on the red-to-green sleep-quality scale
val MEDITATION_COLOR = Color(0xFF94C47D)
val MOOD_COLOR = Color(0xFFE88AA8)
val ENERGY_COLOR = Color(0xFF6CC3B5)
private val GRID = Color(0x33FFFFFF)
private val LABEL = Color(0xFFA4AAB8)
/** Sleep quality 1 (terrible) to 5 (great), red to green. */
private val QUALITY_COLORS = listOf(0xFFE0725A, 0xFFE8A05A, 0xFFE8C547, 0xFFA8C46A, 0xFF6FBF7A).map { Color(it) }
private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM")

private const val AXIS_W = 28f // dp, room for y labels

private fun DrawScope.label(tm: TextMeasurer, text: String, x: Float, y: Float, alignRight: Boolean = false, center: Boolean = false) {
    val layout = tm.measure(text, TextStyle(color = LABEL, fontSize = 10.sp))
    val dx = when {
        alignRight -> -layout.size.width.toFloat()
        center -> -layout.size.width / 2f
        else -> 0f
    }
    drawText(layout, topLeft = Offset(x + dx, y - layout.size.height / 2f))
}

/** Dates under a chart: first, middle and last. */
private fun DrawScope.dateAxis(tm: TextMeasurer, days: List<LocalDate>, left: Float, width: Float, y: Float) {
    val n = days.size
    listOf(0, n / 2, n - 1).distinct().forEach { i ->
        val x = left + width * (i + 0.5f) / n
        label(tm, days[i].format(SHORT_DATE), x, y, center = true)
    }
}

@Composable
fun Legend(vararg items: Pair<String, Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (name, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(c, RoundedCornerShape(2.dp)))
                Text(" $name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Hours slept each night, coloured by how well, with naps stacked on top. A faint band marks 7 to 9 hours. */
@Composable
fun SleepHoursChart(days: List<LocalDate>, byDate: Map<LocalDate, DaySummary>) {
    val tm = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val left = AXIS_W.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val w = size.width - left
        val maxH = maxOf(10f, days.maxOf { d -> byDate[d]?.let { ((it.sleepMinutes ?: 0) + it.napMinutes) / 60f } ?: 0f } + 1f)
        fun y(h: Float) = bottom - bottom * h / maxH
        drawRect(Color(0x14FFFFFF), Offset(left, y(9f)), Size(w, y(7f) - y(9f)))
        for (h in 0..maxH.toInt() step 2) {
            drawLine(GRID, Offset(left, y(h.toFloat())), Offset(size.width, y(h.toFloat())))
            label(tm, "${h}h", left - 4.dp.toPx(), y(h.toFloat()), alignRight = true)
        }
        val slot = w / days.size
        val bw = slot * 0.7f
        days.forEachIndexed { i, d ->
            val s = byDate[d] ?: return@forEachIndexed
            val x = left + slot * i + (slot - bw) / 2
            val night = (s.sleepMinutes ?: 0) / 60f
            if (s.night != null) drawRect(QUALITY_COLORS[s.night.value!! - 1], Offset(x, y(night)), Size(bw, bottom - y(night)))
            if (s.napMinutes > 0) {
                val top = night + s.napMinutes / 60f
                drawRect(NAP_COLOR.copy(alpha = 0.7f), Offset(x, y(top)), Size(bw, y(night) - y(top)))
            }
        }
        dateAxis(tm, days, left, w, size.height - 6.dp.toPx())
    }
}

/**
 * The sleep diary as a picture: one row per day from 18:00 the evening before to 18:00 that day,
 * with the night, naps and meditation drawn where they happened.
 */
@Composable
fun SleepTimingChart(days: List<LocalDate>, entries: List<Entry>, zone: ZoneId) {
    val tm = rememberTextMeasurer()
    val rows = actogramRows(entries, zone, days)
    val rowDp = when {
        days.size <= 14 -> 14
        days.size <= 31 -> 8
        else -> 4
    }
    Canvas(Modifier.fillMaxWidth().height((rowDp * days.size + 30).dp)) {
        val left = 40.dp.toPx()
        val top = 16.dp.toPx()
        val w = size.width - left
        val rh = rowDp.dp.toPx()
        listOf("18", "00", "06", "12", "18").forEachIndexed { i, t ->
            val x = left + w * i / 4f
            drawLine(GRID, Offset(x, top), Offset(x, top + rh * days.size))
            label(tm, t, x, top - 8.dp.toPx(), center = true)
        }
        rows.forEachIndexed { r, segs ->
            val y = top + rh * r
            if (days.size <= 31 || r % 7 == 0 || r == days.size - 1) {
                label(tm, days[r].format(SHORT_DATE), left - 4.dp.toPx(), y + rh / 2, alignRight = true)
            }
            segs.forEach { s ->
                val c = when (s.type) {
                    Entry.Type.SLEEP -> NIGHT_COLOR
                    Entry.Type.NAP -> NAP_COLOR
                    else -> MEDITATION_COLOR
                }
                drawRect(c, Offset(left + w * s.from, y + rh * 0.15f), Size(maxOf(w * (s.to - s.from), 1.5f), rh * 0.7f))
            }
        }
    }
}

/** Daily average mood and energy, 1 to 5. Days without ratings leave a gap in the line. */
@Composable
fun MoodEnergyChart(days: List<LocalDate>, byDate: Map<LocalDate, DaySummary>) {
    val tm = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val left = AXIS_W.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val w = size.width - left
        fun y(v: Double) = (bottom - (bottom - top) * (v - 1) / 4).toFloat()
        for (v in 1..5) {
            drawLine(GRID, Offset(left, y(v.toDouble())), Offset(size.width, y(v.toDouble())))
            label(tm, "$v", left - 4.dp.toPx(), y(v.toDouble()), alignRight = true)
        }
        val slot = w / days.size
        fun series(value: (DaySummary) -> Double?, c: Color) {
            var prev: Offset? = null
            days.forEachIndexed { i, d ->
                val v = byDate[d]?.let(value)
                if (v == null) { prev = null; return@forEachIndexed }
                val p = Offset(left + slot * (i + 0.5f), y(v))
                prev?.let { drawLine(c, it, p, strokeWidth = 2.dp.toPx()) }
                drawCircle(c, if (days.size > 31) 2.dp.toPx() else 3.dp.toPx(), p)
                prev = p
            }
        }
        series({ it.moodMean }, MOOD_COLOR)
        series({ it.energyMean }, ENERGY_COLOR)
        dateAxis(tm, days, left, w, size.height - 6.dp.toPx())
    }
}

/** Minutes of napping and of meditation each day, side by side. */
@Composable
fun NapMeditationChart(days: List<LocalDate>, byDate: Map<LocalDate, DaySummary>) {
    val tm = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val left = AXIS_W.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val w = size.width - left
        val maxM = maxOf(30L, days.maxOf { d -> byDate[d]?.let { maxOf(it.napMinutes, it.meditationMinutes) } ?: 0L })
        val step = when {
            maxM <= 60 -> 15L
            maxM <= 180 -> 30L
            else -> 60L
        }
        val ceil = ((maxM + step - 1) / step) * step
        fun y(m: Long) = bottom - (bottom - top) * m / ceil.toFloat()
        for (m in 0..ceil step step) {
            drawLine(GRID, Offset(left, y(m)), Offset(size.width, y(m)),
                pathEffect = if (m == 0L) null else PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
            label(tm, "$m", left - 4.dp.toPx(), y(m), alignRight = true)
        }
        val slot = w / days.size
        val bw = slot * 0.35f
        days.forEachIndexed { i, d ->
            val s = byDate[d] ?: return@forEachIndexed
            val x = left + slot * i + slot * 0.15f
            if (s.napMinutes > 0) drawRect(NAP_COLOR, Offset(x, y(s.napMinutes)), Size(bw, bottom - y(s.napMinutes)))
            if (s.meditationMinutes > 0) drawRect(MEDITATION_COLOR, Offset(x + bw, y(s.meditationMinutes)), Size(bw, bottom - y(s.meditationMinutes)))
        }
        dateAxis(tm, days, left, w, size.height - 6.dp.toPx())
    }
}

/** Which families of emotion came up, as one proportional bar, then the most frequent emotions. */
@Composable
fun EmotionChart(totals: List<Pair<String, Int>>) {
    if (totals.isEmpty()) {
        Text("No emotions logged in this period.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val other = EmotionFamily("Other", 0xFF555B6B, emptyList())
    val byFamily = totals.groupBy { familyOf(it.first) ?: other }.mapValues { (_, v) -> v.sumOf { it.second } }
        .entries.sortedByDescending { it.value }
    val all = byFamily.sumOf { it.value }.toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(16.dp)) {
            byFamily.forEach { (f, n) -> Box(Modifier.weight(n / all).height(16.dp).background(Color(f.color))) }
        }
        byFamily.chunked(3).forEach { row ->
            Legend(*row.map { (f, n) -> "${f.name} $n" to Color(f.color) }.toTypedArray())
        }
        val top = totals.take(12)
        val most = top.first().second.toFloat()
        top.forEach { (name, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Box(Modifier.weight(1f)) {
                    Box(
                        Modifier.fillMaxWidth(n / most).height(12.dp)
                            .background(Color((familyOf(name) ?: other).color), RoundedCornerShape(2.dp)),
                    )
                }
                Text(" $n", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
