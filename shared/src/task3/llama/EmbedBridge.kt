package task3.llama

/**
 * Thin port to the embedding runtime. Android — LiteRT-LM EmbeddingEngine
 * (EmbeddingGemma bundle), JVM desktop — Fake (hash vectors for preview).
 * Texts already carry prefixes when the model needs them (EmbeddingGemma — none).
 */
interface EmbedBridge {
    val isReady: Boolean

    /** Actual model dimension (checked after load). */
    val dim: Int

    suspend fun load(modelPath: String, nThreads: Int = 4)

    /** Texts already carry prefixes (search_document/search_query) — set by the catalog. */
    suspend fun embed(texts: List<String>): List<FloatArray>

    fun close()
}

expect fun createEmbedBridge(): EmbedBridge
