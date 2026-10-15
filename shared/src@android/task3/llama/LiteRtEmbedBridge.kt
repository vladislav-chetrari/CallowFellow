package task3.llama

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Embeddings via the LiteRT-LM EmbeddingEngine (EmbeddingGemma bundle
 * .litertlm, tokenizer inside — no manual BPE).
 * CPU backend, normalize=true (cosine = dot, as the brute-force search expects),
 * 512-token window (a 1000-character chunk fits entirely).
 */
class LiteRtEmbedBridge : EmbedBridge {
    private var engine: EmbeddingEngine? = null
    private var modelDim: Int = 768

    override val isReady: Boolean get() = engine?.isInitialized() == true

    /** Actual model dimensionality (discovered via probe at load time). */
    override val dim: Int get() = modelDim

    override suspend fun load(modelPath: String, nThreads: Int) =
        withContext(Dispatchers.IO) {
            close()
            val e = EmbeddingEngine(
                EmbeddingEngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    maxInputLength = 512,
                ),
            )
            try {
                e.initialize()
            } catch (t: Throwable) {
                runCatching { e.close() }
                throw IllegalStateException("LiteRT не смог открыть $modelPath: ${t.message}")
            }
            // The catalog dim is for the UI label; ground truth is discovered here.
            modelDim = e.computeEmbedding(listOf(InputData.Text("проверка"))).embedding.size
            engine = e
        }

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.IO) {
            val e = engine?.takeIf { it.isInitialized() }
                ?: throw IllegalStateException("Embedding-модель не загружена")
            e.computeEmbeddingBatch(
                texts.map { listOf(InputData.Text(it)) },
                EmbeddingOptions(normalize = true),
            ).map { it.embedding }
        }

    override fun close() {
        runCatching { engine?.close() }
        engine = null
    }
}

actual fun createEmbedBridge(): EmbedBridge = LiteRtEmbedBridge()
