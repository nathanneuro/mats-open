package com.crosscheck.app.query

import com.crosscheck.app.api.ApiClient
import com.crosscheck.app.models.AppSettings
import com.crosscheck.app.models.QueryResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class QueryManager(private val apiClient: ApiClient) {

    private val _queryState = MutableStateFlow(QueryResponse())
    val queryState: StateFlow<QueryResponse> = _queryState.asStateFlow()

    suspend fun executeQuery(userQuestion: String, settings: AppSettings) {
        if (!settings.isValid()) {
            _queryState.value = QueryResponse(error = "Invalid settings configuration")
            return
        }

        _queryState.value = QueryResponse(isLoading = true, currentStage = 1)

        // Stage 1: Initial scientific answer
        val provider1 = settings.getProviderForStage(0)
        if (provider1 == null) {
            _queryState.value = QueryResponse(error = "No provider configured for stage 1")
            return
        }

        val prompt1 = buildPrompt1(userQuestion)
        val result1 = apiClient.sendQuery(provider1, prompt1)

        if (result1.isFailure) {
            _queryState.value = QueryResponse(
                error = "Stage 1 error: ${result1.exceptionOrNull()?.message}"
            )
            return
        }

        val firstResponse = result1.getOrNull()!!
        _queryState.value = QueryResponse(
            firstResponse = firstResponse,
            isLoading = true,
            currentStage = 2
        )

        // Stage 2: Cross-check
        val provider2 = settings.getProviderForStage(1)
        if (provider2 == null) {
            _queryState.value = QueryResponse(
                firstResponse = firstResponse,
                error = "No provider configured for stage 2"
            )
            return
        }

        val prompt2 = buildPrompt2(userQuestion, firstResponse)
        val result2 = apiClient.sendQuery(provider2, prompt2)

        if (result2.isFailure) {
            _queryState.value = QueryResponse(
                firstResponse = firstResponse,
                error = "Stage 2 error: ${result2.exceptionOrNull()?.message}"
            )
            return
        }

        val secondResponse = result2.getOrNull()!!
        _queryState.value = QueryResponse(
            firstResponse = firstResponse,
            secondResponse = secondResponse,
            isLoading = true,
            currentStage = 3
        )

        // Stage 3: Final synthesis
        val provider3 = settings.getProviderForStage(2)
        if (provider3 == null) {
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
            _queryState.value = QueryResponse(
                firstResponse = firstResponse,
                secondResponse = secondResponse,
                error = "Stage 3 error: ${result3.exceptionOrNull()?.message}"
            )
            return
        }

        val thirdResponse = result3.getOrNull()!!
        _queryState.value = QueryResponse(
            firstResponse = firstResponse,
            secondResponse = secondResponse,
            thirdResponse = thirdResponse,
            isLoading = false,
            currentStage = 3
        )
    }

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
