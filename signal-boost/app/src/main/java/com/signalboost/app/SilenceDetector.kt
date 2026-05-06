package com.signalboost.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

class SilenceDetector(
    context: Context,
    private val detectShake: Boolean,
    private val detectFlip: Boolean,
    private val onTrigger: (Reason) -> Unit,
) : SensorEventListener {

    enum class Reason { SHAKE, FLIP }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Shake state: count threshold-crossings within a sliding window.
    private val shakeEvents = ArrayDeque<Long>()

    // Flip state: low-pass filtered gravity Z to ignore transient bumps.
    private var smoothedZ: Float = 0f
    private var faceDownSince: Long = 0L
    private var armed: Boolean = false  // require seeing face-up first
    private var lastTriggerMs: Long = 0L

    fun start() {
        val mgr = sensorManager ?: return
        val sensor = accelerometer ?: return
        mgr.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        shakeEvents.clear()
        faceDownSince = 0L
        armed = false
    }

    /** Suppress detection for [ms] (e.g. while a snooze countdown runs). */
    fun cooldown(ms: Long) {
        lastTriggerMs = SystemClock.elapsedRealtime() + ms - TRIGGER_COOLDOWN_MS
        shakeEvents.clear()
        faceDownSince = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerMs < TRIGGER_COOLDOWN_MS) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (detectShake) updateShake(now, x, y, z)
        if (detectFlip) updateFlip(now, z)
    }

    private fun updateShake(now: Long, x: Float, y: Float, z: Float) {
        // Subtract gravity magnitude so steady orientation reads ~0.
        val magnitude = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH
        if (abs(magnitude) >= SHAKE_THRESHOLD_MS2) {
            shakeEvents.addLast(now)
        }
        while (shakeEvents.isNotEmpty() && now - shakeEvents.first() > SHAKE_WINDOW_MS) {
            shakeEvents.removeFirst()
        }
        if (shakeEvents.size >= SHAKE_MIN_EVENTS) {
            fire(Reason.SHAKE, now)
        }
    }

    private fun updateFlip(now: Long, z: Float) {
        smoothedZ = smoothedZ * (1f - FLIP_SMOOTH_ALPHA) + z * FLIP_SMOOTH_ALPHA
        if (!armed && smoothedZ > FACE_UP_Z) {
            armed = true
        }
        if (armed && smoothedZ < FACE_DOWN_Z) {
            if (faceDownSince == 0L) faceDownSince = now
            else if (now - faceDownSince >= FLIP_HOLD_MS) {
                fire(Reason.FLIP, now)
                armed = false
            }
        } else {
            faceDownSince = 0L
        }
    }

    private fun fire(reason: Reason, now: Long) {
        lastTriggerMs = now
        shakeEvents.clear()
        faceDownSince = 0L
        onTrigger(reason)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        // Shake: ≥3 high-magnitude samples within a 700ms window.
        private const val SHAKE_THRESHOLD_MS2 = 12f
        private const val SHAKE_WINDOW_MS = 700L
        private const val SHAKE_MIN_EVENTS = 3

        // Flip: smoothed Z must cross below -7 m/s² and stay there for 400ms.
        private const val FLIP_SMOOTH_ALPHA = 0.25f
        private const val FACE_UP_Z = 6f
        private const val FACE_DOWN_Z = -7f
        private const val FLIP_HOLD_MS = 400L

        // Ignore further triggers for 1.5s after firing (debounce).
        private const val TRIGGER_COOLDOWN_MS = 1500L
    }
}
