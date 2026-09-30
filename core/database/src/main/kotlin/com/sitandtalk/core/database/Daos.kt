package com.sitandtalk.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationCacheDao {
    @Query("SELECT * FROM conversation_cache WHERE ownerId = :ownerId ORDER BY sortAt DESC")
    fun observe(ownerId: String): Flow<List<ConversationCacheEntity>>

    @Query("DELETE FROM conversation_cache WHERE ownerId = :ownerId")
    suspend fun clear(ownerId: String)

    @Upsert
    suspend fun upsert(items: List<ConversationCacheEntity>)

    @Transaction
    suspend fun replaceAll(ownerId: String, items: List<ConversationCacheEntity>) {
        clear(ownerId)
        upsert(items)
    }
}

@Dao
interface MessageCacheDao {
    @Query("SELECT * FROM message_cache WHERE ownerId = :ownerId AND conversationId = :conversationId ORDER BY createdAt ASC")
    fun observe(ownerId: String, conversationId: String): Flow<List<MessageCacheEntity>>

    @Query("SELECT MAX(createdAt) FROM message_cache WHERE ownerId = :ownerId AND conversationId = :conversationId")
    suspend fun latestCreatedAt(ownerId: String, conversationId: String): String?

    @Upsert
    suspend fun upsert(items: List<MessageCacheEntity>)

    @Query("DELETE FROM message_cache WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM message_cache WHERE ownerId = :ownerId AND conversationId = :conversationId")
    suspend fun clearConversation(ownerId: String, conversationId: String)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM message_outbox WHERE ownerId = :ownerId AND conversationId = :conversationId ORDER BY createdAtMillis ASC")
    fun observe(ownerId: String, conversationId: String): Flow<List<OutboxMessageEntity>>

    @Query("SELECT * FROM message_outbox WHERE ownerId = :ownerId AND state != 'failed' ORDER BY createdAtMillis ASC")
    suspend fun pending(ownerId: String): List<OutboxMessageEntity>

    @Query("SELECT * FROM message_outbox WHERE id = :id")
    suspend fun get(id: String): OutboxMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: OutboxMessageEntity)

    @Query("UPDATE message_outbox SET state = :state, attempts = attempts + :attemptDelta, lastError = :error WHERE id = :id")
    suspend fun setState(id: String, state: String, attemptDelta: Int, error: String?)

    @Query("DELETE FROM message_outbox WHERE id = :id")
    suspend fun delete(id: String)

    /** Sends interrupted by process death are retried; the stable id makes a repeat harmless. */
    @Query("UPDATE message_outbox SET state = 'queued' WHERE ownerId = :ownerId AND state = 'sending'")
    suspend fun requeueInterrupted(ownerId: String)
}
