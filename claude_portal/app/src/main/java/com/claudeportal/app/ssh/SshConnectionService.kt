package com.claudeportal.app.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.claudeportal.app.MainActivity
import com.claudeportal.app.R

/**
 * Foreground service that keeps the SSH connection alive when the app is
 * in the background or the phone screen is off.
 *
 * Android will kill background processes aggressively to reclaim memory,
 * but a foreground service with a persistent notification is protected.
 * This is the standard pattern for SSH/VPN/music apps that need to stay alive.
 *
 * The service itself doesn't hold the SSH connection — SshManager does.
 * This service just tells Android "don't kill this process."
 */
class SshConnectionService : Service() {

    companion object {
        const val CHANNEL_ID = "ssh_connection"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.claudeportal.app.STOP_SSH"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val serverName = intent?.getStringExtra("server_name") ?: "server"
        val notification = buildNotification(serverName)
        startForeground(NOTIFICATION_ID, notification)

        // If the system kills the service, don't restart automatically —
        // the user can tap to reconnect from the activity.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SSH Connection",
                NotificationManager.IMPORTANCE_LOW // No sound, just persistent icon
            ).apply {
                description = "Keeps SSH connection alive in background"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(serverName: String): Notification {
        // Tap notification → open the app
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Disconnect" action button
        val stopIntent = Intent(this, SshConnectionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SSH Connected")
            .setContentText("Connected to $serverName")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPending)
            .addAction(0, "Disconnect", stopPending)
            .build()
    }

    fun updateServerName(serverName: String) {
        val notification = buildNotification(serverName)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }
}
