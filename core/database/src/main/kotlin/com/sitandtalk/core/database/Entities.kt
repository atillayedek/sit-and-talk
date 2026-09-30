package com.sitandtalk.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cached copy of a conversation list row received from the server (never locally invented). */
@Entity(tableName = "conversation_cache", indices = [Index("ownerId")])
data class ConversationCacheEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val json: String,
    val sortAt: String,
)

/** Cached copy of a server message. */
@Entity(tableName = "message_cache", indices = [Index(value = ["ownerId", "conversationId", "createdAt"])])
data class MessageCacheEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val conversationId: String,
    val json: String,
    val createdAt: String,
)

/**
 * A message the user wrote that the server has not confirmed yet. Its [id] is the idempotency key sent
 * to the server, so retries can never create duplicates. It is shown as "sending" or "failed", never as sent.
 */
@Entity(tableName = "message_outbox", indices = [Index(value = ["ownerId", "conversationId"])])
data class OutboxMessageEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val conversationId: String,
    val kind: String,
    val body: String?,
    val localMediaUri: String?,
    val mediaMime: String?,
    val mediaDurationMs: Int?,
    val replyToId: String?,
    val createdAtMillis: Long,
    val state: String,
    val attempts: Int = 0,
    val lastError: String? = null,
) {
    companion object {
        const val STATE_QUEUED = "queued"
        const val STATE_SENDING = "sending"
        const val STATE_FAILED = "failed"
    }
}
