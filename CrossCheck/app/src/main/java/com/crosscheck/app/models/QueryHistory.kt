package com.crosscheck.app.models

import com.google.gson.annotations.SerializedName
import java.util.UUID

data class QueryHistory(
    val id: String = UUID.randomUUID().toString(),
    val question: String,
    val timestamp: Long = System.currentTimeMillis(),
    val firstResponse: String? = null,
    val secondResponse: String? = null,
    val thirdResponse: String? = null,
    val lastSuccessfulStage: Int = 0, // 0 = none, 1 = first, 2 = second, 3 = third
    val lastError: String? = null,
    val settings: AppSettings? = null // Store settings used for potential retry
) {
    fun isComplete(): Boolean = thirdResponse != null

    fun canRetry(): Boolean = lastSuccessfulStage < 3 && lastError != null

    fun getNextStage(): Int = lastSuccessfulStage + 1

    fun updateWithStage1(response: String): QueryHistory {
        return copy(
            firstResponse = response,
            lastSuccessfulStage = 1,
            lastError = null
        )
    }

    fun updateWithStage2(response: String): QueryHistory {
        return copy(
            secondResponse = response,
            lastSuccessfulStage = 2,
            lastError = null
        )
    }

    fun updateWithStage3(response: String): QueryHistory {
        return copy(
            thirdResponse = response,
            lastSuccessfulStage = 3,
            lastError = null
        )
    }

    fun updateWithError(stage: Int, error: String): QueryHistory {
        return copy(lastError = "Stage $stage error: $error")
    }
}
