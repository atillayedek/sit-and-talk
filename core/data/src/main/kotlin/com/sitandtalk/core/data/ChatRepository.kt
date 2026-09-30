package com.sitandtalk.core.data

import android.net.Uri
import com.sitandtalk.core.database.ConversationCacheDao
import com.sitandtalk.core.database.ConversationCacheEntity
import com.sitandtalk.core.database.MessageCacheDao
import com.sitandtalk.core.database.MessageCacheEntity
import com.sitandtalk.core.database.OutboxDao
import com.sitandtalk.core.database.OutboxMessageEntity
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.ConversationItem
import com.sitandtalk.core.model.ConversationMember
import com.sitandtalk.core.model.Message
import com.sitandtalk.core.model.MessageKind
import com.sitandtalk.core.model.MessageReaction
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.model.UserSummary
import com.sitandtalk.core.network.Api
import com.sitandtalk.core.network.AppJson
import com.sitandtalk.core.network.apiCall
import com.sitandtalk.core.network.putNullable
import com.sitandtalk.core.network.toAppException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class DeliveryState { Sending, Failed, Sent, Delivered, Read }

sealed interface ChatItem {
    val id: String
    val createdAtMillis: Long

    data class Remote(
        val message: Message,
        val mine: Boolean,
        val delivery: DeliveryState?,
        val reactions: List<MessageReaction>,
    ) : ChatItem {
        override val id get() = message.id
        override val createdAtMillis get() = ServerTime.parse(message.createdAt)?.toEpochMilli() ?: 0
    }

    data class Pending(val outbox: OutboxMessageEntity) : ChatItem {
        override val id get() = outbox.id
        override val createdAtMillis get() = outbox.createdAtMillis
        val failed get() = outbox.state == OutboxMessageEntity.STATE_FAILED
        val kind: String get() = outbox.kind
        val body: String? get() = outbox.body
    }
}

data class ConversationDetails(
    val members: List<ConversationMember>,
    val profiles: Map<String, UserSummary>,
)

