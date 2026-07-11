package app.reverb.autolearn

import app.reverb.core.common.ReverbLog
import app.reverb.data.LlmConfig
import app.reverb.data.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * LLM client contract — used by the [SiteAnalyzer] to generate CSS selectors from HTML.
 *
 * Implementations:
 * - [GeminiLlmClient] — Google Gemini API (free tier: Gemini 2.0 Flash, 15 RPM)
 * - [OpenAiCompatibleLlmClient] — any OpenAI-compatible API (GLM, OpenRouter, Ollama, etc.)
 *
 * Rate limiting is built in: the client enforces a minimum interval between calls
 * to respect free-tier limits (default: 4s between calls = 15 RPM max).
 *
 * Reference: PLAN.md §23.3 + user request for GLM/Gemini with rate limit handling.
 */
interface LlmClient {
    /** Complete a prompt with a system message + user message. Returns the text response. */
    suspend fun complete(systemPrompt: String, userPrompt: String): String

    /** Whether this client is configured (has valid API key + endpoint). */
    val isConfigured: Boolean

    /** Human-readable name for logging + UI. */
    val name: String
}

/**
 * Google Gemini API client.
 *
 * Free tier: Gemini 2.0 Flash — 15 RPM, 1500 RPD, 1M TPM.
 * Endpoint: https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 *
 * @param apiKey Google AI Studio API key (user enters in settings)
 * @param model Model name (default: gemini-2.0-flash)
 * @param minIntervalMs Min time between calls (default: 4500ms = ~13 RPM, under the 15 RPM limit)
 */
class GeminiLlmClient(
    private val httpClient: OkHttpClient,
    private val apiKey: String,
    private val model: String = "gemini-2.0-flash",
    private val minIntervalMs: Long = 4500L,
) : LlmClient {

    override val isConfigured: Boolean = apiKey.isNotBlank()
    override val name: String = "Gemini ($model)"

    private val rateLimitMutex = Mutex()
    private var lastCallTime = 0L
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        enforceRateLimit()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val body = buildJsonObject {
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", userPrompt) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.1)
                put("maxOutputTokens", 2048)
                put("responseMimeType", "application/json")
            })
        }.toString()

        ReverbLog.d("LlmClient", "Gemini request: ${userPrompt.length} chars → $model")

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string().orEmpty()
                    ReverbLog.e("LlmClient", "Gemini API error ${resp.code}: ${errorBody.take(200)}")
                    throw RuntimeException("Gemini API error ${resp.code}: ${errorBody.take(200)}")
                }
                val responseStr = resp.body?.string().orEmpty()
                ReverbLog.d("LlmClient", "Gemini raw response: ${responseStr.take(200)}")
                val responseJson = json.parseToJsonElement(responseStr).jsonObject
                val candidates = responseJson["candidates"]?.jsonArray
                val firstCandidate = candidates?.firstOrNull()?.jsonObject
                val content = firstCandidate?.get("content")?.jsonObject
                val parts = content?.get("parts")?.jsonArray
                val firstPart = parts?.firstOrNull()?.jsonObject
                // Use .jsonPrimitive.content to properly unescape the JSON string
                // (previously .toString()?.trim('"') left literal \" and \n in the text)
                val text = firstPart?.get("text")?.jsonPrimitive?.content ?: ""
                ReverbLog.d("LlmClient", "Gemini response: ${text.length} chars — ${text.take(100)}")
                text
            }
        } catch (e: Exception) {
            ReverbLog.e("LlmClient", "Gemini request failed: ${e.message}", e)
            throw e
        }
    }

    private suspend fun enforceRateLimit() {
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastCallTime
            if (elapsed < minIntervalMs && lastCallTime > 0) {
                val wait = minIntervalMs - elapsed
                ReverbLog.d("LlmClient", "Rate limit: waiting ${wait}ms before next Gemini call")
                kotlinx.coroutines.delay(wait)
            }
            lastCallTime = System.currentTimeMillis()
        }
    }
}

