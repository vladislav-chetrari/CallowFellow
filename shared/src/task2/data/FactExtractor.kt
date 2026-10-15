package task2.data

/**
 * Deterministic fact extraction (no LLM).
 *
 * Why not LLM: on-device checks showed that small models
 * ignore meta-instructions — asked to "list facts or answer NO"
 * it replies "I am a helpful assistant". For such a small model
 * rules are more reliable: instant, free, predictable, testable.
 * Seam for an LLM extractor is kept: MemoryStore knows nothing about the source.
 *
 * Catches only explicit phrasings (RU+EN): name, preferences, "remember".
 */
object FactExtractor {
    private val NAME_TRIGGERS = listOf(
        Regex("меня зовут\\s+([^\\s,.!?:;]+)", RegexOption.IGNORE_CASE),
        Regex("мо[её] имя\\s+([^\\s,.!?:;]+)", RegexOption.IGNORE_CASE),
        Regex("my name is\\s+([^\\s,.!?:;]+)", RegexOption.IGNORE_CASE),
        // "I'm Vlad" — capitalized only, plus a stop-list against "I'm fine".
        Regex("\\bi['’]?m\\s+([A-ZА-ЯЁ][^\\s,.!?:;]*)", ),
        Regex("\\bi am\\s+([A-ZА-ЯЁ][^\\s,.!?:;]*)", ),
    )
    private val NAME_STOP = setOf(
        "fine", "ok", "okay", "good", "well", "here", "there",
        "sorry", "tired", "busy", "ready", "happy",
    )
    /** Capturing from a single pronoun ("it") is noise, not a fact. */
    private val PRONOUN_STOP = setOf(
        "it", "this", "that", "these", "those", "them", "him", "her",
        "me", "you", "us", "myself", "yourself",
        "это", "этого", "этому", "этим", "тот", "та", "то", "тебя", "меня",
    )
    private val LIKE_TRIGGERS = listOf(
        Regex("я люблю\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("мне нравится\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("я обожаю\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("i love\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("i like\\s+(.+)", RegexOption.IGNORE_CASE),
    )
    private val REMEMBER_TRIGGERS = listOf(
        Regex("запомни[:\\-,]?\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("remember[:\\-,]?\\s+(.+)", RegexOption.IGNORE_CASE),
    )

    /** Pure function — covered by unit tests. */
    fun extractFrom(text: String, maxCount: Int = 3, maxLen: Int = 120): List<String> {
        val clean = text.trim().take(300)
        if (clean.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        for (re in NAME_TRIGGERS) {
            val name = re.find(clean)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', ',', '!', '?')
                ?: continue
            if (name.length < 2 || name.lowercase() in NAME_STOP) continue
            out += "Имя пользователя — $name"
        }
        for (re in LIKE_TRIGGERS) {
            val what = re.find(clean)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', ',', '!', '?')
                ?: continue
            if (what.length < 2 || what.lowercase() in PRONOUN_STOP) continue
            out += "Нравится: ${what.take(maxLen)}"
        }
        for (re in REMEMBER_TRIGGERS) {
            val what = re.find(clean)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', ',', '!', '?')
                ?: continue
            if (what.length < 2 || what.lowercase() in PRONOUN_STOP) continue
            out += what.take(maxLen)
        }
        return out.distinct().take(maxCount)
    }
}
