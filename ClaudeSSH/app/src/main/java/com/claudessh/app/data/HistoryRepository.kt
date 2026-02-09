package com.claudessh.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HistoryRepository(private val context: Context) {

    private val historyDir: File
        get() = File(context.filesDir, "session_history").also { it.mkdirs() }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun createSessionFile(connectionName: String): File {
        val timestamp = dateFormat.format(Date())
        val safeName = connectionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(historyDir, "${safeName}_${timestamp}.txt")
    }

    suspend fun appendToSession(file: File, text: String) = withContext(Dispatchers.IO) {
        file.appendText(text)
    }

    suspend fun getSessionHistory(): List<SessionHistoryEntry> = withContext(Dispatchers.IO) {
        historyDir.listFiles()
            ?.filter { it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                SessionHistoryEntry(
                    fileName = file.name,
                    file = file,
                    sizeBytes = file.length(),
                    lastModified = Date(file.lastModified())
                )
            } ?: emptyList()
    }

    suspend fun loadSession(file: File): String = withContext(Dispatchers.IO) {
        if (file.exists()) file.readText() else ""
    }

    suspend fun deleteSession(file: File) = withContext(Dispatchers.IO) {
        file.delete()
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        historyDir.listFiles()?.forEach { it.delete() }
    }
}

data class SessionHistoryEntry(
    val fileName: String,
    val file: File,
    val sizeBytes: Long,
    val lastModified: Date
)
