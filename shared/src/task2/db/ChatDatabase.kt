package task2.db

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Local chat DB (Room KMP — as recommended for KMP apps).
 * File: modelsDir/chat.db. The messages table survives restarts,
 * facts replaced memory.json.
 */
@Entity(tableName = "messages")
data class MessageEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	/** "USER" | "ASSISTANT" */
	val role: String,
	val text: String,
	val createdAt: Long,
)

@Entity(tableName = "facts")
data class FactEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	val text: String,
	val createdAt: Long,
	/** Fact origin: "rules" (deterministic rules) — column added by the v1→v2 migration. */
	val source: String = "rules",
)

@Dao
interface ChatDao {
	@Query("SELECT * FROM messages ORDER BY id ASC")
	suspend fun getMessages(): List<MessageEntity>

	@Insert
	suspend fun insertMessage(msg: MessageEntity): Long

	@Query("DELETE FROM messages")
	suspend fun clearMessages()

	@Query("SELECT * FROM facts ORDER BY id ASC")
	suspend fun getFacts(): List<FactEntity>

	@Insert
	suspend fun insertFacts(items: List<FactEntity>)

	@Query("DELETE FROM facts WHERE id NOT IN (SELECT id FROM facts ORDER BY id DESC LIMIT :keep)")
	suspend fun trimFacts(keep: Int)

	@Query("DELETE FROM facts")
	suspend fun clearFacts()
}

@Database(entities = [MessageEntity::class, FactEntity::class], version = 2)
abstract class ChatDatabase : RoomDatabase() {
	abstract fun chatDao(): ChatDao
}

/**
 * Versioned migration v1 → v2 (analog of Flyway script V2__add_fact_source.sql:
 * Flyway has no JDBC driver for Android, so migrations use Room tooling).
 * v1: facts(id, text, createdAt). v2: + source TEXT NOT NULL DEFAULT 'rules'.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
	override fun migrate(connection: SQLiteConnection) {
		connection.execSQL("ALTER TABLE `facts` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'rules'")
	}
}

/** Builds the DB from the platform builder + applies migrations. No destructive migrations. */
fun buildChatDatabase(builder: RoomDatabase.Builder<ChatDatabase>): ChatDatabase =
	builder.addMigrations(MIGRATION_1_2).build()
