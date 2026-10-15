package task2.domain

/**
 * Hardcoded catalog of .litertlm models for LiteRT-LM (no arbitrary URLs — KISS).
 * Default for the task: qwen25-1.5b-q8.
 */
data class AiModel(
    val id: String,
    val label: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeMb: Int,
    val sha256: String = "",
    val defaultCtx: Int = 2048,
)
