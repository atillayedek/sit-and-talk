package com.sitandtalk.core.data

import com.sitandtalk.core.model.CallState
import com.sitandtalk.core.model.FeedbackResult
import com.sitandtalk.core.model.MatchMessage
import com.sitandtalk.core.model.QueueState
import com.sitandtalk.core.model.RtcCredentials
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.model.TalkIntent
import com.sitandtalk.core.model.TalkMode
import com.sitandtalk.core.network.Api
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val TalkMode.wire get() = name.lowercase()

@Singleton
class CallRepository @Inject constructor(
    private val api: Api,
    private val realtime: RealtimeStreams,
    private val auth: AuthRepository,
) {
    suspend fun joinQueue(mode: TalkMode, intent: TalkIntent, alias: String, languages: List<String>, strictLanguage: Boolean): QueueState =
        api.rpc<QueueState>("join_queue") {
            put("p_mode", mode.wire)
            put("p_intent", intent.wire)
            put("p_alias", alias.trim())
            put("p_languages", JsonArray(languages.map { JsonPrimitive(it) }))
            put("p_strict_language", strictLanguage)
        }.also { ServerTime.sync(it.serverNow) }

    suspend fun queueHeartbeat(): QueueState = api.rpc<QueueState>("queue_heartbeat").also { ServerTime.sync(it.serverNow) }
    suspend fun leaveQueue(): QueueState = api.rpc("leave_queue")
    suspend fun respondMatch(matchId: String, accept: Boolean): QueueState = api.rpc("respond_match") {
        put("p_match", matchId)
        put("p_accept", accept)
    }

    suspend fun currentCall(): CallState? = api.rpc<CallState?>("current_call")?.also { ServerTime.sync(it.serverNow) }
    suspend fun state(sessionId: String): CallState = synced(api.rpc("get_call_state") { put("p_session", sessionId) })
    suspend fun heartbeat(sessionId: String): CallState = synced(api.rpc("call_heartbeat") { put("p_session", sessionId) })
    suspend fun markJoined(sessionId: String): CallState = synced(api.rpc("mark_rtc_joined") { put("p_session", sessionId) })
    suspend fun end(sessionId: String, reason: String): CallState = synced(api.rpc("end_call") {
        put("p_session", sessionId)
        put("p_reason", reason)
    })
    suspend fun requestExtension(sessionId: String): CallState = synced(api.rpc("request_extension") { put("p_session", sessionId) })
    suspend fun withdrawExtension(sessionId: String): CallState = synced(api.rpc("withdraw_extension") { put("p_session", sessionId) })
    suspend fun requestMode(sessionId: String, mode: TalkMode): CallState = synced(api.rpc("request_mode_change") {
        put("p_session", sessionId)
        put("p_mode", mode.wire)
    })

    suspend fun feedback(sessionId: String, wantsAgain: Boolean, rating: Int?): FeedbackResult = api.rpc("submit_call_feedback") {
        put("p_session", sessionId)
        put("p_wants_again", wantsAgain)
        if (rating != null) put("p_rating", rating)
    }

    suspend fun startDirect(userId: String, mode: TalkMode): CallState = synced(api.rpc("start_direct_call") {
        put("p_user", userId)
        put("p_mode", mode.wire)
    })

    suspend fun answer(sessionId: String, accept: Boolean): CallState = synced(api.rpc("answer_call") {
        put("p_session", sessionId)
        put("p_accept", accept)
    })

    suspend fun matchMessages(sessionId: String): List<MatchMessage> = api.rpc("list_match_messages") { put("p_session", sessionId) }

    suspend fun sendMatchMessage(sessionId: String, body: String, id: String = UUID.randomUUID().toString()) =
        api.rpcUnit("send_match_message") {
            put("p_session", sessionId)
            put("p_id", id)
            put("p_body", body)
        }

    suspend fun setTyping(sessionId: String) = runCatching { api.rpcUnit("set_match_typing") { put("p_session", sessionId) } }

    suspend fun blockPeer(sessionId: String) = api.rpcUnit("block_call_peer") { put("p_session", sessionId) }
    suspend fun friendRequestToPeer(sessionId: String) = api.rpcUnit("send_friend_request_to_call_peer") { put("p_session", sessionId) }

    suspend fun rtcToken(kind: String, id: String): RtcCredentials = api.function("agora-token") {
        put("kind", kind)
        put("id", id)
    }

    fun queueChanges(): Flow<Unit> = realtime.changes("matchmaking_queue", "user_id=eq.${auth.requireUserId()}").map { }
    fun sessionChanges(sessionId: String): Flow<Unit> = realtime.changes("call_sessions", "id=eq.$sessionId").map { }
    fun matchMessageChanges(sessionId: String): Flow<Unit> = realtime.changes("match_messages", "session_id=eq.$sessionId").map { }

    private fun synced(state: CallState): CallState = state.also { ServerTime.sync(it.serverNow) }
}
