package task3.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import task2.data.nowMillis

/**
 * RAG DB (rag.db file, separate from task 2 chat.db).
 * chunks: single index (structure strategy), vector — BLOB float32 LE.
 */
@Entity(tableName = "chunks")
data class ChunkEntity(
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
data class DocumentEntity(
    @PrimaryKey val source: String,
    val title: String,
    val status: String,
    val error: String = "",
    val active: Boolean = true,
    val updatedAt: Long,
)

/** One conversation. Messages belong to a chat, the chat list is the drawer. */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chatId")],
)
data class RagMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // The default is the legacy chat id — it keeps the v1 → v2 migration
    // (which recreates the table) consistent with the declared schema.
    @ColumnInfo(defaultValue = "1") val chatId: Long = 1,
    val role: String,
    val text: String,
    val createdAt: Long,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface RagDao {
    // --- chunks ---
    @Upsert
    suspend fun upsertChunks(items: List<ChunkEntity>)

    @Query("SELECT * FROM chunks WHERE source = :source ORDER BY ord ASC")
    suspend fun chunksOf(source: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks ORDER BY source ASC, ord ASC")
    suspend fun allChunks(): List<ChunkEntity>

    @Query("SELECT text, embedding, dim FROM chunks WHERE text = :text LIMIT 1")
    suspend fun findVectorByText(text: String): ChunkVectorRow?

    @Query("DELETE FROM chunks WHERE source IN (:sources)")
    suspend fun deleteChunksOf(sources: List<String>)

    @Query("DELETE FROM chunks")
    suspend fun clearChunks()

    @Query("SELECT COUNT(*) FROM chunks")
    suspend fun chunkCount(): Int

    // --- documents ---
    @Upsert
    suspend fun upsertDoc(doc: DocumentEntity)

    @Query("SELECT * FROM documents ORDER BY title ASC")
    suspend fun allDocs(): List<DocumentEntity>

    @Query("DELETE FROM documents WHERE source IN (:sources)")
    suspend fun deleteDocs(sources: List<String>)

    // --- chats ---
    /** One query for the drawer: count and last text per chat, ordered by activity. */
    @Query(
        """
        SELECT c.id AS id, c.title AS title, c.createdAt AS createdAt, c.updatedAt AS updatedAt,
               (SELECT COUNT(*) FROM messages m WHERE m.chatId = c.id) AS messageCount,
               (SELECT m.text FROM messages m WHERE m.chatId = c.id ORDER BY m.id DESC LIMIT 1) AS lastText
        FROM chats c
        ORDER BY c.updatedAt DESC, c.id DESC
        """,
    )
    suspend fun chatRows(): List<ChatListRow>

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC, id DESC")
    suspend fun allChats(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun chat(id: Long): ChatEntity?

    @Insert
    suspend fun insertChat(chat: ChatEntity): Long

    @Query("UPDATE chats SET title = :title WHERE id = :id")
    suspend fun renameChat(id: Long, title: String)

    @Query("UPDATE chats SET updatedAt = :ts WHERE id = :id")
    suspend fun touchChat(id: Long, ts: Long)

    @Query("DELETE FROM messages WHERE chatId = :id")
    suspend fun deleteMessagesOf(id: Long)

    /** Explicit deletes instead of relying on the CASCADE pragma being enabled. */
    @Transaction
    suspend fun deleteChat(id: Long) {
        deleteMessagesOf(id)
        deleteChatRow(id)
    }

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteChatRow(id: Long)

    // --- history ---
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    suspend fun messagesOf(chatId: Long): List<RagMessageEntity>

    @Insert
    suspend fun insertMessage(msg: RagMessageEntity): Long

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun clearMessagesOf(chatId: Long)

    @Query("SELECT MAX(id) FROM messages")
    suspend fun maxMessageId(): Long?

    // --- settings ---
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun getSetting(key: String): String?

    @Upsert
    suspend fun setSetting(item: SettingEntity)
}

data class ChunkVectorRow(
    val text: String,
    val embedding: ByteArray,
    val dim: Int,
)

/** Drawer row: chat plus its message count and the last message text. */
data class ChatListRow(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val lastText: String?,
)

@Database(
    entities = [
        ChunkEntity::class,
        DocumentEntity::class,
        ChatEntity::class,
        RagMessageEntity::class,
        SettingEntity::class,
    ],
    version = 2,
)
abstract class RagDatabase : RoomDatabase() {
    abstract fun ragDao(): RagDao
}

/**
 * v1 → v2: chats appeared. The single v1 conversation becomes chat 1 («Чат»),
 * every existing message gets chatId = 1 — history is kept, not dropped.
 * NB: SQLite cannot add a NOT NULL column with a foreign key by ALTER TABLE,
 * so `messages` is recreated and the rows are copied; Room compares the result
 * with the declared schema on open (covered by ChatsTest.migrate1to2).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        val now = nowMillis()
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `chats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        )
        connection.execSQL(
            "INSERT INTO `chats` (`id`, `title`, `createdAt`, `updatedAt`) " +
                "VALUES (1, 'Чат', $now, $now)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `messages_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`chatId` INTEGER NOT NULL DEFAULT 1, " +
                "`role` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        connection.execSQL(
            "INSERT INTO `messages_new` (`id`, `chatId`, `role`, `text`, `createdAt`) " +
                "SELECT `id`, 1, `role`, `text`, `createdAt` FROM `messages`",
        )
        connection.execSQL("DROP TABLE `messages`")
        connection.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_messages_chatId` ON `messages` (`chatId`)",
        )
    }
}

fun buildRagDatabase(builder: RoomDatabase.Builder<RagDatabase>): RagDatabase =
    builder.addMigrations(MIGRATION_1_2).build()