package com.crosscheck.app.data

import android.content.Context
import com.crosscheck.app.models.Chat
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

class ChatRepository(private val context: Context) {
    private val gson = Gson()
    private val chatsFile = File(context.filesDir, "chats.json")
    private val currentQueryFile = File(context.filesDir, "current_query.json")
    private val currentChatIdFile = File(context.filesDir, "current_chat_id.txt")

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private var _currentChatId: String? = null
    val currentChatId: String?
        get() = _currentChatId

    init {
        loadChats()
        loadCurrentChatId()
    }

    /**
     * Get the JSONL file for a specific chat
     */
    private fun getChatFile(chatId: String): File {
        return File(context.filesDir, "chat_$chatId.jsonl")
    }

    /**
     * Create a new chat
     */
    suspend fun createChat(): Chat = withContext(Dispatchers.IO) {
        val chat = Chat()
        val currentChats = _chats.value.toMutableList()
        currentChats.add(0, chat)
        saveChats(currentChats)
        _currentChatId = chat.id
        saveCurrentChatId(chat.id)
        chat
    }

    /**
     * Get or create current chat
     */
    suspend fun getCurrentChat(): Chat {
        return _currentChatId?.let { chatId ->
            _chats.value.find { it.id == chatId }
        } ?: createChat()
    }

    /**
     * Set the current active chat
     */
    suspend fun setCurrentChat(chatId: String) = withContext(Dispatchers.IO) {
        _currentChatId = chatId
        saveCurrentChatId(chatId)
    }

    /**
     * Update chat name (after auto-generation)
     */
    suspend fun updateChatName(chatId: String, name: String) = withContext(Dispatchers.IO) {
        val currentChats = _chats.value.toMutableList()
        val index = currentChats.indexOfFirst { it.id == chatId }
        if (index >= 0) {
            currentChats[index] = currentChats[index].withName(name)
            saveChats(currentChats)
        }
    }

    /**
     * Increment query count for a chat
     */
    suspend fun incrementChatQueryCount(chatId: String) = withContext(Dispatchers.IO) {
        val currentChats = _chats.value.toMutableList()
        val index = currentChats.indexOfFirst { it.id == chatId }
        if (index >= 0) {
            currentChats[index] = currentChats[index].incrementQueryCount()
            saveChats(currentChats)
        }
    }

    /**
     * Save a query to a chat's JSONL file
     * Each query is written as a single line of JSON
     */
    suspend fun saveQuery(query: QueryHistory) = withContext(Dispatchers.IO) {
        try {
            // Save current query for crash recovery
            currentQueryFile.writeText(gson.toJson(query))

            // Get the chat file
            val chatFile = getChatFile(query.chatId)

            // Read existing queries
            val existingQueries = if (chatFile.exists()) {
                chatFile.readLines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        try {
                            gson.fromJson(line, QueryHistory::class.java)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .toMutableList()
            } else {
                mutableListOf()
            }

            // Update or add query
            val existingIndex = existingQueries.indexOfFirst { it.id == query.id }
            if (existingIndex >= 0) {
                existingQueries[existingIndex] = query
            } else {
                existingQueries.add(query)
            }

            // Write back to JSONL format (one JSON per line)
            chatFile.writeText(
                existingQueries.joinToString("\n") { gson.toJson(it) }
            )

            // Increment query count if this is a new query
            if (existingIndex < 0) {
                incrementChatQueryCount(query.chatId)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * Load all queries for a specific chat from JSONL file
     */
    suspend fun getQueriesForChat(chatId: String): List<QueryHistory> = withContext(Dispatchers.IO) {
        try {
            val chatFile = getChatFile(chatId)
            if (!chatFile.exists()) return@withContext emptyList()

            chatFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        gson.fromJson(line, QueryHistory::class.java)
                    } catch (e: Exception) {
                        null
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Load the current in-progress query (for crash recovery)
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
     * Clear crash recovery file after successful completion
     */
    suspend fun clearCurrentQuery() = withContext(Dispatchers.IO) {
        try {
            currentQueryFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Delete a chat and its queries
     */
    suspend fun deleteChat(chatId: String) = withContext(Dispatchers.IO) {
        try {
            val currentChats = _chats.value.toMutableList()
            currentChats.removeIf { it.id == chatId }
            saveChats(currentChats)

            // Delete the chat's JSONL file
            getChatFile(chatId).delete()

            // If this was the current chat, clear it
            if (_currentChatId == chatId) {
                _currentChatId = null
                currentChatIdFile.delete()
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun saveChats(chats: List<Chat>) {
        try {
            chatsFile.writeText(gson.toJson(chats))
            _chats.value = chats
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun loadChats() {
        try {
            if (chatsFile.exists()) {
                val json = chatsFile.readText()
                val type = object : TypeToken<List<Chat>>() {}.type
                _chats.value = gson.fromJson(json, type) ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _chats.value = emptyList()
        }
    }

    private fun loadCurrentChatId() {
        try {
            if (currentChatIdFile.exists()) {
                _currentChatId = currentChatIdFile.readText().trim()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCurrentChatId(chatId: String) {
        try {
            currentChatIdFile.writeText(chatId)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
