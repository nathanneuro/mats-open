package com.signalboost.app

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "signal_boost_prefs")

enum class VibrationStyle { NONE, GENTLE, NORMAL, INTENSE }

data class AlarmProfile(
    val ringtoneUri: String? = null,
    val vibration: VibrationStyle = VibrationStyle.NORMAL,
    val escalationSeconds: Int = 20,
    val maxVolumePercent: Int = 100,
    val forceMaxVolume: Boolean = true,
    val silenceOnShake: Boolean = true,
    val silenceOnFlip: Boolean = true,
    val silenceSeconds: Int = 30,
    /** Notification accent colour as ARGB (0 = system default tint). Lets
     *  different triggers have visually distinguishable notifications when
     *  several can fire from the same app. */
    val notificationColorArgb: Int = 0,
    /** Seconds of vibration-only lead before any sound plays. 0 disables
     *  the lead — the alarm starts ramping audio immediately. Useful for
     *  catching attention without waking everyone in the room first. */
    val silentVibrationLeadSeconds: Int = 0,
) {
    val silenceGesturesEnabled: Boolean
        get() = silenceOnShake || silenceOnFlip

    fun toJson(): JSONObject = JSONObject().apply {
        put("ringtoneUri", ringtoneUri ?: JSONObject.NULL)
        put("vibration", vibration.name)
        put("escalationSeconds", escalationSeconds)
        put("maxVolumePercent", maxVolumePercent)
        put("forceMaxVolume", forceMaxVolume)
        put("silenceOnShake", silenceOnShake)
        put("silenceOnFlip", silenceOnFlip)
        put("silenceSeconds", silenceSeconds)
        put("notificationColorArgb", notificationColorArgb)
        put("silentVibrationLeadSeconds", silentVibrationLeadSeconds)
    }

    companion object {
        fun fromJson(obj: JSONObject): AlarmProfile = AlarmProfile(
            ringtoneUri = obj.optString("ringtoneUri").takeIf { it.isNotEmpty() && it != "null" },
            vibration = runCatching { VibrationStyle.valueOf(obj.optString("vibration", "NORMAL")) }
                .getOrDefault(VibrationStyle.NORMAL),
            escalationSeconds = obj.optInt("escalationSeconds", 20).coerceIn(1, 600),
            maxVolumePercent = obj.optInt("maxVolumePercent", 100).coerceIn(0, 100),
            forceMaxVolume = obj.optBoolean("forceMaxVolume", true),
            silenceOnShake = obj.optBoolean("silenceOnShake", true),
            silenceOnFlip = obj.optBoolean("silenceOnFlip", true),
            silenceSeconds = obj.optInt("silenceSeconds", 30).coerceIn(5, 600),
            notificationColorArgb = obj.optInt("notificationColorArgb", 0),
            silentVibrationLeadSeconds = obj.optInt("silentVibrationLeadSeconds", 0)
                .coerceIn(0, 300),
        )
    }
}

data class Trigger(
    val id: String,
    val label: String,
    val phrase: String,
    val caseSensitive: Boolean,
    val alarm: AlarmProfile,
    /** Epoch-ms wall-clock time at which the trigger un-pauses. 0 = not
     *  paused. Wall-clock (not elapsedRealtime) so pause survives device
     *  reboots — a 1 h pause set at 14:00 is still valid at 14:30 even
     *  if the phone restarted in between. */
    val pausedUntil: Long = 0L,
    /** Package names of apps whose notifications are scanned for this
     *  trigger. Empty = all apps. The check happens in
     *  NotificationProbeService before `matches` is consulted. */
    val apps: Set<String> = emptySet(),
) {
    fun isPaused(now: Long = System.currentTimeMillis()): Boolean = pausedUntil > now

    /** True if this trigger watches notifications from the given package
     *  (or watches every app, when apps is empty). */
    fun appliesToPackage(packageName: String?): Boolean =
        apps.isEmpty() || (packageName != null && packageName in apps)

    fun matches(text: CharSequence?): Boolean {
        if (isPaused()) return false
        if (text.isNullOrEmpty() || phrase.isEmpty()) return false
        return if (caseSensitive) text.contains(phrase)
        else text.toString().contains(phrase, ignoreCase = true)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("phrase", phrase)
        put("caseSensitive", caseSensitive)
        put("alarm", alarm.toJson())
        put("pausedUntil", pausedUntil)
        put("apps", JSONArray().apply { apps.sorted().forEach { put(it) } })
    }

    companion object {
        fun fromJson(obj: JSONObject): Trigger = Trigger(
            id = obj.optString("id").ifEmpty { UUID.randomUUID().toString() },
            label = obj.optString("label", ""),
            phrase = obj.optString("phrase", ""),
            caseSensitive = obj.optBoolean("caseSensitive", false),
            alarm = obj.optJSONObject("alarm")?.let(AlarmProfile::fromJson) ?: AlarmProfile(),
            pausedUntil = obj.optLong("pausedUntil", 0L),
            apps = obj.optJSONArray("apps")?.let { arr ->
                (0 until arr.length()).mapNotNull {
                    arr.optString(it).takeIf { s -> s.isNotEmpty() }
                }.toSet()
            } ?: emptySet(),
        )

        fun new(phrase: String = "", label: String = ""): Trigger = Trigger(
            id = UUID.randomUUID().toString(),
            label = label,
            phrase = phrase,
            caseSensitive = false,
            alarm = AlarmProfile(),
        )
    }
}

