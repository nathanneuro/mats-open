package com.signalboost.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SignalBoostTheme { SignalBoostApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignalBoostApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Prefs.flow(context).collectAsState(initial = Prefs.DEFAULT)
    val alarmActive by AlarmService.active.collectAsState()

    var listenerEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var postNotifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> postNotifGranted = granted }

    val listenerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        listenerEnabled = isNotificationListenerEnabled(context)
    }

    var editing by remember { mutableStateOf<Trigger?>(null) }
    var permissionsExpanded by remember { mutableStateOf(false) }

    // Tick the wall clock every 30 s so paused-trigger countdowns and the
    // automatic resume transition stay live without user interaction. The
    // matching path itself uses System.currentTimeMillis() at notify time
    // — this state is only for the UI text refresh.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    // One-time promotion of the legacy global signalOnly switch into each
    // trigger's per-trigger app filter, then the legacy key is cleared.
    LaunchedEffect(Unit) {
        Prefs.migrateLegacySignalOnly(context)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Signal Boost") }) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    PermissionsCard(
                        expanded = permissionsExpanded,
                        onToggleExpanded = { permissionsExpanded = !permissionsExpanded },
                        listenerEnabled = listenerEnabled,
                        postNotifGranted = postNotifGranted,
                        onEnableListener = {
                            listenerLauncher.launch(
                                Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
                        },
                        onRequestPostNotif = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                }
                item {
                    if (alarmActive) {
                        ActiveAlarmCard(onStop = { AlarmService.stop(context) })
                    }
                }
                item {
                    Text(
                        "Triggers",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    if (settings.triggers.isEmpty()) {
                        Text(
                            "No triggers yet. Tap + below to add one.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(settings.triggers, key = { it.id }) { trigger ->
                    TriggerCard(
                        trigger = trigger,
                        nowMs = nowMs,
                        onEdit = { editing = trigger },
                        onDelete = {
                            scope.launch { Prefs.removeTrigger(context, trigger.id) }
                        },
                        onTest = { AlarmService.start(context, trigger) },
                        onPauseHours = { hours ->
                            scope.launch {
                                Prefs.setTriggerPaused(
                                    context,
                                    trigger.id,
                                    System.currentTimeMillis() + hours * 3_600_000L,
                                )
                            }
                        },
                        onResume = {
                            scope.launch {
                                Prefs.setTriggerPaused(context, trigger.id, 0L)
                            }
                        },
                    )
                }
                item {
                    // Inline add-trigger button — left-aligned, sits below the
                    // last trigger so adding one feels continuous with the list.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        OutlinedButton(onClick = { editing = Trigger.new() }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(
                                "Add trigger",
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { current ->
        TriggerEditor(
            initial = current,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch { Prefs.upsertTrigger(context, updated) }
                editing = null
            },
        )
    }

    LaunchedEffect(Unit) {
        listenerEnabled = isNotificationListenerEnabled(context)
    }
}

@Composable
private fun PermissionsCard(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    listenerEnabled: Boolean,
    postNotifGranted: Boolean,
    onEnableListener: () -> Unit,
    onRequestPostNotif: () -> Unit,
) {
    val needsPostNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val allGranted = listenerEnabled && (!needsPostNotif || postNotifGranted)
    Card {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // Compact status indicator visible while collapsed so the user
                // can see at a glance whether anything needs attention.
                Text(
                    text = if (allGranted) "✓" else "!",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (allGranted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PermissionRow(
                        label = "Notification access",
                        granted = listenerEnabled,
                        actionLabel = if (listenerEnabled) "Open settings" else "Grant",
                        onAction = onEnableListener,
                    )
                    PermissionRow(
                        label = "Post notifications",
                        granted = postNotifGranted,
                        actionLabel = "Grant",
                        onAction = onRequestPostNotif,
                        hidden = !needsPostNotif,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    hidden: Boolean = false,
) {
    if (hidden) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(
            text = if (granted) "✓" else "✗",
            style = MaterialTheme.typography.titleMedium,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 12.dp),
        )
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun ActiveAlarmCard(onStop: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Alarm is sounding",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onStop) { Text("Stop") }
        }
    }
}

@Composable
private fun TriggerCard(
    trigger: Trigger,
    nowMs: Long,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onPauseHours: (hours: Int) -> Unit,
    onResume: () -> Unit,
) {
    val isPaused = trigger.pausedUntil > nowMs
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = trigger.label.ifEmpty { "(no label)" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "\"${trigger.phrase}\"",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = onTest) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Test alarm")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
            Text(
                text = buildString {
                    append("Vibration: ${trigger.alarm.vibration.name.lowercase()} • ")
                    append("Ramp: ${trigger.alarm.escalationSeconds}s • ")
                    append("Max: ${trigger.alarm.maxVolumePercent}%")
                    if (trigger.alarm.forceMaxVolume) append(" (forced)")
                    append(" • ")
                    append(if (trigger.caseSensitive) "Case-sensitive" else "Case-insensitive")
                    append(" • ")
                    append(
                        if (trigger.apps.isEmpty()) "All apps"
                        else "${trigger.apps.size} app${if (trigger.apps.size == 1) "" else "s"}"
                    )
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (isPaused) {
                PausedRow(
                    pausedUntilMs = trigger.pausedUntil,
                    nowMs = nowMs,
                    onResume = onResume,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPauseHours(1) }) { Text("Pause 1h") }
                    OutlinedButton(onClick = { onPauseHours(2) }) { Text("Pause 2h") }
                }
            }
        }
    }
}

@Composable
private fun PausedRow(
    pausedUntilMs: Long,
    nowMs: Long,
    onResume: () -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val until = remember(pausedUntilMs) { timeFmt.format(java.util.Date(pausedUntilMs)) }
    val remainingMin = ((pausedUntilMs - nowMs) / 60_000L).coerceAtLeast(0L)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Paused until $until ($remainingMin min)",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onResume) { Text("Resume") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerEditor(
    initial: Trigger,
    onDismiss: () -> Unit,
    onSave: (Trigger) -> Unit,
) {
    var label by remember { mutableStateOf(initial.label) }
    var phrase by remember { mutableStateOf(initial.phrase) }
    var caseSensitive by remember { mutableStateOf(initial.caseSensitive) }
    var vibration by remember { mutableStateOf(initial.alarm.vibration) }
    var escalation by remember { mutableStateOf(initial.alarm.escalationSeconds.toFloat()) }
    var maxVolume by remember { mutableStateOf(initial.alarm.maxVolumePercent.toFloat()) }
    var ringtoneUri by remember { mutableStateOf(initial.alarm.ringtoneUri) }
    var forceMaxVolume by remember { mutableStateOf(initial.alarm.forceMaxVolume) }
    var silenceOnShake by remember { mutableStateOf(initial.alarm.silenceOnShake) }
    var silenceOnFlip by remember { mutableStateOf(initial.alarm.silenceOnFlip) }
    var silenceSeconds by remember { mutableStateOf(initial.alarm.silenceSeconds.toFloat()) }
    var apps by remember { mutableStateOf(initial.apps) }
    var pickingApps by remember { mutableStateOf(false) }
    var notificationColor by remember { mutableStateOf(initial.alarm.notificationColorArgb) }
    var silentLead by remember { mutableStateOf(initial.alarm.silentVibrationLeadSeconds.toFloat()) }

    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            ringtoneUri = uri?.toString()
        }
    }

    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.phrase.isEmpty()) "New trigger" else "Edit trigger") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(scrollState),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text("Trigger phrase") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Case-sensitive", modifier = Modifier.weight(1f))
                    Switch(checked = caseSensitive, onCheckedChange = { caseSensitive = it })
                }
                FilledTonalButton(onClick = { pickingApps = true }) {
                    Text(
                        if (apps.isEmpty()) "Apps: all apps"
                        else "Apps: ${apps.size} selected"
                    )
                }
                HorizontalDivider()
                Text("Notification colour", style = MaterialTheme.typography.titleSmall)
                ColorSwatchRow(selected = notificationColor, onChange = { notificationColor = it })
                HorizontalDivider()
                Text("Alarm", style = MaterialTheme.typography.titleSmall)
                VibrationDropdown(selected = vibration, onChange = { vibration = it })
                Column {
                    Text("Silent vibration lead: ${silentLead.toInt()}s")
                    Slider(
                        value = silentLead,
                        onValueChange = { silentLead = it },
                        valueRange = 0f..60f,
                    )
                    Text(
                        if (silentLead.toInt() == 0)
                            "Sound starts immediately."
                        else
                            "Vibrates silently for ${silentLead.toInt()}s before any sound.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column {
                    Text("Ramp-up: ${escalation.toInt()}s")
                    Slider(
                        value = escalation,
                        onValueChange = { escalation = it },
                        valueRange = 1f..120f,
                    )
                }
                Column {
                    Text("Max volume: ${maxVolume.toInt()}%")
                    Slider(
                        value = maxVolume,
                        onValueChange = { maxVolume = it },
                        valueRange = 0f..100f,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Force max alarm volume")
                        Text(
                            "Overrides the device alarm-stream volume while sounding, then restores it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = forceMaxVolume, onCheckedChange = { forceMaxVolume = it })
                }
                HorizontalDivider()
                Text("Silence gestures", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Shake to silence", modifier = Modifier.weight(1f))
                    Switch(checked = silenceOnShake, onCheckedChange = { silenceOnShake = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Flip face-down to silence", modifier = Modifier.weight(1f))
                    Switch(checked = silenceOnFlip, onCheckedChange = { silenceOnFlip = it })
                }
                Column {
                    Text("Silence duration: ${silenceSeconds.toInt()}s")
                    Slider(
                        value = silenceSeconds,
                        onValueChange = { silenceSeconds = it },
                        valueRange = 5f..300f,
                        enabled = silenceOnShake || silenceOnFlip,
                    )
                }
                FilledTonalButton(
                    onClick = {
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Pick alarm sound")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            ringtoneUri?.let {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
                            }
                        }
                        ringtoneLauncher.launch(intent)
                    },
                ) {
                    Text(if (ringtoneUri == null) "Pick alarm sound (default)" else "Change alarm sound")
                }
            }
        },
        confirmButton = {
            Button(
                enabled = phrase.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            label = label.trim(),
                            phrase = phrase.trim(),
                            caseSensitive = caseSensitive,
                            apps = apps,
                            alarm = AlarmProfile(
                                ringtoneUri = ringtoneUri,
                                vibration = vibration,
                                escalationSeconds = escalation.toInt().coerceIn(1, 600),
                                maxVolumePercent = maxVolume.toInt().coerceIn(0, 100),
                                forceMaxVolume = forceMaxVolume,
                                silenceOnShake = silenceOnShake,
                                silenceOnFlip = silenceOnFlip,
                                silenceSeconds = silenceSeconds.toInt().coerceIn(5, 600),
                                notificationColorArgb = notificationColor,
                                silentVibrationLeadSeconds = silentLead.toInt()
                                    .coerceIn(0, 300),
                            ),
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingApps) {
        AppPickerDialog(
            initial = apps,
            onDismiss = { pickingApps = false },
            onSave = {
                apps = it
                pickingApps = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VibrationDropdown(
    selected: VibrationStyle,
    onChange: (VibrationStyle) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.name.lowercase().replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            label = { Text("Vibration") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VibrationStyle.entries.forEach { style ->
                DropdownMenuItem(
                    text = { Text(style.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        onChange(style)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Multi-select picker over installed launcher apps. Empty selection = all
 *  apps (the user-facing default). The list is loaded once when the dialog
 *  opens — there's no live update for installs that happen during the
 *  picker session, which is fine for this use case. */
@Composable
private fun AppPickerDialog(
    initial: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .filter {
                // Keep apps that are user-installed OR have a launcher icon —
                // skips bare framework packages that never post user-facing
                // notifications.
                (it.flags and ApplicationInfo.FLAG_SYSTEM == 0) ||
                    pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .map { info -> info.packageName to pm.getApplicationLabel(info).toString() }
            .sortedBy { it.second.lowercase(Locale.getDefault()) }
    }
    var selected by remember { mutableStateOf(initial) }
    var search by remember { mutableStateOf("") }
    val filtered = remember(search, apps) {
        if (search.isBlank()) apps
        else apps.filter { (pkg, label) ->
            label.contains(search, ignoreCase = true) || pkg.contains(search, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apps to scan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (selected.isEmpty()) "All apps will be scanned"
                    else "${selected.size} app${if (selected.size == 1) "" else "s"} selected",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Box(modifier = Modifier.heightIn(max = 360.dp)) {
                    LazyColumn {
                        items(filtered, key = { it.first }) { (pkg, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected =
                                            if (pkg in selected) selected - pkg
                                            else selected + pkg
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = pkg in selected,
                                    onCheckedChange = null,
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        pkg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { selected = emptySet() }) {
                        Text("Clear (scan all apps)")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(selected) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Per-trigger notification accent palette. The first entry (0 = system
 *  default tint) lets the user opt out — picking it is the same as not
 *  choosing a colour. Swatches are wide-ish so different triggers stay
 *  visually distinguishable in the shade. */
private val PRESET_COLORS_ARGB: List<Int> = listOf(
    0,                        // 0 = system default (no override)
    0xFFE53935.toInt(),       // red
    0xFFFB8C00.toInt(),       // orange
    0xFFFDD835.toInt(),       // yellow
    0xFF43A047.toInt(),       // green
    0xFF00ACC1.toInt(),       // cyan
    0xFF1E88E5.toInt(),       // blue
    0xFF8E24AA.toInt(),       // purple
    0xFFD81B60.toInt(),       // pink
    0xFF8D6E63.toInt(),       // brown
)

@Composable
private fun ColorSwatchRow(selected: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PRESET_COLORS_ARGB.forEach { argb ->
            val isSelected = argb == selected
            val borderColor =
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant
            val borderWidth = if (isSelected) 3.dp else 1.dp
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .border(borderWidth, borderColor, CircleShape)
                    .background(
                        // 0 = "default": render as a subtle ring with a
                        // diagonal slash conveyed via secondary surface
                        // colour so the user can tell it apart from a
                        // missing swatch.
                        if (argb == 0) MaterialTheme.colorScheme.surfaceVariant
                        else Color(argb)
                    )
                    .clickable { onChange(argb) },
                contentAlignment = Alignment.Center,
            ) {
                if (argb == 0) {
                    Text(
                        "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val cn = ComponentName(context, NotificationProbeService::class.java)
    val flat = AndroidSettings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners",
    ) ?: return false
    return flat.split(":").any { it.equals(cn.flattenToString(), ignoreCase = true) }
}
