package com.claudeportal.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class HistoryRepository(private val context: Context) {

    val historyDir: File
        get() = File(context.filesDir, "session_history").also { it.mkdirs() }

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
