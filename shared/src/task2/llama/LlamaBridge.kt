package task2.llama

import kotlinx.coroutines.flow.Flow

/**
 * Thin port to local inference. Implementation is expect/actual:
 * android — LiteRT-LM, jvm — LiteRT-LM (desktop also computes for real).
 * SOLID (DIP): the domain depends only on this interface.
 *
 * The prompt goes as a (system, user) pair: the runtime itself applies
 * the chat template (LiteRT-LM), legacy ChatML strings (llama.cpp) are gone.
 */
/** Sampling params for LiteRT-LM. Default = former hardcoded (Balanced). */
data class GenParams(
    val temperature: Float = 0.5f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val maxTokens: Int = 512,
) {
    companion object {
        val DEFAULT = GenParams()
    }
}

interface LlamaBridge {
    val isReady: Boolean
    suspend fun load(modelPath: String, nCtx: Int = 2048, nThreads: Int = 4)
    fun generateChat(system: String, user: String, params: GenParams = GenParams.DEFAULT): Flow<String>
    fun cancel()
    fun close()
}

expect fun createLlamaBridge(): LlamaBridge

/**
 * Minimal ChatML template instead of the bundled one.
 * The stock Qwen template breaks on the system message: it does
 * '<|im_start|>system\n' + messages[0].content, while LiteRT passes content
 * as a list of blocks (sequence), not a string — rendering fails with
 * ("tried to use + operator on unsupported types string and sequence").
 * Here content is taken explicitly: string as-is, list by concatenating
 * text blocks. LiteRT roles ("system"/"user"/"assistant") are already ChatML.
 *
 * Two limits of the override-template parser (verified by probing
 * a real .litertlm, see history):
 * - NO whitespace-control dashes ({%-, -}}, {{-, -}}) — the parser
 *   doesn't understand them ("unexpected `-`, expected end of block");
 * - newlines inside string literals — only \\n escapes.
 */
internal const val CHATML_TEMPLATE: String =
    "{% for message in messages %}" +
        "{{ '<|im_start|>' + message.role + '\\n' }}" +
        "{% if message.content is string %}" +
        "{{ message.content }}" +
        "{% else %}" +
        "{% for block in message.content %}{{ block.text }}{% endfor %}" +
        "{% endif %}" +
        "{{ '<|im_end|>\\n' }}" +
        "{% endfor %}" +
        "{% if add_generation_prompt %}" +
        "{{ '<|im_start|>assistant\\n' }}" +
        "{% endif %}"

