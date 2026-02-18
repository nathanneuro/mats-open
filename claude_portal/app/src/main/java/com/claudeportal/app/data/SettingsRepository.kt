package com.claudeportal.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.claudeportal.app.models.AppSettings
import com.claudeportal.app.models.ArrowPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ARROW_POSITION = stringPreferencesKey("arrow_position")
        val ARROW_OPACITY = floatPreferencesKey("arrow_opacity")
        val FONT_SIZE = intPreferencesKey("font_size")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val MAX_HISTORY_LINES = intPreferencesKey("max_history_lines")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
        val DEFAULT_CONNECTION_ID = stringPreferencesKey("default_connection_id")
        val THINKING_FONT_SIZE = intPreferencesKey("thinking_font_size")
        val TMUX_FONT_SIZE = intPreferencesKey("tmux_font_size")
        val SHOW_EXTRA_KEYS = booleanPreferencesKey("show_extra_keys")
        val VIBRATE_ON_KEY = booleanPreferencesKey("vibrate_on_key")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            arrowPosition = prefs[Keys.ARROW_POSITION]?.let {
                ArrowPosition.valueOf(it)
            } ?: ArrowPosition.RIGHT,
            arrowOpacity = prefs[Keys.ARROW_OPACITY] ?: 0.4f,
            fontSize = prefs[Keys.FONT_SIZE] ?: 14,
            thinkingFontSize = prefs[Keys.THINKING_FONT_SIZE] ?: 13,
            tmuxFontSize = prefs[Keys.TMUX_FONT_SIZE] ?: 12,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            maxHistoryLines = prefs[Keys.MAX_HISTORY_LINES] ?: 50000,
            saveHistoryBetweenSessions = prefs[Keys.SAVE_HISTORY] ?: true,
            defaultConnectionId = prefs[Keys.DEFAULT_CONNECTION_ID],
            showExtraKeys = prefs[Keys.SHOW_EXTRA_KEYS] ?: true,
            vibrateOnKeyPress = prefs[Keys.VIBRATE_ON_KEY] ?: false
        )
    }

    suspend fun updateSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ARROW_POSITION] = settings.arrowPosition.name
            prefs[Keys.ARROW_OPACITY] = settings.arrowOpacity
            prefs[Keys.FONT_SIZE] = settings.fontSize
            prefs[Keys.THINKING_FONT_SIZE] = settings.thinkingFontSize
            prefs[Keys.TMUX_FONT_SIZE] = settings.tmuxFontSize
            prefs[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
            prefs[Keys.MAX_HISTORY_LINES] = settings.maxHistoryLines
            prefs[Keys.SAVE_HISTORY] = settings.saveHistoryBetweenSessions
            prefs[Keys.DEFAULT_CONNECTION_ID] = settings.defaultConnectionId ?: ""
            prefs[Keys.SHOW_EXTRA_KEYS] = settings.showExtraKeys
            prefs[Keys.VIBRATE_ON_KEY] = settings.vibrateOnKeyPress
        }
    }
}