/**
 * OpenAI-compatible LLM client — works with GLM, OpenAI, OpenRouter, Ollama, vLLM, etc.
 *
 * GLM (ZAI) endpoint: https://api.z.ai/api/paas/v4/chat/completions
 * Model: glm-4-flash (free) or glm-4
 *
 * @param endpoint API base URL (e.g. "https://api.z.ai/api/paas/v4/chat/completions")
 * @param apiKey API key
 * @param model Model name (e.g. "glm-4-flash")
 * @param minIntervalMs Min time between calls (default: 4500ms = ~13 RPM)
 */
class OpenAiCompatibleLlmClient(
    private val httpClient: OkHttpClient,
    private val endpoint: String,
    private val apiKey: String,
    private val model: String = "glm-4-flash",
    private val minIntervalMs: Long = 4500L,
) : LlmClient {

    override val isConfigured: Boolean = apiKey.isNotBlank() && endpoint.isNotBlank()
    override val name: String = "OpenAI-compatible ($model)"

    private val rateLimitMutex = Mutex()
    private var lastCallTime = 0L
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        enforceRateLimit()

        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
            put("temperature", 0.1)
            put("max_tokens", 2048)
        }.toString()

        ReverbLog.d("LlmClient", "OpenAI-compatible request: ${userPrompt.length} chars → $model at $endpoint")

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string().orEmpty()
                    ReverbLog.e("LlmClient", "API error ${resp.code}: ${errorBody.take(200)}")
                    throw RuntimeException("API error ${resp.code}: ${errorBody.take(200)}")
                }
                val responseStr = resp.body?.string().orEmpty()
                ReverbLog.d("LlmClient", "OpenAI-compatible raw response: ${responseStr.take(200)}")
                val responseJson = json.parseToJsonElement(responseStr).jsonObject
                val choices = responseJson["choices"]?.jsonArray
                val firstChoice = choices?.firstOrNull()?.jsonObject
                val message = firstChoice?.get("message")?.jsonObject
                // Use .jsonPrimitive.content to properly unescape the JSON string
                val content = message?.get("content")?.jsonPrimitive?.content ?: ""
                ReverbLog.d("LlmClient", "OpenAI-compatible response: ${content.length} chars — ${content.take(100)}")
                content
            }
        } catch (e: Exception) {
            ReverbLog.e("LlmClient", "Request failed: ${e.message}", e)
            throw e
        }
    }

    private suspend fun enforceRateLimit() {
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastCallTime
            if (elapsed < minIntervalMs && lastCallTime > 0) {
                val wait = minIntervalMs - elapsed
                ReverbLog.d("LlmClient", "Rate limit: waiting ${wait}ms before next call")
                kotlinx.coroutines.delay(wait)
            }
            lastCallTime = System.currentTimeMillis()
        }
    }
}

/** Factory — creates the right LLM client from settings. */
object LlmClientFactory {
    fun create(
        httpClient: OkHttpClient,
        config: LlmConfig,
    ): LlmClient {
        return when (config.provider) {
            LlmProvider.NONE -> NoopLlmClient
            LlmProvider.GEMINI -> GeminiLlmClient(
                httpClient = httpClient,
                apiKey = config.apiKey,
                model = config.model.ifBlank { "gemini-2.0-flash" },
            )
            LlmProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleLlmClient(
                httpClient = httpClient,
                endpoint = config.endpoint.ifBlank { "https://api.z.ai/api/paas/v4/chat/completions" },
                apiKey = config.apiKey,
                model = config.model.ifBlank { "glm-4-flash" },
            )
        }
    }
}

/** No-op client for when LLM is not configured. */
object NoopLlmClient : LlmClient {
    override val isConfigured: Boolean = false
    override val name: String = "None"
    override suspend fun complete(systemPrompt: String, userPrompt: String): String =
        throw IllegalStateException("No LLM configured. Go to Settings → LLM to set up Gemini or GLM.")
}
