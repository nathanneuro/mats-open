package com.signalboost.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AlarmService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var escalationJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTrigger: Trigger? = null
    private var savedAlarmVolume: Int? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAlarm()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val trigger = intent.getTrigger() ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAlarm(trigger)
            }
        }
        return START_STICKY
    }

    private fun startAlarm(trigger: Trigger) {
        if (active.value) return
        currentTrigger = trigger
        active.value = true
        activeTriggerLabel.value = trigger.label.ifEmpty { trigger.phrase }

        startForegroundCompat(buildNotification(trigger))
        acquireWakeLock()
        launchAlarmActivity(trigger)
        beginEscalation(trigger)
    }

    private fun stopAlarm() {
        escalationJob?.cancel()
        escalationJob = null
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        runCatching { vibrator()?.cancel() }
        abandonAudioFocus()
        restoreAlarmStreamVolume()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        active.value = false
        activeTriggerLabel.value = null
        currentTrigger = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun forceAlarmStreamToMax() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (savedAlarmVolume == null) {
                savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            }
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }
    }

    private fun restoreAlarmStreamVolume() {
        val saved = savedAlarmVolume ?: return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
        savedAlarmVolume = null
    }

    private fun requestAudioFocus(attributes: AudioAttributes) {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { /* keep playing regardless */ }
            .build()
        runCatching { am.requestAudioFocus(request) }
        audioFocusRequest = request
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest ?: return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { am.abandonAudioFocusRequest(request) }
        audioFocusRequest = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun beginEscalation(trigger: Trigger) {
        escalationJob = scope.launch {
            val profile = trigger.alarm
            val ringtoneUri = profile.ringtoneUri?.let(Uri::parse)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (profile.forceMaxVolume) {
                forceAlarmStreamToMax()
            }
            requestAudioFocus(audioAttributes)

            val player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(audioAttributes)
                    setDataSource(this@AlarmService, ringtoneUri)
                    isLooping = true
                    setVolume(0f, 0f)
                    prepare()
                    start()
                }
            }.getOrNull()
            mediaPlayer = player

            val totalMs = profile.escalationSeconds * 1000L
            val maxLevel = profile.maxVolumePercent / 100f
            val steps = 60
            val stepMs = (totalMs / steps).coerceAtLeast(50L)

            startVibration(profile.vibration)

            for (i in 1..steps) {
                if (!isActive) return@launch
                val fraction = i.toFloat() / steps
                val volume = (fraction * maxLevel).coerceIn(0f, 1f)
                runCatching { player?.setVolume(volume, volume) }
                delay(stepMs)
            }

            // Hold at max until the user dismisses
            while (isActive) {
                runCatching { player?.setVolume(maxLevel, maxLevel) }
                delay(1000L)
            }
        }
    }

    private fun startVibration(style: VibrationStyle) {
        val v = vibrator() ?: return
        val pattern: LongArray = when (style) {
            VibrationStyle.NONE -> return
            VibrationStyle.GENTLE -> longArrayOf(0, 200, 1500)
            VibrationStyle.NORMAL -> longArrayOf(0, 400, 600, 400, 600)
            VibrationStyle.INTENSE -> longArrayOf(0, 800, 200, 800, 200, 800, 200)
        }
        val effect = VibrationEffect.createWaveform(pattern, 0)
        v.vibrate(effect)
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SignalBoost::AlarmWakeLock",
        ).apply { acquire(10 * 60 * 1000L) }
    }

    private fun launchAlarmActivity(trigger: Trigger) {
        val intent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtras(trigger.toBundle())
        }
        startActivity(intent)
    }

    private fun buildNotification(trigger: Trigger): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtras(trigger.toBundle()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = getString(R.string.alarm_active_title)
        val text = trigger.label.ifEmpty { trigger.phrase }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(getString(R.string.alarm_active_body, text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(openIntent, true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.stop_alarm), stopIntent)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.alarm_channel_desc)
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "com.signalboost.action.START_ALARM"
        const val ACTION_STOP = "com.signalboost.action.STOP_ALARM"
        const val CHANNEL_ID = "signal_boost_alarm"
        const val NOTIF_ID = 0xA1A2

        val active = MutableStateFlow(false)
        val activeTriggerLabel = MutableStateFlow<String?>(null)

        fun start(context: Context, trigger: Trigger) {
            val intent = Intent(context, AlarmService::class.java)
                .setAction(ACTION_START)
                .putExtras(trigger.toBundle())
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AlarmService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

private const val EXTRA_ID = "trigger_id"
private const val EXTRA_LABEL = "trigger_label"
private const val EXTRA_PHRASE = "trigger_phrase"
private const val EXTRA_CASE = "trigger_case"
private const val EXTRA_RINGTONE = "alarm_ringtone"
private const val EXTRA_VIBRATION = "alarm_vibration"
private const val EXTRA_ESCALATION = "alarm_escalation"
private const val EXTRA_VOLUME = "alarm_max_volume"
private const val EXTRA_FORCE_MAX = "alarm_force_max"

fun Trigger.toBundle(): Bundle = Bundle().apply {
    putString(EXTRA_ID, id)
    putString(EXTRA_LABEL, label)
    putString(EXTRA_PHRASE, phrase)
    putBoolean(EXTRA_CASE, caseSensitive)
    putString(EXTRA_RINGTONE, alarm.ringtoneUri)
    putString(EXTRA_VIBRATION, alarm.vibration.name)
    putInt(EXTRA_ESCALATION, alarm.escalationSeconds)
    putInt(EXTRA_VOLUME, alarm.maxVolumePercent)
    putBoolean(EXTRA_FORCE_MAX, alarm.forceMaxVolume)
}

fun Intent.getTrigger(): Trigger? {
    val id = getStringExtra(EXTRA_ID) ?: return null
    val phrase = getStringExtra(EXTRA_PHRASE) ?: return null
    return Trigger(
        id = id,
        label = getStringExtra(EXTRA_LABEL).orEmpty(),
        phrase = phrase,
        caseSensitive = getBooleanExtra(EXTRA_CASE, false),
        alarm = AlarmProfile(
            ringtoneUri = getStringExtra(EXTRA_RINGTONE),
            vibration = runCatching {
                VibrationStyle.valueOf(getStringExtra(EXTRA_VIBRATION) ?: "NORMAL")
            }.getOrDefault(VibrationStyle.NORMAL),
            escalationSeconds = getIntExtra(EXTRA_ESCALATION, 20),
            maxVolumePercent = getIntExtra(EXTRA_VOLUME, 100),
            forceMaxVolume = getBooleanExtra(EXTRA_FORCE_MAX, true),
        )
    )
}
