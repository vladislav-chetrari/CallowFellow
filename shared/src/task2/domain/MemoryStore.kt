package task2.domain

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** One memorized fact about the interlocutor (long-term memory). */
@Serializable
data class ConversationFact(
    val text: String,
    val createdAt: Long,
)

/**
 * Fact storage. SSOT for facts is facts.
 * The implementation persists to JSON, surviving app restarts.
 */
interface MemoryStore {
    val facts: StateFlow<List<ConversationFact>>
    suspend fun load()
    suspend fun addFacts(texts: List<String>)
    suspend fun clear()
}