data class Settings(
    val triggers: List<Trigger>,
)

object Prefs {
    private val KEY_TRIGGERS = stringPreferencesKey("triggers_json")
    // Legacy global key — read once during migration so existing users with
    // signalOnly=true don't suddenly start matching every app.
    private val KEY_SIGNAL_ONLY_LEGACY = booleanPreferencesKey("signal_only")

    val DEFAULT = Settings(triggers = emptyList())

    fun flow(context: Context): Flow<Settings> =
        context.dataStore.data.map { it.toSettings() }

    suspend fun snapshot(context: Context): Settings = flow(context).first()

    suspend fun setTriggers(context: Context, triggers: List<Trigger>) {
        val arr = JSONArray()
        triggers.forEach { arr.put(it.toJson()) }
        context.dataStore.edit { it[KEY_TRIGGERS] = arr.toString() }
    }

    suspend fun upsertTrigger(context: Context, trigger: Trigger) {
        val current = snapshot(context).triggers.toMutableList()
        val idx = current.indexOfFirst { it.id == trigger.id }
        if (idx >= 0) current[idx] = trigger else current.add(trigger)
        setTriggers(context, current)
    }

    suspend fun removeTrigger(context: Context, id: String) {
        val current = snapshot(context).triggers.filterNot { it.id == id }
        setTriggers(context, current)
    }

    /** Pause a trigger until `untilEpochMs`. Pass 0 to resume immediately. */
    suspend fun setTriggerPaused(context: Context, id: String, untilEpochMs: Long) {
        val current = snapshot(context).triggers.toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return
        current[idx] = current[idx].copy(pausedUntil = untilEpochMs.coerceAtLeast(0L))
        setTriggers(context, current)
    }

    /** One-time migration of the old global `signal_only` switch into each
     *  trigger's `apps` filter. Runs whenever the legacy key is still
     *  present; clears it after promoting the value into the triggers. */
    suspend fun migrateLegacySignalOnly(context: Context) {
        val prefs = context.dataStore.data.first()
        val legacy = prefs[KEY_SIGNAL_ONLY_LEGACY] ?: return
        val current = snapshot(context).triggers
        if (legacy && current.isNotEmpty()) {
            val migrated = current.map { t ->
                if (t.apps.isEmpty()) t.copy(apps = SignalPackages.ALL) else t
            }
            setTriggers(context, migrated)
        }
        context.dataStore.edit { it.remove(KEY_SIGNAL_ONLY_LEGACY) }
    }

    private fun Preferences.toSettings(): Settings {
        val raw = this[KEY_TRIGGERS].orEmpty()
        val triggers = if (raw.isBlank()) emptyList() else runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Trigger.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        return Settings(triggers = triggers)
    }
}

object SignalPackages {
    val ALL = setOf(
        "org.thoughtcrime.securesms",
        "org.thoughtcrime.securesms.staging",
        "org.thoughtcrime.securesms.debug",
        "org.thoughtcrime.securesms.main",
    )
}
