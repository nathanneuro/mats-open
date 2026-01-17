package com.crosscheck.app.service

import com.crosscheck.app.api.ApiClient
import com.crosscheck.app.data.ChatRepository
import com.crosscheck.app.models.ApiProvider

class ChatNamingService(
    private val apiClient: ApiClient,
    private val chatRepository: ChatRepository
) {

    /**
     * Auto-generate a chat name based on the first question
     * Uses the utility model configured in settings
     */
    suspend fun autoNameChat(chatId: String, firstQuestion: String, utilityModel: ApiProvider) {
        val prompt = """Generate a concise 3-5 word title for this question. Return ONLY the title, no quotes or extra text.

Question: $firstQuestion"""

        val result = apiClient.sendQuery(utilityModel, prompt)

        if (result.isSuccess) {
            val generatedName = result.getOrNull()
                ?.trim()
                ?.removePrefix("\"")
                ?.removeSuffix("\"")
                ?.take(50) // Limit to 50 characters

            if (!generatedName.isNullOrBlank()) {
                chatRepository.updateChatName(chatId, generatedName)
            }
        }
    }
}
