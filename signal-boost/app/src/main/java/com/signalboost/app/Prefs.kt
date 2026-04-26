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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ringtoneUri", ringtoneUri ?: JSONObject.NULL)
        put("vibration", vibration.name)
        put("escalationSeconds", escalationSeconds)
        put("maxVolumePercent", maxVolumePercent)
    }

    companion object {
        fun fromJson(obj: JSONObject): AlarmProfile = AlarmProfile(
            ringtoneUri = obj.optString("ringtoneUri").takeIf { it.isNotEmpty() && it != "null" },
            vibration = runCatching { VibrationStyle.valueOf(obj.optString("vibration", "NORMAL")) }
                .getOrDefault(VibrationStyle.NORMAL),
            escalationSeconds = obj.optInt("escalationSeconds", 20).coerceIn(1, 600),
            maxVolumePercent = obj.optInt("maxVolumePercent", 100).coerceIn(0, 100),
        )
    }
}

data class Trigger(
    val id: String,
    val label: String,
    val phrase: String,
    val caseSensitive: Boolean,
    val alarm: AlarmProfile,
) {
    fun matches(text: CharSequence?): Boolean {
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
    }

    companion object {
        fun fromJson(obj: JSONObject): Trigger = Trigger(
            id = obj.optString("id").ifEmpty { UUID.randomUUID().toString() },
            label = obj.optString("label", ""),
            phrase = obj.optString("phrase", ""),
            caseSensitive = obj.optBoolean("caseSensitive", false),
            alarm = obj.optJSONObject("alarm")?.let(AlarmProfile::fromJson) ?: AlarmProfile(),
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
    val signalOnly: Boolean,
)

object Prefs {
    private val KEY_TRIGGERS = stringPreferencesKey("triggers_json")
    private val KEY_SIGNAL_ONLY = booleanPreferencesKey("signal_only")

    val DEFAULT = Settings(triggers = emptyList(), signalOnly = true)

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

    suspend fun setSignalOnly(context: Context, value: Boolean) {
        context.dataStore.edit { it[KEY_SIGNAL_ONLY] = value }
    }

    private fun Preferences.toSettings(): Settings {
        val raw = this[KEY_TRIGGERS].orEmpty()
        val triggers = if (raw.isBlank()) emptyList() else runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Trigger.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        return Settings(
            triggers = triggers,
            signalOnly = this[KEY_SIGNAL_ONLY] ?: true,
        )
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
