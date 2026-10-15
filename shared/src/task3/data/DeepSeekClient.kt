package task3.data

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

sealed interface DeepSeekResult {
    data class Ok(val text: String) : DeepSeekResult
    data class HttpError(val code: Int, val detail: String) : DeepSeekResult
    data class NetworkError(val detail: String) : DeepSeekResult
}

/**
 * DeepSeek via Koog (ai.koog:prompt-executor-deepseek-client).
 * Prompt is built with the Koog DSL, params (temperature) travel inside the Prompt,
 * transport/serialization/timeouts are on Koog (previously bare
 * HttpURLConnection + manual JSON).
 *
 * Client creation is inline in common code with an explicit factory
 * (KtorKoogHttpClient.Factory + CIO): factory auto-discovery via
 * ServiceLoader does not work in the APK, so explicit is deterministic.
 * Contract for RagRepository is unchanged.
 */
object DeepSeekClient {
    const val MODEL = "deepseek-chat"

    /**
     * deepseek-chat is missing from the beta client's preset DeepSeekModels
     * (only V4 flagships there), so we describe it ourselves. Capabilities —
     * like V4Flash: the executor needs temperature/tools for validation.
     */
    private val deepSeekChat = LLModel(
        provider = LLMProvider.DeepSeek,
        id = MODEL,
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Temperature,
            LLMCapability.Tools,
            LLMCapability.ToolChoice,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.MultipleChoices,
            LLMCapability.Thinking,
        ),
    )

    // One client per key for the whole app lifetime (creating a Ktor client
    // per request wastes threads/sockets).
    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cached: LLMClient? = null

    private fun executor(apiKey: String): LLMClient {
        val c = cached
        if (c != null && cachedKey == apiKey) return c
        // Pass the factory explicitly: ServiceLoader auto-discovery is unreliable in the APK
        // (META-INF/services gets stripped by packaging). CIO engine is explicit too,
        // no auto-detection. withSse=false: streaming is not used.
        return DeepSeekLLMClient(
            apiKey = apiKey,
            httpClientFactory = KtorKoogHttpClient.Factory(
                baseClient = HttpClient(CIO),
                withSse = false,
            ),
        ).also {
            cached = it
            cachedKey = apiKey
        }
    }

    suspend fun complete(
        system: String,
        user: String,
        history: List<Pair<String, String>> = emptyList(),
        apiKey: String?,
    ): DeepSeekResult {
        if (apiKey.isNullOrBlank()) return DeepSeekResult.NetworkError("Нет API-ключа DeepSeek")
        return try {
            val response = executor(apiKey).execute(
                prompt("rag-answer", DeepSeekParams(temperature = 0.3)) {
                    system(system)
                    history.takeLast(12).forEach { (role, content) ->
                        if (role == "assistant") assistant(content) else user(content)
                    }
                    user(user)
                },
                deepSeekChat,
                emptyList(),
            )
            val text = response.textContent().trim()
            if (text.isEmpty()) DeepSeekResult.NetworkError("Пустой ответ модели")
            else DeepSeekResult.Ok(text)
        } catch (e: Exception) {
            DeepSeekResult.NetworkError(e.message?.takeIf { it.isNotBlank() } ?: "запрос не удался")
        }
    }
}