@Singleton
class ChatRepository @Inject constructor(
    private val api: Api,
    private val auth: AuthRepository,
    private val media: MediaRepository,
    private val realtime: RealtimeStreams,
    private val conversationDao: ConversationCacheDao,
    private val messageDao: MessageCacheDao,
    private val outbox: OutboxDao,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    // -----------------------------------------------------------------------------------------
    // Conversations
    // -----------------------------------------------------------------------------------------

    suspend fun refreshConversations(archived: Boolean = false): List<ConversationItem> {
        val uid = auth.requireUserId()
        val list = api.rpc<List<ConversationItem>>("list_conversations") { put("p_archived", archived) }
        if (!archived) {
            conversationDao.replaceAll(uid, list.map {
                ConversationCacheEntity(it.id, uid, AppJson.encodeToString(ConversationItem.serializer(), it), it.sortAt.orEmpty())
            })
        }
        return list
    }

    /** Last list received from the server, for offline display. */
    fun cachedConversations(): Flow<List<ConversationItem>> = conversationDao.observe(auth.requireUserId()).map { rows ->
        rows.mapNotNull { runCatching { AppJson.decodeFromString(ConversationItem.serializer(), it.json) }.getOrNull() }
            .sortedWith(compareByDescending<ConversationItem> { it.pinned }.thenByDescending { it.sortAt })
    }

    fun conversationListChanges(): Flow<Unit> =
        realtime.changes("conversation_members", "user_id=eq.${auth.requireUserId()}").map { }

    suspend fun openDirect(userId: String): String = api.rpc("get_or_create_direct") { put("p_user", userId) }

    suspend fun createGroup(title: String, memberIds: List<String>): String = api.rpc("create_group") {
        put("p_title", title.trim())
        put("p_members", JsonArray(memberIds.map { JsonPrimitive(it) }))
    }

    suspend fun addMembers(conversationId: String, memberIds: List<String>) = api.rpcUnit("add_group_members") {
        put("p_conversation", conversationId)
        put("p_members", JsonArray(memberIds.map { JsonPrimitive(it) }))
    }

    suspend fun removeMember(conversationId: String, userId: String) = api.rpcUnit("remove_group_member") {
        put("p_conversation", conversationId)
        put("p_user", userId)
    }

    suspend fun updateSettings(conversationId: String, pinned: Boolean? = null, archived: Boolean? = null, mutedUntil: String? = null, clearMute: Boolean = false) =
        api.rpcUnit("update_conversation_settings") {
            put("p_conversation", conversationId)
            if (pinned != null) put("p_pinned", pinned)
            if (archived != null) put("p_archived", archived)
            putNullable("p_muted_until", mutedUntil)
            put("p_clear_mute", clearMute)
        }

    suspend fun clearHistory(conversationId: String) {
        api.rpcUnit("clear_conversation") { put("p_conversation", conversationId) }
        messageDao.clearConversation(auth.requireUserId(), conversationId)
    }

    suspend fun details(conversationId: String): ConversationDetails {
        val members = apiCall {
            api.client.from("conversation_members").select(
                Columns.list("conversation_id", "user_id", "role", "joined_at", "left_at", "typing_until", "delivered_at", "shared_read_at"),
            ) { filter { eq("conversation_id", conversationId) } }.decodeList<ConversationMember>()
        }
        val ids = members.map { it.userId }
        val profiles = if (ids.isEmpty()) emptyList() else apiCall {
            api.client.from("profiles").select(Columns.list("id", "username", "display_name", "avatar_path")) {
                filter { isIn("id", ids) }
            }.decodeList<UserSummary>()
        }
        return ConversationDetails(members, profiles.associateBy { it.id })
    }

    // -----------------------------------------------------------------------------------------
    // Messages
    // -----------------------------------------------------------------------------------------

    /** Loads the newest page (or the page before [beforeCreatedAt]) from the server and caches it. */
    suspend fun loadMessages(conversationId: String, beforeCreatedAt: String? = null): List<Message> {
        val uid = auth.requireUserId()
        val page = apiCall {
            api.client.from("messages").select(
                Columns.list("id", "conversation_id", "sender_id", "kind", "body", "media_path", "media_mime",
                    "media_duration_ms", "reply_to_id", "created_at", "edited_at", "deleted_for_all_at"),
            ) {
                filter {
                    eq("conversation_id", conversationId)
                    if (beforeCreatedAt != null) lt("created_at", beforeCreatedAt)
                }
                order("created_at", Order.DESCENDING)
                limit(PAGE_SIZE.toLong())
            }.decodeList<Message>()
        }
        messageDao.upsert(page.map { MessageCacheEntity(it.id, uid, it.conversationId, AppJson.encodeToString(Message.serializer(), it), it.createdAt) })
        return page
    }

    suspend fun reactions(messageIds: List<String>): List<MessageReaction> = if (messageIds.isEmpty()) emptyList() else apiCall {
        api.client.from("message_reactions").select(Columns.list("message_id", "user_id", "emoji", "created_at")) {
            filter { isIn("message_id", messageIds) }
        }.decodeList<MessageReaction>()
    }

    /** Cached server messages merged with this device's unsent outbox, oldest first. */
    fun observeConversation(
        conversationId: String,
        members: Flow<List<ConversationMember>>,
        reactions: Flow<Map<String, List<MessageReaction>>>,
    ): Flow<List<ChatItem>> {
        val uid = auth.requireUserId()
        return combine(messageDao.observe(uid, conversationId), outbox.observe(uid, conversationId), members, reactions) { cached, pending, memberList, reactionMap ->
            val others = memberList.filter { it.userId != uid && it.leftAt == null }
            val remote = cached.mapNotNull { runCatching { AppJson.decodeFromString(Message.serializer(), it.json) }.getOrNull() }
                .map { m ->
                    val mine = m.senderId == uid
                    ChatItem.Remote(m, mine, if (mine) deliveryOf(m, others) else null, reactionMap[m.id].orEmpty())
                }
            val confirmed = remote.map { it.id }.toSet()
            (remote + pending.filter { it.id !in confirmed }.map { ChatItem.Pending(it) }).sortedBy { it.createdAtMillis }
        }
    }

    private fun deliveryOf(message: Message, others: List<ConversationMember>): DeliveryState {
        if (others.isEmpty()) return DeliveryState.Sent
        val created = ServerTime.parse(message.createdAt) ?: return DeliveryState.Sent
        val allRead = others.all { m -> ServerTime.parse(m.sharedReadAt)?.let { !it.isBefore(created) } == true }
        if (allRead) return DeliveryState.Read
        val anyDelivered = others.any { m -> ServerTime.parse(m.deliveredAt)?.let { !it.isBefore(created) } == true }
        return if (anyDelivered) DeliveryState.Delivered else DeliveryState.Sent
    }

    fun messageChanges(conversationId: String): Flow<Unit> = merge(
        realtime.changes("messages", "conversation_id=eq.$conversationId"),
        realtime.changes("message_reactions"),
    ).map { }

    fun memberChanges(conversationId: String): Flow<Unit> =
        realtime.changes("conversation_members", "conversation_id=eq.$conversationId").map { }

    fun sendText(conversationId: String, body: String, replyToId: String?) {
        enqueue(conversationId, MessageKind.Text, body.trim(), null, null, null, replyToId)
    }

    fun sendImage(conversationId: String, uri: Uri) {
        enqueue(conversationId, MessageKind.Image, null, uri.toString(), "image/jpeg", null, null)
    }

    fun sendVoice(conversationId: String, file: File, durationMs: Int) {
        enqueue(conversationId, MessageKind.Voice, null, Uri.fromFile(file).toString(), "audio/mp4", durationMs, null)
    }

    private fun enqueue(conversationId: String, kind: MessageKind, body: String?, localUri: String?, mime: String?, durationMs: Int?, replyToId: String?) {
        val uid = auth.requireUserId()
        val item = OutboxMessageEntity(
            id = UUID.randomUUID().toString(),
            ownerId = uid,
            conversationId = conversationId,
            kind = kind.name.lowercase(),
            body = body,
            localMediaUri = localUri,
            mediaMime = mime,
            mediaDurationMs = durationMs,
            replyToId = replyToId,
            createdAtMillis = System.currentTimeMillis(),
            state = OutboxMessageEntity.STATE_QUEUED,
        )
        scope.launch {
            outbox.put(item)
            deliver(item.id)
        }
    }

    fun retry(outboxId: String) {
        scope.launch {
            outbox.setState(outboxId, OutboxMessageEntity.STATE_QUEUED, 0, null)
            deliver(outboxId)
        }
    }

    fun discard(outboxId: String) {
        scope.launch { outbox.delete(outboxId) }
    }

    @Volatile
    private var recoveredFor: String? = null

    /** Sends everything still queued (app start, connectivity back). */
    suspend fun flushOutbox() {
        val uid = auth.currentUserId() ?: return
        if (recoveredFor != uid) {
            outbox.requeueInterrupted(uid)
            recoveredFor = uid
        }
        outbox.pending(uid).forEach { deliver(it.id) }
    }

    private suspend fun deliver(outboxId: String) {
        val item = outbox.get(outboxId) ?: return
        if (item.state == OutboxMessageEntity.STATE_SENDING) return
        outbox.setState(outboxId, OutboxMessageEntity.STATE_SENDING, 1, null)
        try {
            val localUri: String? = item.localMediaUri
            val mediaPath = when {
                localUri == null -> null
                item.kind == "image" -> media.uploadImage(StorageBuckets.CHAT_MEDIA, item.conversationId, Uri.parse(localUri))
                else -> media.uploadAudio(StorageBuckets.CHAT_MEDIA, item.conversationId, File(Uri.parse(localUri).path ?: ""))
            }
            val durationMs: Int? = item.mediaDurationMs
            try {
                apiCall {
                    api.client.from("messages").insert(buildJsonObject {
                        put("id", item.id)
                        put("conversation_id", item.conversationId)
                        put("sender_id", item.ownerId)
                        put("kind", item.kind)
                        putNullable("body", item.body)
                        putNullable("media_path", mediaPath)
                        putNullable("media_mime", item.mediaMime)
                        if (durationMs != null) put("media_duration_ms", durationMs)
                        putNullable("reply_to_id", item.replyToId)
                    })
                }
            } catch (e: AppException) {
                // Same id already stored: an earlier attempt reached the server.
                if (e.code != "duplicate") throw e
            }
            loadMessages(item.conversationId)
            outbox.delete(item.id)
            if (localUri != null && localUri.startsWith("file:")) runCatching { File(Uri.parse(localUri).path ?: "").delete() }
        } catch (e: Exception) {
            val mapped = e.toAppException()
            outbox.setState(outboxId, OutboxMessageEntity.STATE_FAILED, 0, mapped.code)
        }
    }

    suspend fun edit(messageId: String, body: String, conversationId: String) {
        api.rpcUnit("edit_message") {
            put("p_message", messageId)
            put("p_body", body.trim())
        }
        loadMessages(conversationId)
    }

    suspend fun deleteForEveryone(messageId: String, conversationId: String) {
        api.rpcUnit("delete_message_for_all") { put("p_message", messageId) }
        loadMessages(conversationId)
    }

    suspend fun deleteForMe(messageId: String) {
        api.rpcUnit("hide_message") { put("p_message", messageId) }
        messageDao.delete(messageId)
    }

    suspend fun react(messageId: String, emoji: String?) = api.rpcUnit("react_to_message") {
        put("p_message", messageId)
        put("p_emoji", emoji.orEmpty())
    }

    suspend fun markRead(conversationId: String) = runCatching { api.rpcUnit("mark_conversation_read") { put("p_conversation", conversationId) } }
    suspend fun markDelivered() = runCatching { api.rpcUnit("mark_delivered") }
    suspend fun typing(conversationId: String) = runCatching { api.rpcUnit("set_typing") { put("p_conversation", conversationId) } }

    companion object {
        const val PAGE_SIZE = 50
    }
}
