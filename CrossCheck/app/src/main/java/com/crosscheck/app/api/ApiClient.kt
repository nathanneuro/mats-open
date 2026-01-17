package com.crosscheck.app.api

import com.crosscheck.app.models.ApiProvider
import com.crosscheck.app.models.ProviderType
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun sendQuery(provider: ApiProvider, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            when (provider.type) {
                ProviderType.ANTHROPIC -> sendAnthropicQuery(provider, prompt)
                ProviderType.OPENROUTER -> sendOpenRouterQuery(provider, prompt)
                ProviderType.GEMINI -> sendGeminiQuery(provider, prompt)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun sendAnthropicQuery(provider: ApiProvider, prompt: String): Result<String> {
        val json = JsonObject().apply {
            addProperty("model", provider.modelName)
            add("messages", gson.toJsonTree(listOf(
                mapOf("role" to "user", "content" to prompt)
            )))
            addProperty("max_tokens", 4096)
        }

        val request = Request.Builder()
            .url("${provider.getBaseUrl()}/messages")
            .addHeader("x-api-key", provider.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return Result.failure(Exception("API Error: ${response.code} - $responseBody"))
        }

        val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
        val content = responseJson.getAsJsonArray("content")
            ?.get(0)?.asJsonObject
            ?.get("text")?.asString
            ?: return Result.failure(Exception("Invalid response format"))

        return Result.success(content)
    }

    private fun sendOpenRouterQuery(provider: ApiProvider, prompt: String): Result<String> {
        val json = JsonObject().apply {
            addProperty("model", provider.modelName)
            add("messages", gson.toJsonTree(listOf(
                mapOf("role" to "user", "content" to prompt)
            )))
        }

        val request = Request.Builder()
            .url("${provider.getBaseUrl()}/chat/completions")
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return Result.failure(Exception("API Error: ${response.code} - $responseBody"))
        }

        val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
        val content = responseJson.getAsJsonArray("choices")
            ?.get(0)?.asJsonObject
            ?.getAsJsonObject("message")
            ?.get("content")?.asString
            ?: return Result.failure(Exception("Invalid response format"))

        return Result.success(content)
    }

    private fun sendGeminiQuery(provider: ApiProvider, prompt: String): Result<String> {
        val json = JsonObject().apply {
            add("contents", gson.toJsonTree(listOf(
                mapOf("parts" to listOf(mapOf("text" to prompt)))
            )))
        }

        val request = Request.Builder()
            .url("${provider.getBaseUrl()}/models/${provider.modelName}:generateContent?key=${provider.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return Result.failure(Exception("API Error: ${response.code} - $responseBody"))
        }

        val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
        val content = responseJson.getAsJsonArray("candidates")
            ?.get(0)?.asJsonObject
            ?.getAsJsonObject("content")
            ?.getAsJsonArray("parts")
            ?.get(0)?.asJsonObject
            ?.get("text")?.asString
            ?: return Result.failure(Exception("Invalid response format"))

        return Result.success(content)
    }
}
