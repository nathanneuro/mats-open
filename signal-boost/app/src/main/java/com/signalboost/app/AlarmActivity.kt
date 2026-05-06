package com.signalboost.app

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val initialLabel = intent?.getTrigger()?.let { it.label.ifEmpty { it.phrase } }
        setContent { SignalBoostTheme { AlarmScreen(initialLabel) } }
    }
}

@Composable
private fun AlarmScreen(initialLabel: String?) {
    val context = LocalContext.current
    val active by AlarmService.active.collectAsStateWithLifecycle()
    val label by AlarmService.activeTriggerLabel.collectAsStateWithLifecycle()
    val silencedUntil by AlarmService.silencedUntilElapsedFlow.collectAsStateWithLifecycle()
    val displayed = remember(label, initialLabel) { label ?: initialLabel.orEmpty() }

    var nowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(silencedUntil) {
        while (silencedUntil > SystemClock.elapsedRealtime()) {
            nowMs = SystemClock.elapsedRealtime()
            delay(250L)
        }
        nowMs = SystemClock.elapsedRealtime()
    }
    val secondsLeft = ((silencedUntil - nowMs) / 1000L).toInt().coerceAtLeast(0)
    val isSilenced = silencedUntil > nowMs
    val background = if (isSilenced) Color(0xFF333333) else Color(0xFF8B0000)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.NotificationsActive,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(96.dp),
            )
            Text(
                text = "SIGNAL BOOST",
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = if (displayed.isNotEmpty()) "Triggered by: $displayed" else "Trigger phrase detected",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            if (isSilenced) {
                Text(
                    text = "Silenced — resumes in ${secondsLeft}s",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (active) {
                Text(
                    text = "Shake or flip face-down to silence",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = {
                    AlarmService.stop(context)
                    (context as? AlarmActivity)?.finish()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF8B0000),
                ),
            ) {
                Text(
                    text = if (active) "DISMISS" else "CLOSE",
                    fontWeight = FontWeight.Bold,
                )
            }
            if (active && !isSilenced) {
                OutlinedButton(
                    onClick = { AlarmService.silence(context) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Text("Silence", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
