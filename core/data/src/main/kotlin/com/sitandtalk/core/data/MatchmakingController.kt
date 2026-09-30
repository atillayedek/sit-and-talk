package com.sitandtalk.core.data

import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.QueueState
import com.sitandtalk.core.model.TalkIntent
import com.sitandtalk.core.model.TalkMode
import com.sitandtalk.core.network.SafeLog
import com.sitandtalk.core.network.toAppException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MatchPhase {
    data object Idle : MatchPhase
    data object Joining : MatchPhase
    data class Searching(val mode: TalkMode) : MatchPhase
    data class Found(val state: QueueState) : MatchPhase
    data class WaitingForPeer(val state: QueueState) : MatchPhase
    data class Failed(val error: AppException) : MatchPhase
}

/**
 * Drives the server-side queue: join, heartbeat (every 4 s, also triggers pairing), accept/decline,
 * and hands a created session to [ActiveCallController]. No match is ever simulated on the device.
 */
@Singleton
class MatchmakingController @Inject constructor(
    private val repository: CallRepository,
    private val calls: ActiveCallController,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _phase = MutableStateFlow<MatchPhase>(MatchPhase.Idle)
    val phase: StateFlow<MatchPhase> = _phase.asStateFlow()

    private var loop: Job? = null
    private var realtimeJob: Job? = null

    fun join(mode: TalkMode, intent: TalkIntent, alias: String, languages: List<String>, strictLanguage: Boolean) {
        if (_phase.value is MatchPhase.Joining) return
        _phase.value = MatchPhase.Joining
        scope.launch {
            try {
                handle(repository.joinQueue(mode, intent, alias, languages, strictLanguage))
                startLoops()
            } catch (e: Exception) {
                _phase.value = MatchPhase.Failed(e.toAppException())
            }
        }
    }

    private fun startLoops() {
        loop?.cancel()
        loop = scope.launch {
            while (true) {
                delay(4_000)
                val phase = _phase.value
                if (phase is MatchPhase.Idle || phase is MatchPhase.Failed) break
                try {
                    handle(repository.queueHeartbeat())
                } catch (e: Exception) {
                    val mapped = e.toAppException()
                    // Transient network problems keep the search alive; the server drops us after 45 s.
                    if (mapped.code != AppException.NETWORK && mapped.code != AppException.TIMEOUT) {
                        _phase.value = MatchPhase.Failed(mapped)
                        break
                    }
                }
            }
        }
        realtimeJob?.cancel()
        realtimeJob = scope.launch {
            repository.queueChanges().collectLatest {
                runCatching { handle(repository.queueHeartbeat()) }
            }
        }
    }

    private suspend fun handle(state: QueueState) {
        val sessionId = state.sessionId
        when {
            state.isInSession && sessionId != null -> {
                stopLoops()
                _phase.value = MatchPhase.Idle
                runCatching { calls.start(repository.state(sessionId)) }
                    .onFailure { SafeLog.error("match", "session_load_failed", it) }
            }
            state.isMatched -> _phase.value = if (state.iAccepted) MatchPhase.WaitingForPeer(state) else MatchPhase.Found(state)
            state.isWaiting -> _phase.value = MatchPhase.Searching(state.mode ?: TalkMode.Voice)
            state.isIdle -> {
                if (_phase.value !is MatchPhase.Failed) {
                    // Match expired/declined on the other side and we were not requeued.
                    stopLoops()
                    _phase.value = MatchPhase.Idle
                }
            }
        }
    }

    fun respond(accept: Boolean) {
        val matchId = when (val p = _phase.value) {
            is MatchPhase.Found -> p.state.matchId
            is MatchPhase.WaitingForPeer -> p.state.matchId
            else -> null
        } ?: return
        scope.launch {
            try {
                val state = repository.respondMatch(matchId, accept)
                if (!accept) {
                    stopLoops()
                    _phase.value = MatchPhase.Idle
                } else {
                    handle(state)
                }
            } catch (e: Exception) {
                val mapped = e.toAppException()
                if (mapped.code == "match_expired") {
                    // Back to searching if the server kept us in the queue.
                    runCatching { handle(repository.queueHeartbeat()) }
                } else {
                    _phase.value = MatchPhase.Failed(mapped)
                }
            }
        }
    }

    fun cancel() {
        stopLoops()
        _phase.value = MatchPhase.Idle
        scope.launch { runCatching { repository.leaveQueue() } }
    }

    fun clearError() {
        if (_phase.value is MatchPhase.Failed) _phase.value = MatchPhase.Idle
    }

    private fun stopLoops() {
        loop?.cancel()
        loop = null
        realtimeJob?.cancel()
        realtimeJob = null
    }
}
