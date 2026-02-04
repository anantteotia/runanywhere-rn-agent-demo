package com.runanywhere.agent.kernel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GPTClient(
    private val apiKeyProvider: () -> String?,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "GPTClient"
        private const val API_URL = "https://api.openai.com/v1/chat/completions"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = !apiKeyProvider().isNullOrBlank()

    suspend fun generatePlan(task: String): PlanResult? {
        val content = request(
            systemPrompt = "You are an expert Android planning assistant. Always respond with valid minified JSON following this schema: ${SystemPrompts.PLANNING_SCHEMA}",
            userPrompt = SystemPrompts.buildPlanningPrompt(task),
            maxTokens = 256
        ) ?: return null

        return try {
            parsePlan(content)
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse plan: ${e.message}")
            null
        }
    }

    suspend fun generateAction(prompt: String): String? {
        return request(
            systemPrompt = SystemPrompts.SYSTEM_PROMPT,
            userPrompt = prompt,
            maxTokens = 256
        )
    }

    private suspend fun request(systemPrompt: String, userPrompt: String, maxTokens: Int): String? {
        val apiKey = apiKeyProvider()?.takeIf { it.isNotBlank() } ?: return null

        val payload = JSONObject().apply {
            put("model", "gpt-4o")
            put("temperature", 0)
            put("max_tokens", maxTokens)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }

        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", JSON_MEDIA.toString())
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        return try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            val body = response.body?.string()
            if (!response.isSuccessful) {
                val err = body ?: response.message
                Log.e(TAG, "GPT call failed: ${response.code} $err")
                onLog("GPT-4o error ${response.code}")
                null
            } else {
                body?.let { extractContent(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GPT request error: ${e.message}", e)
            onLog("GPT-4o request failed: ${e.message}")
            null
        }
    }

    private fun extractContent(body: String): String? {
        val json = JSONObject(body)
        val choices = json.optJSONArray("choices") ?: return null
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
        val arrayContent = message.optJSONArray("content")
        return when {
            arrayContent != null -> buildString {
                for (i in 0 until arrayContent.length()) {
                    val part = arrayContent.optJSONObject(i)
                    if (part != null) {
                        append(part.optString("text"))
                    } else {
                        append(arrayContent.optString(i))
                    }
                }
            }.trim()
            else -> message.optString("content").trim()
        }
    }

    private fun parsePlan(text: String): PlanResult {
        val cleaned = text
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val obj = JSONObject(cleaned)
        val stepsArray = obj.optJSONArray("steps") ?: JSONArray()
        val steps = mutableListOf<String>()
        for (i in 0 until stepsArray.length()) {
            steps.add(stepsArray.optString(i))
        }
        val successCriteria = obj.optString("success_criteria").takeIf { it.isNotEmpty() }
        return PlanResult(steps, successCriteria)
    }
}

data class PlanResult(
    val steps: List<String>,
    val successCriteria: String?
)
