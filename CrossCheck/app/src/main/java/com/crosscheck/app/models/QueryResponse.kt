package com.crosscheck.app.models

data class QueryResponse(
    val firstResponse: String? = null,
    val secondResponse: String? = null,
    val thirdResponse: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentStage: Int = 0
)

data class QueryStage(
    val stageNumber: Int,
    val prompt: String,
    val response: String? = null,
    val error: String? = null
)
