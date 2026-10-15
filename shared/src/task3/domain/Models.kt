package task3.domain

/** LLM choice: one local .litertlm from the catalog or cloud DeepSeek. */
sealed interface LlmChoice {
    data class Local(val id: String) : LlmChoice
    data object DeepSeek : LlmChoice

    companion object {
        fun fromStored(value: String?): LlmChoice? = when {
            value.isNullOrBlank() -> null
            value == "deepseek" -> DeepSeek
            else -> Local(value)
        }

        fun LlmChoice?.toStored(): String = when (this) {
            null -> ""
            DeepSeek -> "deepseek"
            is Local -> id
        }
    }
}

/** Knowledge-base document for the UI. */
data class DocInfo(
    val source: String,
    val title: String,
    val chunks: Int,
    val chars: Long,
    val active: Boolean = true,
    val indexing: Boolean = false,
    val error: String? = null,
)

/**
 * Task 4 generation preset: sampler + prompt style. Default — Balanced.
 * A single sampler is barely visible on a small model (especially on the short
 * "not in the documents" fallback), so the preset additionally mixes
 * styleHint into the system prompt — the difference is visible at once.
 */
enum class GenPreset(
    val id: String,
    val label: String,
    val description: String,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val styleHint: String,
) {
    PRECISE(
        id = "precise",
        label = "Точный",
        description = "Факты по документам, минимум выдумок",
        temperature = 0.2f, topK = 20, topP = 0.8f,
        styleHint = "Стиль: кратко, 1-3 предложения, только проверенные факты.",
    ),
    BALANCED(
        id = "balanced",
        label = "Баланс",
        description = "Живо и по делу, дефолт",
        temperature = 0.5f, topK = 40, topP = 0.9f,
        styleHint = "Стиль: живо и по делу, 2-4 предложения.",
    ),
    CREATIVE(
        id = "creative",
        label = "Живой",
        description = "Разговорный, свободнее формулировки",
        temperature = 0.8f, topK = 60, topP = 0.95f,
        styleHint = "Стиль: живо и развёрнуто, 3-6 предложений, можешь дополнять общими знаниями, но факты из базы помечай ссылками.",
    );

    fun toGenParams(): task2.llama.GenParams = task2.llama.GenParams(
        temperature = temperature, topK = topK, topP = topP,
    )

    companion object {
        val DEFAULT: GenPreset = BALANCED
        fun fromId(id: String?): GenPreset = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Found chunk + cosine similarity. */
data class ScoredChunk(
    val title: String,
    val section: String,
    val source: String,
    val text: String,
    val score: Float,
)
