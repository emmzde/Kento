package dev.kento.data.db

import androidx.room.*

@Entity(tableName = "web_cache", primaryKeys = ["kind", "key"])
data class WebCacheEntry(val kind: String, val key: String, val value: String, val expires: Long)

@Dao
abstract class WebDao {
    @Query("SELECT value FROM web_cache WHERE kind = :kind AND `key` = :key AND expires > :now")
    abstract suspend fun get(kind: String, key: String, now: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(entry: WebCacheEntry)

    @Query("DELETE FROM web_cache WHERE kind = :kind AND (expires <= :now OR `key` IN (SELECT `key` FROM web_cache WHERE kind = :kind ORDER BY expires DESC, `key` LIMIT -1 OFFSET :limit))")
    abstract suspend fun prune(kind: String, now: Long, limit: Int)

    @Transaction
    open suspend fun save(entry: WebCacheEntry, now: Long, limit: Int) {
        put(entry)
        prune(entry.kind, now, limit)
    }
}
