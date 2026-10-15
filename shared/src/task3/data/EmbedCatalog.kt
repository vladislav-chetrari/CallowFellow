package task3.data

/**
 * Local embedding model for the LiteRT-LM EmbeddingEngine:
 * EmbeddingGemma 270M (.litertlm bundle, tokenizer inside),
 * multilingual (Russian Wikipedia is its profile), 165 MB, 768d output.
 * No query/document prefixes needed — the bridge passes texts through as-is.
 */
data class EmbedModel(
    val id: String,
    val label: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeMb: Int,
    /** Vector dimensionality (verified by a probe after loading). */
    val dim: Int,
    /** Prefix for documents (not needed for EmbeddingGemma). */
    val docPrefix: String = "",
    /** Prefix for the search query. */
    val queryPrefix: String = "",
)

object EmbedCatalog {
    val EMBEDGEMMA_270M = EmbedModel(
        id = "embedgemma-270m",
        label = "EmbeddingGemma 270M (~165 МБ)",
        fileName = "embeddinggemma-2-text-270m.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm",
        sizeMb = 157,
        dim = 768,
    )

    val ALL: List<EmbedModel> = listOf(EMBEDGEMMA_270M)
    val DEFAULT: EmbedModel = EMBEDGEMMA_270M

    fun resolve(id: String?): EmbedModel? = ALL.firstOrNull { it.id == id }
}
