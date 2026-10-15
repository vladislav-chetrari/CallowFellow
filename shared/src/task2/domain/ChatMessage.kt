package task2.domain

import kotlinx.serialization.Serializable

/** One chat turn. SSOT is stored in ChatRepository.messages. */
@Serializable
data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
) {
    @Serializable
    enum class Role { USER, ASSISTANT, SYSTEM }
}
