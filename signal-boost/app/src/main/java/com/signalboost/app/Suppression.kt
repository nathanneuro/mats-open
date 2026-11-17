package com.signalboost.app

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.service.notification.StatusBarNotification

/** Decides when a matching notification should NOT raise an alarm. */
object Suppression {
    /** Minimum gap between one alarm being dismissed and the next firing. */
    const val COOLDOWN_MS = 5 * 60_000L

    // Activities resumed longer ago than this are not seen, so the alarm
    // fires — the safe direction to be wrong in.
    private const val FOREGROUND_LOOKBACK_MS = 12 * 3_600_000L

    /** Why this notification should stay quiet, or null if it should alarm. */
    fun reason(context: Context, sbn: StatusBarNotification): String? = when {
        sbn.notification.category == Notification.CATEGORY_CALL -> "call notification"
        inCall(context) -> "call in progress"
        inCooldown() -> "cooldown after last alarm"
        userIsLookingAt(context, sbn.packageName) -> "app already open and unlocked"
        else -> null
    }

    // Any non-normal audio mode: ringing, cellular call, or VoIP (Signal
    // calls set MODE_IN_COMMUNICATION).
    private fun inCall(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java).mode != AudioManager.MODE_NORMAL

    private fun inCooldown(): Boolean {
        val stopped = AlarmService.lastStoppedElapsed ?: return false
        return SystemClock.elapsedRealtime() - stopped < COOLDOWN_MS
    }

    private fun userIsLookingAt(context: Context, packageName: String): Boolean =
        context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            isForeground(context, packageName)

    /** Needs usage access; without it the event list is empty and this is false. */
    private fun isForeground(context: Context, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val events = context.getSystemService(UsageStatsManager::class.java)
            .queryEvents(now - FOREGROUND_LOOKBACK_MS, now)
        // Tracked per activity: moving between two screens of one app logs
        // A paused, B resumed, A stopped — so "last event" alone would lie.
        val resumed = mutableSetOf<String>()
        val event = UsageEvents.Event()
        while (events.getNextEvent(event)) {
            if (event.packageName != packageName) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumed += event.className.orEmpty()
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> resumed -= event.className.orEmpty()
            }
        }
        return resumed.isNotEmpty()
    }

    fun hasUsageAccess(context: Context): Boolean {
        @Suppress("DEPRECATION")
        val mode = context.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
