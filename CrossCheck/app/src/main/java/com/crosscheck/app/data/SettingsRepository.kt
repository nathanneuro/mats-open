package com.crosscheck.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.crosscheck.app.models.ApiProvider
import com.crosscheck.app.models.AppSettings
import com.crosscheck.app.models.QueryMode
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val gson = Gson()

    companion object {
        private val PROVIDERS_KEY = stringPreferencesKey("providers")
        private val QUERY_ORDER_KEY = stringPreferencesKey("query_order")
        private val UTILITY_MODEL_KEY = stringPreferencesKey("utility_model")
        private val QUERY_MODE_KEY = stringPreferencesKey("query_mode")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val providersJson = preferences[PROVIDERS_KEY]
        val queryOrderJson = preferences[QUERY_ORDER_KEY]
        val utilityModelJson = preferences[UTILITY_MODEL_KEY]
        val queryModeJson = preferences[QUERY_MODE_KEY]

        val providers = if (providersJson != null) {
            try {
                val type = object : TypeToken<List<ApiProvider>>() {}.type
                gson.fromJson<List<ApiProvider>>(providersJson, type)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val queryOrder = if (queryOrderJson != null) {
            try {
                val type = object : TypeToken<List<Int>>() {}.type
                gson.fromJson<List<Int>>(queryOrderJson, type)
            } catch (e: Exception) {
                listOf(0, 1, 2)
            }
        } else {
            listOf(0, 1, 2)
        }

        val utilityModel = if (utilityModelJson != null) {
            try {
                gson.fromJson(utilityModelJson, ApiProvider::class.java)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        val queryMode = if (queryModeJson != null) {
            try {
                QueryMode.valueOf(queryModeJson)
            } catch (e: Exception) {
                QueryMode.ABC
            }
        } else {
            QueryMode.ABC
        }

        AppSettings(providers, queryOrder, queryMode, utilityModel)
    }

    suspend fun saveSettings(settings: AppSettings) {
        context.dataStore.edit { preferences ->
            preferences[PROVIDERS_KEY] = gson.toJson(settings.providers)
            preferences[QUERY_ORDER_KEY] = gson.toJson(settings.queryOrder)
            preferences[QUERY_MODE_KEY] = settings.queryMode.name
            if (settings.utilityModel != null) {
                preferences[UTILITY_MODEL_KEY] = gson.toJson(settings.utilityModel)
            } else {
                preferences.remove(UTILITY_MODEL_KEY)
            }
        }
    }

    suspend fun clearSettings() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
