package com.crosscheck.app.query

import com.crosscheck.app.api.ApiClient
import com.crosscheck.app.data.ChatRepository
import com.crosscheck.app.models.AppSettings
import com.crosscheck.app.models.QueryHistory
import com.crosscheck.app.models.QueryResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class QueryManager(
    private val apiClient: ApiClient,
    private val chatRepository: ChatRepository
) {

    private val _queryState = MutableStateFlow(QueryResponse())
    val queryState: StateFlow<QueryResponse> = _queryState.asStateFlow()

    private var currentQueryHistory: QueryHistory? = null

    /**
     * Execute a new query from scratch
     */
    suspend fun executeQuery(userQuestion: String, settings: AppSettings) {
        // Get or create current chat
        val chat = chatRepository.getCurrentChat()

        // Create new query history and save immediately (user input is valuable!)
        val queryHistory = QueryHistory(
            chatId = chat.id,
            question = userQuestion,
            settings = settings
        )
        currentQueryHistory = queryHistory
        chatRepository.saveQuery(queryHistory)

        executeFromHistory(queryHistory, settings)
    }

    /**
     * Retry a query from where it left off
     */
    suspend fun retryQuery(queryHistory: QueryHistory) {
        currentQueryHistory = queryHistory
        val settings = queryHistory.settings ?: run {
            _queryState.value = QueryResponse(error = "Cannot retry: settings not saved")
            return
        }
        executeFromHistory(queryHistory, settings)
    }

    /**
     * Execute or resume a query based on QueryHistory state
     */
    private suspend fun executeFromHistory(queryHistory: QueryHistory, settings: AppSettings) {
        if (!settings.isValid()) {
            val updated = queryHistory.updateWithError(0, "Invalid settings configuration")
            currentQueryHistory = updated
            chatRepository.saveQuery(updated)
            _queryState.value = QueryResponse(error = "Invalid settings configuration")
            return
        }

        val userQuestion = queryHistory.question
        var history = queryHistory

        // Stage 0: Initial scientific answer (skip if already completed OR not needed for query mode)
        if (settings.shouldRunStage(0) && history.lastSuccessfulStage < 1) {
            _queryState.value = QueryResponse(isLoading = true, currentStage = 1)

            val provider1 = settings.getProviderForStage(0)
            if (provider1 == null) {
                val updated = history.updateWithError(1, "No provider configured for stage 1")
                currentQueryHistory = updated
                chatRepository.saveQuery(updated)
                _queryState.value = QueryResponse(error = "No provider configured for stage 1")
                return
            }

            val prompt1 = buildPrompt1(userQuestion)
            val result1 = apiClient.sendQuery(provider1, prompt1)

            if (result1.isFailure) {
                val errorMsg = result1.exceptionOrNull()?.message ?: "Unknown error"
                val updated = history.updateWithError(1, errorMsg)
                currentQueryHistory = updated
                chatRepository.saveQuery(updated)
                _queryState.value = QueryResponse(
                    error = "Stage 1 error: $errorMsg"
                )
                return
            }

            val firstResponse = result1.getOrNull()!!
            history = history.updateWithStage1(firstResponse)
            currentQueryHistory = history
            chatRepository.saveQuery(history) // Save immediately!
        }

        val firstResponse = history.firstResponse!!
        _queryState.value = QueryResponse(
            firstResponse = firstResponse,
            isLoading = true,
            currentStage = 2
        )

        // Stage 1: Cross-check (skip if already completed OR not needed for query mode)
        if (settings.shouldRunStage(1) && history.lastSuccessfulStage < 2) {
            val provider2 = settings.getProviderForStage(1)
            if (provider2 == null) {
                val updated = history.updateWithError(2, "No provider configured for stage 2")
                currentQueryHistory = updated
                chatRepository.saveQuery(updated)
                _queryState.value = QueryResponse(
                    firstResponse = firstResponse,
                    error = "No provider configured for stage 2"
                )
                return
            }

            val prompt2 = buildPrompt2(userQuestion, firstResponse)
            val result2 = apiClient.sendQuery(provider2, prompt2)

            if (result2.isFailure) {
                val errorMsg = result2.exceptionOrNull()?.message ?: "Unknown error"
                val updated = history.updateWithError(2, errorMsg)
                currentQueryHistory = updated
                chatRepository.saveQuery(updated)
                _queryState.value = QueryResponse(
                    firstResponse = firstResponse,
                    error = "Stage 2 error: $errorMsg"
                )
                return
            }

            val secondResponse = result2.getOrNull()!!
            history = history.updateWithStage2(secondResponse)
            currentQueryHistory = history
            chatRepository.saveQuery(history) // Save immediately!
        }

        val secondResponse = history.secondResponse!!
        _queryState.value = QueryResponse(
            firstResponse = firstResponse,
            secondResponse = secondResponse,
            isLoading = true,
            currentStage = 3
        )

        // Stage 3: Final synthesis (skip if already completed)
        if (history.lastSuccessfulStage < 3) {
            val provider3 = settings.getProviderForStage(2)
            if (provider3 == null) {
                val updated = history.updateWithError(3, "No provider configured for stage 3")
                currentQueryHistory = updated
                chatRepository.saveQuery(updated)
                _queryState.value = QueryResponse(
                    firstResponse = firstResponse,
                    secondResponse = secondResponse,
                    error = "No provider configured for stage 3"
                )
                return
            }

            val prompt3 = buildPrompt3(userQuestion, firstResponse, secondResponse)
            val result3 = apiClient.sendQuery(provider3, prompt3)

            if (result3.isFailure) {
                val errorMsg = result3.exceptionOrNull()?.message ?: "Unknown error"
                val updated = history.updateWithError(3, errorMsg)
                currentQueryHistory = updated
                chatRepository.saveQuery(updated)
                _queryState.value = QueryResponse(
                    firstResponse = firstResponse,
                    secondResponse = secondResponse,
                    error = "Stage 3 error: $errorMsg"
                )
                return
            }

            val thirdResponse = result3.getOrNull()!!
            history = history.updateWithStage3(thirdResponse)
            currentQueryHistory = history
            chatRepository.saveQuery(history) // Save immediately!
            chatRepository.clearCurrentQuery() // Success! Clear crash recovery file
        }

        val thirdResponse = history.thirdResponse!!
        _queryState.value = QueryResponse(
            firstResponse = firstResponse,
            secondResponse = secondResponse,
            thirdResponse = thirdResponse,
            isLoading = false,
            currentStage = 3
        )
    }

    fun getCurrentQueryHistory(): QueryHistory? = currentQueryHistory

    private fun buildPrompt1(userQuestion: String): String {
        return "Please answer the following question scientifically, ideally with citations: $userQuestion"
    }

    private fun buildPrompt2(userQuestion: String, firstResponse: String): String {
        return """Please cross-check this answer, flagging anything you are unsure of or believe is incorrect. If incomplete, flesh it out.

User Question: $userQuestion

First Response: $firstResponse"""
    }

    private fun buildPrompt3(userQuestion: String, firstResponse: String, secondResponse: String): String {
        return """Please check these answers, summarize them, and give your own best answer informed by research.

User Question: $userQuestion

First Response: $firstResponse

Second Response: $secondResponse"""
    }

    fun reset() {
        _queryState.value = QueryResponse()
    }
}
