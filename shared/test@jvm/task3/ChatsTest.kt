package task3

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import task2.data.ModelCatalog
import task2.llama.FakeLlamaBridge
import task3.data.RagRepository
import task3.db.MIGRATION_1_2
import task3.db.RagDatabase
import task3.domain.LlmChoice
import task3.llama.FakeEmbedBridge

/**
 * Schema v1 of rag.db — messages without chatId, no chats table.
 * All v1 tables are declared: Room validates the whole schema after a migration,
 * so a one-table stand-in would fail validation. KSP generates the Impl only for
 * this test DB; production code never sees it.
 */
@Entity(tableName = "chunks")
data class ChunkV1(
    @PrimaryKey val id: String,
    val source: String,
    val title: String,
    val section: String,
    val ord: Int,
    val text: String,
    val dim: Int,
    val embedding: ByteArray,
)

@Entity(tableName = "documents")
data class DocumentV1(
    @PrimaryKey val source: String,
    val title: String,
    val status: String,
    val error: String = "",
    val active: Boolean = true,
    val updatedAt: Long,
)

@Entity(tableName = "messages")
data class RagMessageV1(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val createdAt: Long,
)

@Entity(tableName = "settings")
data class SettingV1(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface RagDaoV1 {
    @Insert
    suspend fun insertMessage(msg: RagMessageV1): Long

    @Insert
    suspend fun insertDoc(doc: DocumentV1)

    @Upsert
    suspend fun setSetting(item: SettingV1)
}

@Database(
    entities = [ChunkV1::class, DocumentV1::class, RagMessageV1::class, SettingV1::class],
    version = 1,
)
abstract class RagDatabaseV1 : RoomDatabase() {
    abstract fun dao(): RagDaoV1
}

/** Several chats: migration of the old single history, isolation, deletion, titles. */
class ChatsTest {
    private fun tmpDir(): File =
        File(System.getProperty("java.io.tmpdir"), "chats-test-${System.nanoTime()}").apply { mkdirs() }

    /** Real SQLite in a temp dir + a fake LLM selected so send() actually generates. */
    private suspend fun repo(): Pair<RagRepository, RagDatabase> {
        val dir = tmpDir()
        val db = Room.databaseBuilder<RagDatabase>(
            name = File(dir, "rag.db").absolutePath,
        ).setDriver(BundledSQLiteDriver()).build()
        val modelsDir = File(dir, "models").apply { mkdirs() }
        val r = RagRepository(
            genBridge = FakeLlamaBridge(),
            embBridge = FakeEmbedBridge(),
            dao = db.ragDao(),
            modelsDir = modelsDir.absolutePath,
            scope = CoroutineScope(Dispatchers.Default),
        )
        r.init()
        val m = ModelCatalog.QWEN3_06B_INT4
        File(modelsDir, "llm/${m.fileName}").apply { parentFile.mkdirs(); writeText("fake") }
        r.selectLlm(LlmChoice.Local(m.id))
        return r to db
    }

    @Test
    fun migrate1to2KeepsHistoryInFirstChat() = runBlocking {
        val path = File(tmpDir(), "rag.db").absolutePath
        val v1 = Room.databaseBuilder<RagDatabaseV1>(name = path)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            v1.dao().insertMessage(RagMessageV1(role = "USER", text = "привет", createdAt = 1))
            v1.dao().insertMessage(RagMessageV1(role = "ASSISTANT", text = "здрасте", createdAt = 2))
            v1.dao().insertDoc(
                DocumentV1(source = "wiki:Ворона", title = "Ворона", status = "ready", updatedAt = 3),
            )
            v1.dao().setSetting(SettingV1("llm", "qwen3-0.6b-int4"))
        } finally {
            v1.close()
        }
        val v2 = Room.databaseBuilder<RagDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(MIGRATION_1_2)
            .build()
        try {
            val dao = v2.ragDao()
            val chats = dao.allChats()
            assertEquals(1, chats.size, "v1-история должна попасть в один чат")
            assertEquals(listOf("привет", "здрасте"), dao.messagesOf(chats[0].id).map { it.text })
            val rows = dao.chatRows()
            assertEquals(2, rows[0].messageCount)
            assertEquals("здрасте", rows[0].lastText)
            assertEquals("qwen3-0.6b-int4", dao.getSetting("llm"), "настройки не должны теряться")
            assertEquals(1, dao.allDocs().size, "документы не должны теряться")
        } finally {
            v2.close()
        }
    }

    @Test
    fun messagesAreIsolatedPerChat() = runBlocking {
        val (r, db) = repo()
        try {
            val first = r.activeChatId.value
            r.send("первый вопрос")
            val second = r.createChat()
            r.send("второй вопрос")

            assertEquals(2, r.chats.value.size, "должно быть два чата")
            assertEquals(second, r.activeChatId.value)
            assertEquals(listOf("второй вопрос", "fake-reply"), r.messages.value.map { it.text })
            val secondIds = r.messages.value.map { it.id }

            r.selectChat(first)
            assertEquals(listOf("первый вопрос", "fake-reply"), r.messages.value.map { it.text })
            val firstIds = r.messages.value.map { it.id }
            assertTrue(
                firstIds.intersect(secondIds.toSet()).isEmpty(),
                "id сообщений разных чатов не должны пересекаться: $firstIds vs $secondIds",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun clearChatKeepsOtherChats() = runBlocking {
        val (r, db) = repo()
        try {
            val first = r.activeChatId.value
            r.send("первый вопрос")
            val second = r.createChat()
            r.send("второй вопрос")

            r.clearChat()
            assertTrue(r.messages.value.isEmpty(), "очистка должна опустошить активный чат")
            assertEquals(0, r.chats.value.first { it.id == second }.messageCount)

            r.selectChat(first)
            assertEquals(2, r.messages.value.size, "соседний чат не должен пострадать")
        } finally {
            db.close()
        }
    }

    @Test
    fun deleteChatRemovesOnlyItsMessages() = runBlocking {
        val (r, db) = repo()
        try {
            val first = r.activeChatId.value
            r.send("первый вопрос")
            val second = r.createChat()
            r.send("второй вопрос")

            r.deleteChat(second)
            assertEquals(listOf(first), r.chats.value.map { it.id })
            assertEquals(first, r.activeChatId.value, "после удаления активного чата выбирается оставшийся")
            assertEquals(2, r.messages.value.size)
            assertEquals(2, r.chats.value.first().messageCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun deletingLastChatKeepsOneEmptyChat() = runBlocking {
        val (r, db) = repo()
        try {
            val only = r.activeChatId.value
            r.send("первый вопрос")

            r.deleteChat(only)
            assertEquals(1, r.chats.value.size, "всегда должен оставаться хотя бы один чат")
            val fresh = r.activeChatId.value
            assertTrue(fresh != only, "удалённый чат не должен оставаться активным")
            assertTrue(r.messages.value.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun firstUserMessageBecomesTitle() = runBlocking {
        val (r, db) = repo()
        try {
            r.send("расскажи про ворону")

            val active = r.chats.value.first { it.id == r.activeChatId.value }
            assertEquals("расскажи про ворону", active.title)

            r.renameChat(active.id, "Вороны")
            assertEquals("Вороны", r.chats.value.first { it.id == active.id }.title)
            assertEquals("Вороны", r.activeChat?.title)
        } finally {
            db.close()
        }
    }

    @Test
    fun chatsAreSortedByActivity() = runBlocking {
        val (r, db) = repo()
        try {
            val first = r.activeChatId.value
            val second = r.createChat()
            assertEquals(listOf(second, first), r.chats.value.map { it.id }, "свежий чат сверху")

            r.selectChat(first)
            r.send("вернулись в первый")
            assertEquals(first, r.chats.value.first().id, "активность поднимает чат наверх")
        } finally {
            db.close()
        }
    }
}