package com.sitandtalk.core.data

import android.content.Context
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.RoomMessage
import com.sitandtalk.core.model.RoomReaction
import com.sitandtalk.core.model.RoomRole
import com.sitandtalk.core.model.RoomState
import com.sitandtalk.core.network.SafeLog
import com.sitandtalk.core.network.toAppException
import com.sitandtalk.core.rtc.CallService
import com.sitandtalk.core.rtc.RtcEvent
import com.sitandtalk.core.rtc.RtcManager
import com.sitandtalk.core.rtc.RtcSessionInfo
import com.sitandtalk.core.rtc.RtcState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class ActiveRoom(
    val room: RoomState,
    val messages: List<RoomMessage> = emptyList(),
    val reactions: List<RoomReaction> = emptyList(),
    val rtcFailed: Boolean = false,
    val removed: Boolean = false,
    val lastError: AppException? = null,
    val info: String? = null,
)

/**
 * Keeps the user's current room alive across screens. The database decides membership and role;
 * the Agora token role follows it (listeners receive subscriber tokens).
 */
@Singleton
class ActiveRoomController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: RoomsRepository,
    private val rtc: RtcManager,
    private val arbiter: RtcArbiter,
    private val auth: AuthRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _active = MutableStateFlow<ActiveRoom?>(null)
    val active: StateFlow<ActiveRoom?> = _active.asStateFlow()
    val rtcState: StateFlow<RtcState> get() = rtc.state

    private var jobs = mutableListOf<Job>()
    private var joinedChannel: String? = null
    private var currentRole: RoomRole? = null
    private var reactionsSince: Instant = Instant.now()

    init {
        scope.launch { CallService.endActions.collect { if (_active.value != null) leave() } }
        scope.launch {
            rtc.events.collect { event ->
                val roomId = _active.value?.room?.id ?: return@collect
                when (event) {
                    RtcEvent.TokenWillExpire, RtcEvent.TokenExpired -> renew(roomId)
                    is RtcEvent.Kicked -> onRemoved()
                    else -> Unit
                }
            }
        }
    }

    fun enter(room: RoomState) {
        scope.launch {
            if (_active.value?.room?.id == room.id) {
                apply(room)
                return@launch
            }
            arbiter.acquire(RtcArbiter.Owner.Room) { leaveSilently() }
            stopJobs()
            _active.value = ActiveRoom(room)
            currentRole = null
            reactionsSince = Instant.now()
            apply(room)
            loadMessages(room.id)
            jobs += scope.launch { heartbeatLoop(room.id) }
            jobs += scope.launch { repository.roomChanges(room.id).collectLatest { refresh(room.id) } }
            jobs += scope.launch { repository.messageChanges(room.id).collectLatest { loadMessages(room.id) } }
            jobs += scope.launch { repository.reactionChanges(room.id).collectLatest { loadReactions(room.id) } }
        }
    }

    private suspend fun heartbeatLoop(roomId: String) {
        while (_active.value?.room?.id == roomId) {
            delay(20_000)
            try {
                apply(repository.heartbeat(roomId))
            } catch (e: Exception) {
                val mapped = e.toAppException()
                if (mapped.code == "not_room_member") {
                    onRemoved()
                    return
                }
            }
        }
    }

    private suspend fun refresh(roomId: String) {
        try {
            apply(repository.get(roomId))
        } catch (e: Exception) {
            val mapped = e.toAppException()
            if (mapped.code == "not_found") onRemoved()
        }
    }

    private suspend fun apply(room: RoomState) {
        _active.update { (it ?: ActiveRoom(room)).copy(room = room) }
        val me = auth.currentUserId()
        val myMember = room.members.firstOrNull { it.userId == me }
        if (!room.isOpen) {
            releaseRtc()
            _active.update { it?.copy(removed = true) }
            return
        }
        if (myMember == null) {
            onRemoved()
            return
        }
        if (joinedChannel == null) {
            join(room, myMember.role)
        } else if (currentRole != null && currentRole!!.canSpeak != myMember.role.canSpeak) {
            // Promotion or demotion: fetch a token for the new role; the mic always starts muted.
            runCatching { rtc.updateRole(repository.rtcToken(room.id)) }
        }
        currentRole = myMember.role
        if (myMember.mutedByModerator && !rtc.state.value.micMuted) rtc.setMicMuted(true)
    }

    private suspend fun join(room: RoomState, role: RoomRole) {
        if (role.canSpeak && !rtc.hasMicPermission()) {
            _active.update { it?.copy(lastError = AppException("mic_permission")) }
        }
        try {
            val credentials = repository.rtcToken(room.id)
            val result = rtc.join(credentials, video = false, startWithMicOn = false)
            if (result != 0) throw AppException("rtc_failed")
            CallService.start(context, RtcSessionInfo(RtcSessionInfo.Kind.Room, room.title, video = false))
            joinedChannel = credentials.channelName
            currentRole = role
        } catch (e: Exception) {
            SafeLog.error("room", "rtc_join_failed", e)
            _active.update { it?.copy(rtcFailed = true, lastError = e.toAppException()) }
            CallService.stop(context)
        }
    }

    private suspend fun renew(roomId: String) {
        try {
            rtc.renewToken(repository.rtcToken(roomId).token)
        } catch (e: Exception) {
            onRemoved()
        }
    }

    private suspend fun loadMessages(roomId: String) {
        if (_active.value?.room?.textChatEnabled != true) return
        runCatching { repository.messages(roomId) }.onSuccess { list -> _active.update { it?.copy(messages = list) } }
    }

    private suspend fun loadReactions(roomId: String) {
        runCatching { repository.recentReactions(roomId, reactionsSince) }.onSuccess { list ->
            if (list.isNotEmpty()) {
                _active.update { it?.copy(reactions = (it.reactions + list).takeLast(30)) }
            }
        }
    }

    fun retryRtc() {
        val room = _active.value?.room ?: return
        val role = room.myRole ?: return
        _active.update { it?.copy(rtcFailed = false, lastError = null) }
        scope.launch { join(room, role) }
    }

    fun setMicMuted(muted: Boolean) = act {
        repository.setSelfMuted(it, muted)
        rtc.setMicMuted(muted)
    }

    fun raiseHand(raised: Boolean) = act { repository.raiseHand(it, raised) }
    fun respondSpeakerInvite(accept: Boolean) = act { apply(repository.respondSpeakerInvite(it, accept)) }
    fun inviteToSpeak(userId: String) = act { repository.inviteToSpeak(it, userId) }
    fun declineHand(userId: String) = act { repository.declineHand(it, userId) }
    fun setRole(userId: String, role: RoomRole) = act { repository.setRole(it, userId, role) }
    fun moderatorMute(userId: String, muted: Boolean) = act { repository.moderatorMute(it, userId, muted) }

    fun kick(userId: String, ban: Boolean, reason: String?) = act {
        val enforced = repository.kick(it, userId, ban, reason)
        if (!enforced) _active.update { a -> a?.copy(info = "agora_rest_not_configured") }
    }

    fun sendReaction(kind: String) = act { repository.sendReaction(it, kind) }
    fun sendMessage(body: String) = act {
        repository.sendMessage(it, body)
        loadMessages(it)
    }

    fun setFavorite(favorite: Boolean) = act {
        repository.setFavorite(it, favorite)
        refresh(it)
    }

    fun update(title: String, description: String, handRaise: Boolean, textChat: Boolean) = act {
        apply(repository.update(it, title, description, handRaise, textChat))
    }

    fun close() = act {
        repository.close(it)
        releaseRtc()
        _active.update { a -> a?.copy(removed = true) }
    }

    fun leave() {
        val roomId = _active.value?.room?.id ?: return
        scope.launch {
            releaseRtc()
            runCatching { repository.leave(roomId) }
            stopJobs()
            _active.value = null
            arbiter.releaseIfOwner(RtcArbiter.Owner.Room)
        }
    }

    fun dismissRemoved() {
        if (_active.value?.removed == true) {
            stopJobs()
            _active.value = null
        }
    }

    fun consumeMessage() {
        _active.update { it?.copy(lastError = null, info = null) }
    }

    private suspend fun leaveSilently() {
        val roomId = _active.value?.room?.id
        releaseRtc()
        if (roomId != null) runCatching { repository.leave(roomId) }
        stopJobs()
        _active.value = null
    }

    private suspend fun onRemoved() {
        releaseRtc()
        _active.update { it?.copy(removed = true) }
        arbiter.releaseIfOwner(RtcArbiter.Owner.Room)
    }

    private fun releaseRtc() {
        if (joinedChannel != null) rtc.leave()
        joinedChannel = null
        currentRole = null
        CallService.stop(context)
    }

    private fun stopJobs() {
        jobs.forEach { it.cancel() }
        jobs = mutableListOf()
    }

    private fun act(block: suspend (String) -> Unit) {
        val roomId = _active.value?.room?.id ?: return
        scope.launch {
            try {
                block(roomId)
            } catch (e: Exception) {
                _active.update { it?.copy(lastError = e.toAppException()) }
            }
        }
    }
}
