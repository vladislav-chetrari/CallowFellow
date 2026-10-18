package task2.data

import task2.domain.AiModel

/**
 * Local models for LiteRT-LM (.litertlm from litert-community, Apache-2.0,
 * download without login). The runtime itself applies the chat template —
 * PromptBuilder ChatML hacks are no longer needed.
 * Default — Qwen2.5 1.5B Q8 (instruct, handles RAG with citations).
 */
object ModelCatalog {
    val QWEN25_15B_Q8 = AiModel(
        id = "qwen25-1.5b-q8",
        label = "Qwen2.5 1.5B Q8",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        sizeMb = 1524,
        defaultCtx = 4096,
    )
    val QWEN3_06B_INT4 = AiModel(
        id = "qwen3-0.6b-int4",
        label = "Qwen3 0.6B",
        fileName = "qwen3_0.6b_q4_block32_ekv1280.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_q4_block32_ekv1280.litertlm",
        sizeMb = 331,
        defaultCtx = 1280,
    )
    // Light tier: fast on low-RAM devices (fills the gap under 1 GB).
    val LFM25_12B_INT4 = AiModel(
        id = "lfm25-1.2b-int4",
        label = "LFM2.5 1.2B",
        fileName = "LFM2.5-1.2B-Instruct_int4.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/resolve/main/LFM2.5-1.2B-Instruct_int4.litertlm",
        sizeMb = 736,
        defaultCtx = 4096,
    )
    // Sweet spot: Google's mobile-first model, best-supported on Android (up to 32k ctx).
    val GEMMA4_E2B = AiModel(
        id = "gemma4-e2b",
        label = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sizeMb = 2588,
        defaultCtx = 4096,
    )
    // Quality tier: strong reasoning, for 8+ GB devices (bundle ctx is 2048).
    val QWEN3_4B_INT4 = AiModel(
        id = "qwen3-4b-int4",
        label = "Qwen3 4B",
        fileName = "qwen3_4b_mixed_int4.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-4B/resolve/main/qwen3_4b_mixed_int4.litertlm",
        sizeMb = 2659,
        defaultCtx = 2048,
    )

    val ALL: List<AiModel> =
        listOf(QWEN3_06B_INT4, LFM25_12B_INT4, QWEN25_15B_Q8, GEMMA4_E2B, QWEN3_4B_INT4)
    val DEFAULT: AiModel = QWEN25_15B_Q8

    fun resolve(id: String?): AiModel = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
