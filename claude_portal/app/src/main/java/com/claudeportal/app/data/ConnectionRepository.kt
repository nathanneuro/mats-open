package com.claudeportal.app.data

import android.content.Context
import com.claudeportal.app.models.ConnectionProfile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ConnectionRepository(private val context: Context) {

    private val gson = Gson()
    private val connectionsFile: File
        get() = File(context.filesDir, "connections.json")

    suspend fun getConnections(): List<ConnectionProfile> = withContext(Dispatchers.IO) {
        if (!connectionsFile.exists()) return@withContext emptyList()
        try {
            val json = connectionsFile.readText()
            val type = object : TypeToken<List<ConnectionProfile>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveConnection(profile: ConnectionProfile) = withContext(Dispatchers.IO) {
        val connections = getConnections().toMutableList()
        val index = connections.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            connections[index] = profile
        } else {
            connections.add(profile)
        }
        writeConnections(connections)
    }

    suspend fun deleteConnection(id: String) = withContext(Dispatchers.IO) {
        val connections = getConnections().filter { it.id != id }
        writeConnections(connections)
    }

    suspend fun getConnection(id: String): ConnectionProfile? = withContext(Dispatchers.IO) {
        getConnections().find { it.id == id }
    }

    private fun writeConnections(connections: List<ConnectionProfile>) {
        connectionsFile.writeText(gson.toJson(connections))
    }
}
