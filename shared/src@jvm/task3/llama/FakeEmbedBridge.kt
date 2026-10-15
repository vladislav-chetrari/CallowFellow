package task3.llama

import kotlinx.coroutines.delay
import task3.data.hashingEmbed

/**
 * Desktop stub: deterministic hash vectors with catalog dimensionality.
 * There is no embedding runtime on the JVM, so desktopApp shows
 * the same indexing UI on garbage but stable vectors.
 */
class FakeEmbedBridge : EmbedBridge {
    private var modelPath: String? = null
    private var modelDim: Int = 768

    override val isReady: Boolean get() = modelPath != null
    override val dim: Int get() = modelDim

    override suspend fun load(modelPath: String, nThreads: Int) {
        delay(300)
        this.modelPath = modelPath
        this.modelDim = 768
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (!isReady) throw IllegalStateException("Embedding-модель не загружена")
        return texts.map {
            delay(10)
            hashingEmbed(it, modelDim)
        }
    }

    override fun close() {
        modelPath = null
    }
}

actual fun createEmbedBridge(): EmbedBridge = FakeEmbedBridge()
