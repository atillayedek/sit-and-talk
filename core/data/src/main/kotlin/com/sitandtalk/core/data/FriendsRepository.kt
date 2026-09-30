package com.sitandtalk.core.data

import com.sitandtalk.core.model.BlockedUser
import com.sitandtalk.core.model.Friend
import com.sitandtalk.core.model.FriendRequestItem
import com.sitandtalk.core.network.Api
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FriendsRepository @Inject constructor(
    private val api: Api,
    private val realtime: RealtimeStreams,
    private val auth: AuthRepository,
) {
    suspend fun friends(): List<Friend> = api.rpc("list_friends")
    suspend fun requests(): List<FriendRequestItem> = api.rpc("list_friend_requests")
    suspend fun blocked(): List<BlockedUser> = api.rpc("list_blocked_users")

    suspend fun sendRequest(userId: String, source: String = "profile") {
        api.rpcUnit("send_friend_request") {
            put("p_user", userId)
            put("p_source", source)
        }
    }

    suspend fun respond(requestId: String, accept: Boolean) = api.rpcUnit("respond_friend_request") {
        put("p_request", requestId)
        put("p_accept", accept)
    }

    suspend fun cancel(requestId: String) = api.rpcUnit("cancel_friend_request") { put("p_request", requestId) }
    suspend fun remove(userId: String) = api.rpcUnit("remove_friend") { put("p_user", userId) }
    suspend fun setFavorite(userId: String, favorite: Boolean) = api.rpcUnit("set_friend_favorite") {
        put("p_user", userId)
        put("p_favorite", favorite)
    }

    suspend fun block(userId: String) = api.rpcUnit("block_user") { put("p_user", userId) }
    suspend fun unblock(userId: String) = api.rpcUnit("unblock_user") { put("p_user", userId) }

    /** Emits when a friend request addressed to or sent by the current user changes. */
    fun requestChanges(): Flow<Unit> {
        val uid = auth.requireUserId()
        return realtime.changes("friend_requests", "receiver_id=eq.$uid").map { }
    }
}
