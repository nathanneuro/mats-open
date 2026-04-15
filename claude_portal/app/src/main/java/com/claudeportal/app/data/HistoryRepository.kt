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

    suspend fun loadSession(file: File, maxChars: Int = 200_000): String = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext ""
        // Tail-bounded read so opening a huge session file doesn't OOM.
        val maxBytes = maxChars.toLong() * 4L
        val skip = (file.length() - maxBytes).coerceAtLeast(0L)
        file.inputStream().use { fis ->
            var remaining = skip
            while (remaining > 0) {
                val s = fis.skip(remaining)
                if (s <= 0) break
                remaining -= s
            }
            val text = fis.bufferedReader(Charsets.UTF_8).readText()
            if (text.length > maxChars) text.substring(text.length - maxChars) else text
        }
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
