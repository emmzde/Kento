package dev.kento.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/** v7 adds dates to messages and memories; existing rows take their chat's last activity as the best estimate. */
class Timestamps : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE messages SET createdAt = COALESCE((SELECT updatedAt FROM chats WHERE chats.id = messages.chatId), 0) WHERE createdAt = 0")
        db.execSQL("""UPDATE memory_entries SET createdAt = COALESCE((SELECT MAX(m.createdAt) FROM memory_sources s
            JOIN messages m ON m.id = s.messageId WHERE s.entryId = memory_entries.id), ${System.currentTimeMillis()}) WHERE createdAt = 0""")
        db.execSQL("UPDATE memory_entries SET importance = 3 WHERE edited = 1 AND kind = 'fact'")
    }
}

class IndexHistory : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO message_search(message_search) VALUES('rebuild')")
    }
}

/**
 * v12 drops the domain votes of the old search pipeline: nothing has written them since
 * it went, and the scores left from older versions kept boosting the same sites in the ranking for good.
 */
@DeleteTable(tableName = "web_domains")
class DropDomainVotes : AutoMigrationSpec

class IndexSummaries : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO summary_search(summary_search) VALUES('rebuild')")
    }
}

@Database(entities = [Conversation::class, Message::class, MemoryEntry::class, MemorySource::class,
    MemoryCursor::class, MemoryIndex::class, MessageIndex::class, WebCacheEntry::class, Attachment::class, OcrEntry::class, ChatSummary::class, SummaryIndex::class],
    version = 12, exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2, spec = IndexHistory::class), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7, spec = Timestamps::class), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9),
        // v10: chat_summaries and the measured prompt size of each reply.
        AutoMigration(from = 9, to = 10),
        // v11: fingerprints of deleted facts and the full-text index of chat summaries.
        AutoMigration(from = 10, to = 11, spec = IndexSummaries::class),
        AutoMigration(from = 11, to = 12, spec = DropDomainVotes::class)])
abstract class KentoDb : RoomDatabase() {
    abstract fun media(): MediaDao
    abstract fun chats(): ChatDao
    abstract fun memory(): MemoryDao
    abstract fun web(): WebDao
    abstract fun backup(): BackupDao
    companion object {
        @Volatile private var instance: KentoDb? = null
        fun open(context: Context): KentoDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, KentoDb::class.java, "kento.db").build().also { instance = it }
        }
    }
}
