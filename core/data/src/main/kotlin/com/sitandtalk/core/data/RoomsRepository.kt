package com.sitandtalk.core.data

import com.sitandtalk.core.model.RoomEvent
import com.sitandtalk.core.model.RoomInvite
import com.sitandtalk.core.model.RoomMessage
import com.sitandtalk.core.model.RoomReaction
import com.sitandtalk.core.model.RoomRole
import com.sitandtalk.core.model.RoomState
import com.sitandtalk.core.model.RoomSummary
import com.sitandtalk.core.model.RoomVisibility
import com.sitandtalk.core.model.RtcCredentials
import com.sitandtalk.core.network.Api
import com.sitandtalk.core.network.apiCall
import com.sitandtalk.core.network.putNullable
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class CreateRoomInput(
    val title: String,
    val description: String,
    val topic: String?,
    val tags: List<String>,
    val language: String,
    val visibility: RoomVisibility,
    val password: String?,
    val maxParticipants: Int,
    val maxSpeakers: Int,
    val handRaiseRequired: Boolean,
    val textChatEnabled: Boolean,
    val eventId: String? = null,
)

private val RoomVisibility.wire get() = name.lowercase()
private val RoomRole.wire get() = name.lowercase()

@Singleton
class RoomsRepository @Inject constructor(
    private val api: Api,
    private val realtime: RealtimeStreams,
    private val auth: AuthRepository,
) {
    suspend fun list(query: String?, topic: String?, language: String?, favoritesOnly: Boolean, offset: Int): List<RoomSummary> =
        api.rpc("list_rooms") {
            putNullable("p_query", query?.takeIf { it.isNotBlank() })
            putNullable("p_topic", topic)
            putNullable("p_language", language)
            put("p_favorites_only", favoritesOnly)
            put("p_limit", 30)
            put("p_offset", offset)
        }

    suspend fun get(roomId: String): RoomState = api.rpc("get_room") { put("p_room", roomId) }

    suspend fun create(input: CreateRoomInput): RoomState = api.rpc("create_room") {
        put("p_title", input.title.trim())
        put("p_description", input.description.trim())
        putNullable("p_topic", input.topic)
        put("p_tags", JsonArray(input.tags.map { JsonPrimitive(it) }))
        put("p_language", input.language)
        put("p_visibility", input.visibility.wire)
        putNullable("p_password", input.password?.takeIf { input.visibility == RoomVisibility.Password })
        put("p_max_participants", input.maxParticipants)
        put("p_max_speakers", input.maxSpeakers)
        put("p_hand_raise_required", input.handRaiseRequired)
        put("p_text_chat_enabled", input.textChatEnabled)
        putNullable("p_event_id", input.eventId)
    }

    suspend fun join(roomId: String, password: String?, inviteCode: String?): RoomState = api.rpc("join_room") {
        put("p_room", roomId)
        putNullable("p_password", password)
        putNullable("p_invite_code", inviteCode)
    }

    suspend fun leave(roomId: String) = api.rpcUnit("leave_room") { put("p_room", roomId) }
    suspend fun heartbeat(roomId: String): RoomState = api.rpc("room_heartbeat") { put("p_room", roomId) }

    suspend fun setSelfMuted(roomId: String, muted: Boolean) = api.rpcUnit("set_self_muted") {
        put("p_room", roomId)
        put("p_muted", muted)
    }

    suspend fun raiseHand(roomId: String, raised: Boolean) = api.rpcUnit("raise_hand") {
        put("p_room", roomId)
        put("p_raised", raised)
    }

    suspend fun inviteToSpeak(roomId: String, userId: String) = api.rpcUnit("invite_to_speak") {
        put("p_room", roomId)
        put("p_user", userId)
    }

    suspend fun respondSpeakerInvite(roomId: String, accept: Boolean): RoomState = api.rpc("respond_speaker_invite") {
        put("p_room", roomId)
        put("p_accept", accept)
    }

    suspend fun declineHand(roomId: String, userId: String) = api.rpcUnit("decline_hand") {
        put("p_room", roomId)
        put("p_user", userId)
    }

    suspend fun setRole(roomId: String, userId: String, role: RoomRole) = api.rpcUnit("set_member_role") {
        put("p_room", roomId)
        put("p_user", userId)
        put("p_role", role.wire)
    }

    suspend fun moderatorMute(roomId: String, userId: String, muted: Boolean) = api.rpcUnit("moderator_mute") {
        put("p_room", roomId)
        put("p_user", userId)
        put("p_muted", muted)
    }

    /** Removes the member in the database, then asks Agora to cut their live connection if configured. */
    suspend fun kick(roomId: String, userId: String, ban: Boolean, reason: String?): Boolean {
        api.rpcUnit("kick_from_room") {
            put("p_room", roomId)
            put("p_user", userId)
            put("p_ban", ban)
            putNullable("p_reason", reason)
        }
        return runCatching {
            api.function<RtcKickResult>("rtc-moderation") {
                put("room_id", roomId)
                put("user_id", userId)
            }.enforced
        }.getOrDefault(false)
    }

    suspend fun unban(roomId: String, userId: String) = api.rpcUnit("unban_from_room") {
        put("p_room", roomId)
        put("p_user", userId)
    }

    suspend fun close(roomId: String) = api.rpcUnit("close_room") { put("p_room", roomId) }

    suspend fun update(roomId: String, title: String, description: String, handRaiseRequired: Boolean, textChat: Boolean): RoomState =
        api.rpc("update_room") {
            put("p_room", roomId)
            put("p_title", title)
            put("p_description", description)
            put("p_hand_raise_required", handRaiseRequired)
            put("p_text_chat_enabled", textChat)
        }

    suspend fun createInvite(roomId: String, userId: String?, maxUses: Int = 25): RoomInvite = api.rpc("create_room_invite") {
        put("p_room", roomId)
        if (userId != null) put("p_user", userId) else put("p_user", JsonNull)
        put("p_max_uses", maxUses)
    }

    suspend fun sendReaction(roomId: String, kind: String) = api.rpcUnit("send_room_reaction") {
        put("p_room", roomId)
        put("p_kind", kind)
    }

    suspend fun setFavorite(roomId: String, favorite: Boolean) = api.rpcUnit("set_room_favorite") {
        put("p_room", roomId)
        put("p_favorite", favorite)
    }

    suspend fun messages(roomId: String): List<RoomMessage> = apiCall {
        api.client.from("room_messages").select(Columns.list("id", "room_id", "user_id", "body", "created_at", "deleted_at")) {
            filter {
                eq("room_id", roomId)
                exact("deleted_at", null)
            }
            order("created_at", Order.DESCENDING)
            limit(100)
        }.decodeList<RoomMessage>().reversed()
    }

    suspend fun sendMessage(roomId: String, body: String) {
        val uid = auth.requireUserId()
        apiCall {
            api.client.from("room_messages").insert(buildJsonObject {
                put("room_id", roomId)
                put("user_id", uid)
                put("body", body.trim())
            })
        }
    }

    suspend fun recentReactions(roomId: String, since: Instant): List<RoomReaction> = apiCall {
        api.client.from("room_reactions").select(
            Columns.list("id", "room_id", "user_id", "kind", "gift_code", "target_user_id", "created_at"),
        ) {
            filter {
                eq("room_id", roomId)
                gt("created_at", since.toString())
            }
            order("created_at", Order.ASCENDING)
            limit(50)
        }.decodeList<RoomReaction>()
    }

    suspend fun events(): List<RoomEvent> = api.rpc("list_room_events")

    suspend fun createEvent(title: String, description: String, topic: String?, language: String, startsAt: Instant): String =
        api.rpc("create_room_event") {
            put("p_title", title)
            put("p_description", description)
            putNullable("p_topic", topic)
            put("p_language", language)
            put("p_starts_at", startsAt.toString())
        }

    suspend fun setEventSubscription(eventId: String, subscribed: Boolean) = api.rpcUnit("set_event_subscription") {
        put("p_event", eventId)
        put("p_subscribed", subscribed)
    }

    suspend fun cancelEvent(eventId: String) = api.rpcUnit("cancel_room_event") { put("p_event", eventId) }

    suspend fun rtcToken(roomId: String): RtcCredentials = api.function("agora-token") {
        put("kind", "room")
        put("id", roomId)
    }

    /** Any change that should refresh the room screen. */
    fun roomChanges(roomId: String): Flow<Unit> = merge(
        realtime.changes("rooms", "id=eq.$roomId"),
        realtime.changes("room_members", "room_id=eq.$roomId"),
        realtime.changes("room_speaker_requests", "room_id=eq.$roomId"),
    ).map { }

    fun messageChanges(roomId: String): Flow<Unit> = realtime.changes("room_messages", "room_id=eq.$roomId").map { }
    fun reactionChanges(roomId: String): Flow<Unit> = realtime.changes("room_reactions", "room_id=eq.$roomId").map { }
}

@kotlinx.serialization.Serializable
data class RtcKickResult(val enforced: Boolean = false, val code: String? = null)
