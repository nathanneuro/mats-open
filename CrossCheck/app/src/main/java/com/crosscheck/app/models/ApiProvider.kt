package com.crosscheck.app.models

import com.google.gson.annotations.SerializedName

enum class ProviderType {
    @SerializedName("openrouter")
    OPENROUTER,

    @SerializedName("anthropic")
    ANTHROPIC,

    @SerializedName("gemini")
    GEMINI
}

data class ApiProvider(
    val type: ProviderType,
    val apiKey: String,
    val modelName: String
) {
    fun getDisplayName(): String {
        return when (type) {
            ProviderType.OPENROUTER -> "OpenRouter"
            ProviderType.ANTHROPIC -> "Anthropic"
            ProviderType.GEMINI -> "Google Gemini"
        }
    }

    fun getBaseUrl(): String {
        return when (type) {
            ProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
            ProviderType.ANTHROPIC -> "https://api.anthropic.com/v1"
            ProviderType.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        }
    }
}
