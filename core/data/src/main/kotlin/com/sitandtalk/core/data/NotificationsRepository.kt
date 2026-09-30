package com.sitandtalk.core.data

import com.sitandtalk.core.model.NotificationItem
import com.sitandtalk.core.model.NotificationPreferences
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.network.Api
import com.sitandtalk.core.network.apiCall
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationsRepository @Inject constructor(
    private val api: Api,
    private val auth: AuthRepository,
    private val realtime: RealtimeStreams,
    val push: PushRegistrar,
) {
    suspend fun list(): List<NotificationItem> {
        val uid = auth.requireUserId()
        return apiCall {
            api.client.from("notifications").select(
                Columns.list("id", "user_id", "kind", "actor_id", "entity_type", "entity_id", "data", "expires_at", "read_at", "created_at"),
            ) {
                filter { eq("user_id", uid) }
                order("created_at", Order.DESCENDING)
                limit(100)
            }.decodeList<NotificationItem>()
        }.filter { n ->
            // An expired call invitation is history, not a live call.
            val expires = ServerTime.parse(n.expiresAt)
            !(n.kind == "incoming_call" && expires != null && expires.isBefore(ServerTime.now()))
        }
    }

    suspend fun unreadCount(): Int = list().count { it.readAt == null }

    suspend fun markRead(ids: List<String>? = null) = api.rpcUnit("mark_notifications_read") {
        if (ids == null) put("p_ids", kotlinx.serialization.json.JsonNull) else put("p_ids", JsonArray(ids.map { JsonPrimitive(it) }))
    }

    fun changes(): Flow<Unit> = realtime.changes("notifications", "user_id=eq.${auth.requireUserId()}").map { }

    suspend fun preferences(): NotificationPreferences {
        val uid = auth.requireUserId()
        return apiCall {
            api.client.from("notification_preferences").select {
                filter { eq("user_id", uid) }
            }.decodeSingleOrNull<NotificationPreferences>()
        } ?: NotificationPreferences(userId = uid)
    }

    suspend fun savePreferences(prefs: NotificationPreferences) {
        apiCall {
            api.client.from("notification_preferences").upsert(buildJsonObject {
                put("user_id", auth.requireUserId())
                put("friend_requests", prefs.friendRequests)
                put("messages", prefs.messages)
                put("calls", prefs.calls)
                put("room_invites", prefs.roomInvites)
                put("events", prefs.events)
                put("comments", prefs.comments)
                put("purchases", prefs.purchases)
                put("show_previews", prefs.showPreviews)
            })
        }
    }
}
