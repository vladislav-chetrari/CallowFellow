package task2.llama

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Test stub for the generative bridge (lives in the test source set:
 * main now uses the real LiteRT-LM on all platforms).
 */
class FakeLlamaBridge : LlamaBridge {
    private var modelPath: String? = null

    override val isReady: Boolean get() = modelPath != null

    override suspend fun load(modelPath: String, nCtx: Int, nThreads: Int) {
        this.modelPath = modelPath
    }

    override fun generateChat(system: String, user: String, params: GenParams): Flow<String> = flow {
        emit("fake-reply")
    }

    override fun cancel() {}
    override fun close() {
        modelPath = null
    }
}
