package com.sitandtalk.core.data

import android.content.Context
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.CallState
import com.sitandtalk.core.model.MatchMessage
import com.sitandtalk.core.model.TalkMode
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the call screen and the ongoing-call bar need. */
data class ActiveCall(
    val call: CallState,
    val messages: List<MatchMessage> = emptyList(),
    val rtcJoinFailed: Boolean = false,
    val lastError: AppException? = null,
    val busy: Boolean = false,
)

/**
 * Owns the lifecycle of the current call across screens: navigating away never ends it.
 * Server state is authoritative; RTC follows it. Heartbeats keep the session alive and give a polling
 * fallback when Realtime is unavailable.
 */
@Singleton
class ActiveCallController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: CallRepository,
    private val rtc: RtcManager,
    private val arbiter: RtcArbiter,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _active = MutableStateFlow<ActiveCall?>(null)
    val active: StateFlow<ActiveCall?> = _active.asStateFlow()

    val rtcState: StateFlow<RtcState> get() = rtc.state

    private val mutex = Mutex()
    private var loops: Job? = null
    private var joinedChannel: String? = null
    private var videoWanted = false

    init {
        scope.launch { CallService.endActions.collect { end("hangup") } }
        scope.launch {
            rtc.events.collect { event ->
                val sessionId = _active.value?.call?.sessionId ?: return@collect
                when (event) {
                    RtcEvent.JoinedChannel -> runCatching { apply(repository.markJoined(sessionId)) }
                    RtcEvent.TokenWillExpire, RtcEvent.TokenExpired -> renewToken(sessionId)
                    is RtcEvent.Kicked -> end("rtc_failed")
                    else -> Unit
                }
            }
        }
    }

    /** Called when a session exists (match accepted, direct call placed/answered, or app restart). */
    fun start(initial: CallState) {
        scope.launch {
            mutex.withLock {
                if (_active.value?.call?.sessionId == initial.sessionId) return@withLock
                arbiter.acquire(RtcArbiter.Owner.Call) { releaseRtc() }
                _active.value = ActiveCall(initial)
                loops?.cancel()
                loops = scope.launch { runLoops(initial.sessionId) }
            }
            apply(initial)
        }
    }

    /** Restores a live call after process death. */
    suspend fun resumeIfAny(): Boolean {
        val current = try {
            repository.currentCall()
        } catch (e: Exception) {
            SafeLog.error("call", "resume_failed", e)
            null
        } ?: return false
        start(current)
        return true
    }

    private suspend fun runLoops(sessionId: String) {
        scope.launch {
            repository.sessionChanges(sessionId).collectLatest { refresh(sessionId) }
        }
        scope.launch {
            repository.matchMessageChanges(sessionId).collectLatest { loadMessages(sessionId) }
        }
        while (scope.isActive && _active.value?.call?.sessionId == sessionId && _active.value?.call?.isEnded == false) {
            try {
                apply(repository.heartbeat(sessionId))
            } catch (e: Exception) {
                val mapped = e.toAppException()
                if (mapped.code == "not_found") {
                    onEnded()
                    return
                }
                _active.update { it?.copy(lastError = mapped) }
            }
            delay(5_000)
        }
    }

    private suspend fun refresh(sessionId: String) {
        try {
            apply(repository.state(sessionId))
        } catch (e: Exception) {
            SafeLog.error("call", "refresh_failed", e)
        }
    }

    private suspend fun loadMessages(sessionId: String) {
        val call = _active.value?.call ?: return
        if (call.mode != TalkMode.Text && call.kind != "random") return
        runCatching { repository.matchMessages(sessionId) }.onSuccess { list ->
            _active.update { it?.copy(messages = list) }
        }
    }

    private suspend fun apply(state: CallState) {
        val previous = _active.value?.call
        _active.update { (it ?: ActiveCall(state)).copy(call = state, lastError = null) }
        if (state.isEnded || state.iLeft) {
            onEnded()
            return
        }
        if (state.isRandom && (state.mode == TalkMode.Text || previous?.mode == TalkMode.Text)) loadMessages(state.sessionId)
        if (state.needsRtc) {
            ensureRtc(state)
        }
        if (joinedChannel != null && previous != null && previous.mode != state.mode) {
            rtc.setVideo(state.mode == TalkMode.Video && videoWanted)
        }
    }

    private suspend fun ensureRtc(state: CallState) {
        if (joinedChannel != null) return
        if (!rtc.hasMicPermission()) {
            _active.update { it?.copy(lastError = AppException("mic_permission")) }
            return
        }
        try {
            val credentials = repository.rtcToken("call", state.sessionId)
            val video = state.mode == TalkMode.Video && rtc.hasCameraPermission()
            videoWanted = video
            val result = rtc.join(credentials, video = video, startWithMicOn = true)
            if (result != 0) throw AppException("rtc_failed")
            CallService.start(context, RtcSessionInfo(RtcSessionInfo.Kind.Call, state.peerProfile?.displayName ?: state.peerAlias.orEmpty(), video))
            joinedChannel = credentials.channelName
        } catch (e: Exception) {
            val mapped = e.toAppException()
            SafeLog.error("call", "rtc_join_failed", e)
            _active.update { it?.copy(rtcJoinFailed = true, lastError = mapped) }
            CallService.stop(context)
        }
    }

    private suspend fun renewToken(sessionId: String) {
        try {
            val credentials = repository.rtcToken("call", sessionId)
            rtc.renewToken(credentials.token)
        } catch (e: Exception) {
            // Authorization was withdrawn (call ended, block, restriction): leave instead of lingering.
            val mapped = e.toAppException()
            if (mapped.code in setOf("call_ended", "not_found", "account_restricted")) end("rtc_failed")
        }
    }

    fun retryRtc() {
        val call = _active.value?.call ?: return
        _active.update { it?.copy(rtcJoinFailed = false, lastError = null) }
        scope.launch { ensureRtc(call) }
    }

    fun setMicMuted(muted: Boolean) = rtc.setMicMuted(muted)
    fun setSpeaker(on: Boolean) = rtc.setSpeakerphone(on)
    fun switchCamera() = rtc.switchCamera()
    fun setCameraOff(off: Boolean) = rtc.setCameraOff(off)

    fun setLocalVideo(enabled: Boolean) {
        videoWanted = enabled
        rtc.setVideo(enabled && _active.value?.call?.mode == TalkMode.Video)
    }

    fun requestExtension() = action { repository.requestExtension(it) }
    fun withdrawExtension() = action { repository.withdrawExtension(it) }
    fun requestMode(mode: TalkMode) = action { repository.requestMode(it, mode) }

    fun sendMessage(body: String) {
        val sessionId = _active.value?.call?.sessionId ?: return
        scope.launch {
            try {
                repository.sendMatchMessage(sessionId, body)
                loadMessages(sessionId)
            } catch (e: Exception) {
                _active.update { it?.copy(lastError = e.toAppException()) }
            }
        }
    }

    fun typing() {
        val sessionId = _active.value?.call?.sessionId ?: return
        scope.launch { repository.setTyping(sessionId) }
    }

    fun answer(accept: Boolean) = action { repository.answer(it, accept) }

    fun end(reason: String = "hangup") {
        val sessionId = _active.value?.call?.sessionId ?: return
        scope.launch {
            // Leave RTC first so the microphone stops immediately, even if the network is down.
            releaseRtc()
            try {
                apply(repository.end(sessionId, reason))
            } catch (e: Exception) {
                SafeLog.error("call", "end_failed", e)
                _active.update { it?.copy(call = it.call.copy(status = "ended", endReason = reason)) }
                onEnded()
            }
        }
    }

    /** Clears the finished call once the post-call screen is done. */
    fun dismiss() {
        val current = _active.value ?: return
        if (current.call.isEnded || current.call.iLeft) {
            loops?.cancel()
            _active.value = null
        }
    }

    private fun action(block: suspend (String) -> CallState) {
        val sessionId = _active.value?.call?.sessionId ?: return
        scope.launch {
            _active.update { it?.copy(busy = true) }
            try {
                apply(block(sessionId))
            } catch (e: Exception) {
                _active.update { it?.copy(lastError = e.toAppException()) }
            } finally {
                _active.update { it?.copy(busy = false) }
            }
        }
    }

    private suspend fun onEnded() {
        releaseRtc()
        arbiter.releaseIfOwner(RtcArbiter.Owner.Call)
    }

    private fun releaseRtc() {
        if (joinedChannel != null || rtc.state.value.isInChannel) rtc.leave()
        joinedChannel = null
        videoWanted = false
        CallService.stop(context)
    }

    fun consumeError() {
        _active.update { it?.copy(lastError = null) }
    }
}
