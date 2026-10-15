package task3.data

/**
 * Rule-based intent router without LLM (Phase 2).
 * A tiny on-device model is an expensive and unreliable classifier,
 * rules are enough: small-talk is answered freely without retrieval
 * (saves an EmbeddingGemma call + avoids feeding junk context).
 */
object QueryRouter {
    private val SMALL_TALK = listOf(
        Regex("^\\s*привет[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*здравствуй(те)?[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*добрый (день|вечер|утро)[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*как дела[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*как ты[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*спасибо[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*благодарю[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*пока[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*до свидания[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*hi[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*hello[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*hey[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*thanks[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*thank you[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*bye[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*good (morning|afternoon|evening|day)[!?.\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*how are you[!?.\\s]*$", RegexOption.IGNORE_CASE),
    )

    /** Pure function — testable without the platform. */
    fun isSmallTalk(query: String): Boolean {
        val clean = query.trim().take(200)
        if (clean.isEmpty()) return false
        return SMALL_TALK.any { it.matches(clean) }
    }
}
