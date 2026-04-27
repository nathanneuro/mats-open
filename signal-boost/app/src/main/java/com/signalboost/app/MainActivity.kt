package com.signalboost.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SignalBoostApp() }
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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Signal Boost") })
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { editing = Trigger.new() }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add trigger")
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    PermissionsCard(
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
                    SignalOnlyCard(
                        signalOnly = settings.signalOnly,
                        onChange = { scope.launch { Prefs.setSignalOnly(context, it) } },
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
                            "No triggers yet. Tap + to add one.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(settings.triggers, key = { it.id }) { trigger ->
                    TriggerCard(
                        trigger = trigger,
                        onEdit = { editing = trigger },
                        onDelete = {
                            scope.launch { Prefs.removeTrigger(context, trigger.id) }
                        },
                        onTest = { AlarmService.start(context, trigger) },
                    )
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
    listenerEnabled: Boolean,
    postNotifGranted: Boolean,
    onEnableListener: () -> Unit,
    onRequestPostNotif: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
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
                hidden = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
            )
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
private fun SignalOnlyCard(signalOnly: Boolean, onChange: (Boolean) -> Unit) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Signal only", style = MaterialTheme.typography.titleMedium)
                Text(
                    "When off, every app's notifications are scanned.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = signalOnly, onCheckedChange = onChange)
        }
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerEditor(
    initial: Trigger,
    onDismiss: () -> Unit,
    onSave: (Trigger) -> Unit,
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf(initial.label) }
    var phrase by remember { mutableStateOf(initial.phrase) }
    var caseSensitive by remember { mutableStateOf(initial.caseSensitive) }
    var vibration by remember { mutableStateOf(initial.alarm.vibration) }
    var escalation by remember { mutableStateOf(initial.alarm.escalationSeconds.toFloat()) }
    var maxVolume by remember { mutableStateOf(initial.alarm.maxVolumePercent.toFloat()) }
    var ringtoneUri by remember { mutableStateOf(initial.alarm.ringtoneUri) }
    var forceMaxVolume by remember { mutableStateOf(initial.alarm.forceMaxVolume) }

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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.phrase.isEmpty()) "New trigger" else "Edit trigger") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                HorizontalDivider()
                Text("Alarm", style = MaterialTheme.typography.titleSmall)
                VibrationDropdown(selected = vibration, onChange = { vibration = it })
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
                            alarm = AlarmProfile(
                                ringtoneUri = ringtoneUri,
                                vibration = vibration,
                                escalationSeconds = escalation.toInt().coerceIn(1, 600),
                                maxVolumePercent = maxVolume.toInt().coerceIn(0, 100),
                                forceMaxVolume = forceMaxVolume,
                            ),
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val cn = ComponentName(context, NotificationProbeService::class.java)
    val flat = AndroidSettings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners",
    ) ?: return false
    return flat.split(":").any { it.equals(cn.flattenToString(), ignoreCase = true) }
}
