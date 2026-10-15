package task2.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.room.RoomDatabase
import task2.data.ChatRepositoryImpl
import task2.data.RoomMemoryStore
import task2.db.ChatDatabase
import task2.db.buildChatDatabase
import task2.domain.ChatRepository
import task2.llama.createLlamaBridge
import task2.presentation.ChatViewModel

/**
 * Manual DI container (no Hilt — KISS for a single screen).
 * The DB is created by the platform entry-point (it owns Context/path),
 * an already-built builder arrives here — everything else is shared.
 */
class AppContainer(
    val modelsDir: String,
    dbBuilder: RoomDatabase.Builder<ChatDatabase>,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val db = buildChatDatabase(dbBuilder)
    val repo: ChatRepository = ChatRepositoryImpl(
        bridge = createLlamaBridge(),
        memory = RoomMemoryStore(db.chatDao()),
        dao = db.chatDao(),
    )
    val chatViewModel = ChatViewModel(repo, scope, modelsDir)
}
