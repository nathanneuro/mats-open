package com.crosscheck.app.data

import android.content.Context
import com.crosscheck.app.models.QueryHistory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class QueryRepository(private val context: Context) {
    private val gson = Gson()
    private val queriesFile = File(context.filesDir, "query_history.json")
    private val currentQueryFile = File(context.filesDir, "current_query.json")

    private val _queries = MutableStateFlow<List<QueryHistory>>(emptyList())
    val queries: StateFlow<List<QueryHistory>> = _queries.asStateFlow()

    init {
        loadQueries()
    }

    /**
     * Save a query to history. This is called after each stage completes or fails.
     * Plaintext is cheap - we save aggressively to protect against data loss.
     */
    suspend fun saveQuery(query: QueryHistory) = withContext(Dispatchers.IO) {
        try {
            // Save current query immediately to separate file for crash recovery
            currentQueryFile.writeText(gson.toJson(query))

            // Update or add to history
            val currentQueries = _queries.value.toMutableList()
            val existingIndex = currentQueries.indexOfFirst { it.id == query.id }

            if (existingIndex >= 0) {
                currentQueries[existingIndex] = query
            } else {
                currentQueries.add(0, query) // Add to front
            }

            // Keep only last 100 queries to prevent unbounded growth
            val trimmedQueries = currentQueries.take(100)

            // Save to disk
            queriesFile.writeText(gson.toJson(trimmedQueries))
            _queries.value = trimmedQueries
        } catch (e: IOException) {
            // Log error but don't throw - we don't want to crash on save failure
            e.printStackTrace()
        }
    }

    /**
     * Load the current in-progress query if it exists (for crash recovery)
     */
    suspend fun loadCurrentQuery(): QueryHistory? = withContext(Dispatchers.IO) {
        try {
            if (currentQueryFile.exists()) {
                val json = currentQueryFile.readText()
                gson.fromJson(json, QueryHistory::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Clear the current query file when a query completes successfully
     */
    suspend fun clearCurrentQuery() = withContext(Dispatchers.IO) {
        try {
            currentQueryFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Get a specific query by ID
     */
    fun getQueryById(id: String): QueryHistory? {
        return _queries.value.firstOrNull { it.id == id }
    }

    /**
     * Delete a query from history
     */
    suspend fun deleteQuery(id: String) = withContext(Dispatchers.IO) {
        try {
            val currentQueries = _queries.value.toMutableList()
            currentQueries.removeIf { it.id == id }
            queriesFile.writeText(gson.toJson(currentQueries))
            _queries.value = currentQueries
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * Clear all query history
     */
    suspend fun clearAllQueries() = withContext(Dispatchers.IO) {
        try {
            queriesFile.delete()
            currentQueryFile.delete()
            _queries.value = emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadQueries() {
        try {
            if (queriesFile.exists()) {
                val json = queriesFile.readText()
                val type = object : TypeToken<List<QueryHistory>>() {}.type
                _queries.value = gson.fromJson(json, type) ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _queries.value = emptyList()
        }
    }
}
