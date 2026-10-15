package task2.llama

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Inference via LiteRT-LM (Google AI Edge): an Engine over a .litertlm model,
 * a fresh Conversation per request (history is already embedded in the user text),
 * deltas from sendMessageAsync are streamed to the UI as-is.
 * Backend: GPU first (on flagships prefill is several times faster). The GPU engine
 * may initialize fine but fail only on the first generation
 * ("Can not find OpenCL library"), so at load time we run a trial
 * micro-generation; if it fails we recreate the engine on CPU while the
 * loading status is still showing, not in the middle of a chat. CPU works everywhere.
 * Custom template (CHATML_TEMPLATE): the bundled one crashes on
 * system messages, see the comment on the constant.
 */
class LiteRtLlamaBridge : LlamaBridge {
    private var engine: Engine? = null

    @Volatile
    private var active: Conversation? = null

    override val isReady: Boolean get() = engine?.isInitialized() == true

    override suspend fun load(modelPath: String, nCtx: Int, nThreads: Int) =
        withContext(Dispatchers.IO) {
            close()
            // GPU is several times faster but not available everywhere — try it first
            // with a full-path check (initialization + micro-generation).
            // On failure fall back to CPU (always works).
            val gpuOk = tryOpen(modelPath, Backend.GPU(), probe = true)
            if (!gpuOk) {
                tryOpen(
                    modelPath,
                    Backend.CPU(threadCount = nThreads.takeIf { it > 0 }),
                    probe = false,
                )
            }
        }

    /**
     * Open the engine; true means fully working. `probe=true` additionally
     * runs a micro-generation: GPU initialization can be lazy and die
     * only on the first request — catch that here, not in the chat.
     * An exception escapes only if the CPU variant failed.
     */
    private fun tryOpen(modelPath: String, backend: Backend, probe: Boolean): Boolean {
        val e = Engine(EngineConfig(modelPath = modelPath, backend = backend))
        try {
            // Loading takes up to ~10 seconds on large models — LoadingModel status is already showing.
            e.initialize()
            if (probe) {
                e.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.5),
                        maxOutputToken = 16,
                        chatTemplate = CHATML_TEMPLATE,
                    ),
                ).use { conv ->
                    conv.sendMessage(Message.user("hi"))
                }
            }
        } catch (t: Throwable) {
            runCatching { e.close() }
            if (backend is Backend.CPU) {
                throw IllegalStateException("LiteRT не смог открыть $modelPath: ${t.message}")
            }
            return false
        }
        engine = e
        return true
    }

    override fun generateChat(system: String, user: String, params: GenParams): Flow<String> = flow {
        val e = engine?.takeIf { it.isInitialized() }
            ?: throw IllegalStateException("Модель не загружена")
        val conv = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = params.topK, topP = params.topP.toDouble(), temperature = params.temperature.toDouble()),
                maxOutputToken = params.maxTokens,
                chatTemplate = CHATML_TEMPLATE,
            ),
        )
        active = conv
        try {
            conv.sendMessageAsync(Message.user(user)).collect { msg ->
                currentCoroutineContext().ensureActive()
                emit(msg.toString())
            }
        } finally {
            if (active === conv) active = null
            runCatching { conv.close() }
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        // Interrupt native decoding: close the active Conversation.
        runCatching { active?.close() }
        active = null
    }

    override fun close() {
        runCatching { active?.close() }
        active = null
        runCatching { engine?.close() }
        engine = null
    }
}

actual fun createLlamaBridge(): LlamaBridge = LiteRtLlamaBridge()
