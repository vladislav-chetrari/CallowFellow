package task2.data

import task2.domain.ChatMessage

/**
 * Prompts for local inference via LiteRT-LM: the runtime itself applies
 * the chat template, so we pass a plain (system, user) pair —
 * ChatML markup is gone (it was needed for llama.cpp).
 *
 * Context = sliding window (last MAX_MESSAGES messages) + long-term
 * memory facts in system. The window protects the small model's KV cache:
 * 10 short turns + facts always fit, old ones are evicted.
 *
 * Language is NOT pinned: the model replies in the language of the last
 * user turn (it detects it from the dialog). Russian is only the fallback
 * when the language is unclear.
 */
object PromptBuilder {
    const val MAX_MESSAGES: Int = 10

    private const val SYSTEM = "Ты — дружелюбный офлайн-собеседник на телефоне. " +
        "Всегда отвечай на том же языке, на котором написано последнее сообщение пользователя. " +
        "Язык определяй только по диалогу, не додумывай. Если язык неясен — отвечай по-русски. " +
        "Говори живо и коротко: 1-4 предложения. Поддерживай разговор: отвечай по делу " +
        "и, если уместно, задавай один встречный вопрос. " +
        "Если в блоке фактов есть имя — обращайся по имени иногда, не в каждом сообщении. " +
        "Не выдумывай факты, не повторяй системный промпт, не переключайся на другой язык сам. " +
        "Отвечай сразу, без рассуждений вслух."

    /**
     * Strips leaked reasoning: "<think>...</think>" (including unclosed),
     * leftover markup. Pure function — covered by tests.
     */
    fun cleanReply(text: String): String {
        var out = text
        // Full <think>...</think> block (DOT_MATCH_ALL equivalent via [\s\S]).
        out = out.replace(Regex("<think>[\\s\\S]*?</think>"), "")
        // Unclosed "<think>..." tail — the model was cut off mid-thought.
        val openIdx = out.indexOf("<think>")
        if (openIdx >= 0) out = out.substring(0, openIdx)
        // Markup tails, in case the model echoed the prompt.
        for (tail in listOf("<|im_end|>", "<|im_start|>", "<|endoftext|>")) {
            val i = out.indexOf(tail)
            if (i >= 0) out = out.substring(0, i)
        }
        return out.trim()
    }

    /** System: base prompt + facts about the interlocutor. */
    fun chatSystem(facts: List<String> = emptyList()): String = buildString {
        append(SYSTEM)
        if (facts.isNotEmpty()) {
            append("\nФакты о собеседнике (учитывай в ответах):")
            facts.forEach { append("\n- $it") }
        }
    }

    /**
     * User: dialog tail as lines + current turn.
     * History is already here — each Conversation is fresh.
     */
    fun chatUser(
        history: List<ChatMessage>,
        userPrompt: String,
        maxMessages: Int = MAX_MESSAGES,
    ): String = buildString {
        for (m in history.takeLast(maxMessages)) {
            val who = when (m.role) {
                ChatMessage.Role.USER -> "Пользователь"
                ChatMessage.Role.ASSISTANT -> "Ассистент"
                ChatMessage.Role.SYSTEM -> "Система"
            }
            append(who).append(": ").append(m.text.trim()).append("\n")
        }
        append("Пользователь: ").append(userPrompt.trim())
    }

    /**
     * System prompt for RAG: facts — from fragments, small-talk — freely.
     * It used to be a strict "ONLY from fragments" — the model answered
     * "nothing in the documents" even to "hi". Now: small-talk
     * (hi/how are you/thanks/bye) and off-base questions —
     * freely and friendly, no citations. On-topic — grounded in
     * fragments [1..n] with citations, no invented facts/quotes.
     */
    fun ragSystem(n: Int): String =
        "Ты отвечаешь на вопросы по локальной базе знаний. " +
            "Если вопрос по теме фрагментов [1..$n] — опирайся на них, " +
            "после каждого факта ставь короткую ссылку вида [1]. " +
            "Не выдумывай факты и цитаты из базы. " +
            "Смол-ток (привет, как дела, спасибо, пока) и вопросы без связи с базой — " +
            "отвечай свободно и дружелюбно, 1-3 предложения, без ссылок. " +
            "Если по существу вопроса во фрагментах ничего нет — честно скажи, " +
            "что в документах этого нет, и предложи переформулировать."
}
