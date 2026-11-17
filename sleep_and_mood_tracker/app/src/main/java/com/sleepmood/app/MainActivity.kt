package com.sleepmood.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

private val MOOD_WORDS = listOf("awful", "low", "okay", "good", "great")
private val ENERGY_WORDS = listOf("dysfunctional", "exhausted", "tired", "okay", "energetic")
private val QUALITY_WORDS = listOf("terrible", "poor", "okay", "good", "great")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(File(filesDir, "entries.jsonl"))
        setContent { SleepMoodTheme { App(store, ::export) } }
    }

    /** Writes the two CSVs to the cache and opens the share sheet (email, Drive, Files...). */
    private fun export(entries: List<Entry>) {
        val zone = ZoneId.systemDefault()
        val dir = File(cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val stamp = LocalDate.now().toString()
        val files = listOf(
            File(dir, "sleep_diary_$stamp.csv").apply { writeText(sleepDiaryCsv(entries, zone)) },
            File(dir, "all_entries_$stamp.csv").apply { writeText(allEntriesCsv(entries, zone)) },
        )
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(this, "$packageName.export", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "Sleep and mood log, to $stamp")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Export"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(store: Store, onExport: (List<Entry>) -> Unit) {
    var entries by remember { mutableStateOf(store.load()) }
    var tab by remember { mutableIntStateOf(0) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()

    // The app may sit open past midnight; keep "today" current.
    LaunchedEffect(Unit) { while (true) { delay(30_000); today = LocalDate.now() } }

    fun delete(e: Entry) { entries = entries.filterNot { it.id == e.id }; store.replaceAll(entries) }
    fun replace(e: Entry) { entries = entries.map { if (it.id == e.id) e else it }; store.replaceAll(entries) }
    fun add(e: Entry, message: String) {
        store.add(e); entries = entries + e
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(message, actionLabel = "Undo", withDismissAction = true) ==
                SnackbarResult.ActionPerformed
            ) delete(e)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sleep & Mood") },
                actions = { TextButton(onClick = { onExport(entries) }, enabled = entries.isNotEmpty()) { Text("Export") } },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = {}, label = { Text("Today") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = {}, label = { Text("History") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (tab == 0) TodayScreen(pad, today, entries, zone, ::add, ::replace)
        else HistoryScreen(pad, entries, zone, ::delete)
    }
}

// ---------------------------------------------------------------- Today

@Composable
private fun TodayScreen(
    pad: PaddingValues,
    today: LocalDate,
    entries: List<Entry>,
    zone: ZoneId,
    add: (Entry, String) -> Unit,
    replace: (Entry) -> Unit,
) {
    val todays = entries.filter { it.start.toLocal(zone).toLocalDate() == today }
    val night = daySummaries(entries, zone).firstOrNull { it.date == today }?.night
    val ongoingNap = entries.firstOrNull { it.type == Entry.Type.NAP && it.end == null }
    var nightDialog by remember { mutableStateOf(false) }
    var napDialog by remember { mutableStateOf(false) }
    var emotionDialog by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    Column(
        Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(today.format(DAY), style = MaterialTheme.typography.titleMedium)

        RatingCard("Mood", MOOD_WORDS, todays.filter { it.type == Entry.Type.MOOD }, zone) { v ->
            add(Entry.new(Entry.Type.MOOD, System.currentTimeMillis(), value = v), "Mood ${MOOD_WORDS[v - 1]} logged")
        }
        RatingCard("Energy", ENERGY_WORDS, todays.filter { it.type == Entry.Type.ENERGY }, zone) { v ->
            add(Entry.new(Entry.Type.ENERGY, System.currentTimeMillis(), value = v), "Energy ${ENERGY_WORDS[v - 1]} logged")
        }

        Section("Strong emotions") {
            todays.filter { it.type == Entry.Type.EMOTIONS }.sortedBy { it.start }.forEach {
                Text("${it.start.toLocal(zone).format(HM)}  ${it.text}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { emotionDialog = true }) { Text("Log emotions") }
        }

        Section("Last night") {
            if (night != null) {
                val mins = (night.end!! - night.start) / 60_000
                Text(
                    "${night.start.toLocal(zone).format(HM)} to ${night.end.toLocal(zone).format(HM)}, " +
                        "${formatMinutes(mins)}, ${QUALITY_WORDS[night.value!! - 1]}",
                )
                OutlinedButton(onClick = { nightDialog = true }) { Text("Edit") }
            } else {
                Button(onClick = { nightDialog = true }) { Text("Log last night") }
            }
        }

        Section("Naps") {
            todays.filter { it.type == Entry.Type.NAP && it.end != null }.sortedBy { it.start }.forEach {
                Text("${it.start.toLocal(zone).format(HM)} to ${it.end!!.toLocal(zone).format(HM)} (${(it.end - it.start) / 60_000} min)")
            }
            if (ongoingNap != null) {
                Text("Napping since ${ongoingNap.start.toLocal(zone).format(HM)}", fontWeight = FontWeight.Bold)
                Button(onClick = {
                    replace(ongoingNap.copy(end = maxOf(System.currentTimeMillis(), ongoingNap.start + 60_000)))
                }) { Text("End nap") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { add(Entry.new(Entry.Type.NAP, System.currentTimeMillis()), "Nap started") }) {
                        Text("Start nap now")
                    }
                    OutlinedButton(onClick = { napDialog = true }) { Text("Add a past nap") }
                }
            }
        }

        Section("Note") {
            todays.filter { it.type == Entry.Type.NOTE }.forEach {
                Text("${it.start.toLocal(zone).format(HM)}  ${it.text}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), placeholder = { Text("Anything worth remembering") })
            Button(onClick = {
                add(Entry.new(Entry.Type.NOTE, System.currentTimeMillis(), text = note.trim()), "Note saved")
                note = ""
            }, enabled = note.isNotBlank()) { Text("Save note") }
        }
    }

    if (nightDialog) {
        val last = night ?: entries.filter { it.type == Entry.Type.SLEEP }.maxByOrNull { it.end!! }
        NightDialog(
            bed = last?.start?.toLocal(zone)?.toLocalTime() ?: LocalTime.of(23, 0),
            wake = last?.end?.toLocal(zone)?.toLocalTime() ?: LocalTime.of(7, 0),
            quality = night?.value,
            onDismiss = { nightDialog = false },
        ) { bed, wake, q ->
            val (s, e) = nightInterval(LocalDate.now(), bed, wake, zone)
            if (night != null) replace(night.copy(start = s, end = e, value = q))
            else add(Entry.new(Entry.Type.SLEEP, s, end = e, value = q), "Night logged")
            nightDialog = false
        }
    }
    if (emotionDialog) {
        EmotionDialog(onDismiss = { emotionDialog = false }) { picked ->
            add(Entry.new(Entry.Type.EMOTIONS, System.currentTimeMillis(), text = picked.joinToString(", ")), "Emotions logged")
            emotionDialog = false
        }
    }
    if (napDialog) {
        NapDialog(onDismiss = { napDialog = false }) { from, to ->
            val (s, e) = napInterval(LocalDate.now(), from, to, zone)
            add(Entry.new(Entry.Type.NAP, s, end = e), "Nap logged")
            napDialog = false
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun RatingRow(words: List<String>, selected: Int?, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..5).forEach { v ->
            val label = @Composable { Text("$v", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            val mod = Modifier.weight(1f).height(52.dp)
            if (v == selected) Button({ onPick(v) }, mod, contentPadding = PaddingValues(0.dp)) { label() }
            else FilledTonalButton({ onPick(v) }, mod, contentPadding = PaddingValues(0.dp)) { label() }
        }
    }
    Text(
        words.mapIndexed { i, w -> "${i + 1} $w" }.joinToString("  ·  "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RatingCard(title: String, words: List<String>, todays: List<Entry>, zone: ZoneId, onPick: (Int) -> Unit) {
    Section(title) {
        RatingRow(words, null, onPick)
        if (todays.isNotEmpty()) {
            Text(
                "Today: " + todays.sortedBy { it.start }.joinToString(", ") {
                    "${words[it.value!! - 1]} at ${it.start.toLocal(zone).format(HM)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NightDialog(
    bed: LocalTime,
    wake: LocalTime,
    quality: Int?,
    onDismiss: () -> Unit,
    onSave: (LocalTime, LocalTime, Int) -> Unit,
) {
    val bedState = rememberTimePickerState(bed.hour, bed.minute, is24Hour = true)
    val wakeState = rememberTimePickerState(wake.hour, wake.minute, is24Hour = true)
    var q by remember { mutableStateOf(quality) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Last night") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Went to sleep")
                TimeInput(bedState)
                Text("Woke up (today)")
                TimeInput(wakeState)
                Text("How well did you sleep?")
                RatingRow(QUALITY_WORDS, q) { q = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(LocalTime.of(bedState.hour, bedState.minute), LocalTime.of(wakeState.hour, wakeState.minute), q!!) },
                enabled = q != null && (bedState.hour != wakeState.hour || bedState.minute != wakeState.minute),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NapDialog(onDismiss: () -> Unit, onSave: (LocalTime, LocalTime) -> Unit) {
    val now = LocalTime.now()
    val fromState = rememberTimePickerState(now.minusMinutes(30).hour, now.minusMinutes(30).minute, is24Hour = true)
    val toState = rememberTimePickerState(now.hour, now.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("A nap today") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fell asleep")
                TimeInput(fromState)
                Text("Woke up")
                TimeInput(toState)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(LocalTime.of(fromState.hour, fromState.minute), LocalTime.of(toState.hour, toState.minute)) },
                enabled = fromState.hour != toState.hour || fromState.minute != toState.minute,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The wheel's families as coloured groups of chips; pick any number, across families. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmotionDialog(onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var picked by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Any strong emotions?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EMOTION_FAMILIES.forEach { fam ->
                    val c = Color(fam.color)
                    Text(fam.name, style = MaterialTheme.typography.labelMedium, color = c)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        fam.emotions.forEach { e ->
                            FilterChip(
                                selected = e in picked,
                                onClick = { picked = if (e in picked) picked - e else picked + e },
                                label = { Text(e) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = c.copy(alpha = 0.35f),
                                    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = e in picked,
                                    borderColor = c.copy(alpha = 0.6f), selectedBorderColor = c,
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Keep the wheel's order in what is saved, whatever order they were tapped in.
            TextButton(onClick = { onSave(EMOTION_FAMILIES.flatMap { it.emotions }.filter { it in picked }) }, enabled = picked.isNotEmpty()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- History

@Composable
private fun HistoryScreen(pad: PaddingValues, entries: List<Entry>, zone: ZoneId, delete: (Entry) -> Unit) {
    val days = daySummaries(entries, zone)
    var expanded by remember { mutableStateOf<LocalDate?>(null) }
    var confirm by remember { mutableStateOf<Entry?>(null) }

    if (days.isEmpty()) {
        Text("Nothing logged yet.", Modifier.padding(pad).padding(16.dp))
        return
    }
    LazyColumn(
        Modifier.padding(pad).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(days, key = { it.date.toString() }) { d ->
            Card(Modifier.fillMaxWidth().clickable { expanded = if (expanded == d.date) null else d.date }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(d.date.format(DAY), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    d.night?.let {
                        Text(
                            "Night: ${it.start.toLocal(zone).format(HM)} to ${it.end!!.toLocal(zone).format(HM)}, " +
                                "${formatMinutes(d.sleepMinutes!!)}, ${QUALITY_WORDS[it.value!! - 1]}",
                        )
                    }
                    if (d.naps.isNotEmpty()) Text("Naps: ${d.naps.size}, ${d.napMinutes} min")
                    val ratings = listOfNotNull(
                        d.moodMean?.let { "Mood %.1f (%d)".format(it, d.moods.size) },
                        d.energyMean?.let { "Energy %.1f (%d)".format(it, d.energies.size) },
                    )
                    if (ratings.isNotEmpty()) Text(ratings.joinToString("   "))
                    if (d.emotions.isNotEmpty()) Text("Emotions: ${emotionCounts(d.emotions)}")
                    d.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }

                    if (expanded == d.date) {
                        Spacer(Modifier.height(8.dp))
                        Text("Tap an entry to delete it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val raw = entries.filter {
                            (if (it.type == Entry.Type.SLEEP) it.end!! else it.start).toLocal(zone).toLocalDate() == d.date
                        }.sortedBy { it.start }
                        raw.forEach { e ->
                            Text(describe(e, zone), Modifier.fillMaxWidth().clickable { confirm = e }.padding(vertical = 6.dp))
                        }
                    }
                }
            }
        }
    }

    confirm?.let { e ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete this entry?") },
            text = { Text(describe(e, zone)) },
            confirmButton = { TextButton(onClick = { delete(e); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } },
        )
    }
}

private fun describe(e: Entry, zone: ZoneId): String {
    val t = e.start.toLocal(zone).format(HM)
    val end = e.end?.toLocal(zone)?.format(HM)
    return when (e.type) {
        Entry.Type.MOOD -> "$t  mood: ${MOOD_WORDS[e.value!! - 1]}"
        Entry.Type.ENERGY -> "$t  energy: ${ENERGY_WORDS[e.value!! - 1]}"
        Entry.Type.SLEEP -> "$t to $end  night, ${QUALITY_WORDS[e.value!! - 1]}"
        Entry.Type.NAP -> "$t to ${end ?: "now"}  nap"
        Entry.Type.NOTE -> "$t  note: ${e.text}"
        Entry.Type.EMOTIONS -> "$t  emotions: ${e.text}"
    }
}
