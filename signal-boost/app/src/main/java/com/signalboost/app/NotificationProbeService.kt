package com.signalboost.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class NotificationProbeService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var settings: Settings = Prefs.DEFAULT

    override fun onCreate() {
        super.onCreate()
        connected.value = true
        scope.launch {
            Prefs.flow(applicationContext).collect { settings = it }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected.value = true
    }

    override fun onListenerDisconnected() {
        connected.value = false
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        connected.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val s = settings
        if (s.triggers.isEmpty()) return
        if (s.signalOnly && sbn.packageName !in SignalPackages.ALL) return

        val haystack = extractText(sbn)
        if (haystack.isBlank()) return

        val match = s.triggers.firstOrNull { it.matches(haystack) } ?: return
        Log.i(TAG, "Match: trigger='${match.phrase}' from ${sbn.packageName}")
        AlarmService.start(applicationContext, match)
    }

    private fun extractText(sbn: StatusBarNotification): String {
        val extras = sbn.notification?.extras ?: return ""
        val parts = mutableListOf<String>()
        listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
        ).forEach { key ->
            (extras.getCharSequence(key))?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
        }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
            line?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
        }
        return parts.joinToString("\n")
    }

    companion object {
        private const val TAG = "SignalBoostProbe"
        val connected = MutableStateFlow(false)
    }
}
