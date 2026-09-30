package com.sitandtalk.feature.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitandtalk.core.data.ActiveRoom
import com.sitandtalk.core.data.ActiveRoomController
import com.sitandtalk.core.data.AuthRepository
import com.sitandtalk.core.data.CreateRoomInput
import com.sitandtalk.core.data.ModerationRepository
import com.sitandtalk.core.data.ProfileRepository
import com.sitandtalk.core.data.RoomsRepository
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.Interest
import com.sitandtalk.core.model.LoadState
import com.sitandtalk.core.model.ReportReason
import com.sitandtalk.core.model.ReportTarget
import com.sitandtalk.core.model.RoomEvent
import com.sitandtalk.core.model.RoomRole
import com.sitandtalk.core.model.RoomSummary
import com.sitandtalk.core.model.RoomVisibility
import com.sitandtalk.core.network.toAppException
import com.sitandtalk.core.rtc.RtcState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

enum class RoomsTab { Active, Favorites, Events }

data class RoomsUiState(
    val tab: RoomsTab = RoomsTab.Active,
    val query: String = "",
    val topic: String? = null,
    val language: String? = null,
    val rooms: LoadState<List<RoomSummary>> = LoadState.Loading,
    val canLoadMore: Boolean = false,
    val events: LoadState<List<RoomEvent>> = LoadState.Loading,
    val interests: List<Interest> = emptyList(),
    val joining: String? = null,
    val passwordPrompt: RoomSummary? = null,
    val error: AppException? = null,
    val enteredRoomId: String? = null,
    val activeRoomId: String? = null,
)

@HiltViewModel
class RoomsViewModel @Inject constructor(
    private val rooms: RoomsRepository,
    private val profiles: ProfileRepository,
    private val controller: ActiveRoomController,
) : ViewModel() {
    private val _state = MutableStateFlow(RoomsUiState())
    val state: StateFlow<RoomsUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch { runCatching { profiles.interests() }.onSuccess { list -> _state.update { it.copy(interests = list) } } }
        viewModelScope.launch { controller.active.collect { a -> _state.update { it.copy(activeRoomId = if (a != null && !a.removed) a.room.id else null) } } }
        refresh()
    }

    fun setTab(tab: RoomsTab) {
        _state.update { it.copy(tab = tab) }
        refresh()
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            refresh()
        }
    }

    fun setTopic(slug: String?) {
        _state.update { it.copy(topic = slug) }
        refresh()
    }

    fun setLanguage(code: String?) {
        _state.update { it.copy(language = code) }
        refresh()
    }

    fun refresh() {
        val s = _state.value
        viewModelScope.launch {
            if (s.tab == RoomsTab.Events) {
                _state.update { it.copy(events = LoadState.Loading) }
                _state.update {
                    it.copy(events = try {
                        LoadState.Success(rooms.events())
                    } catch (e: Exception) {
                        LoadState.Failure(e.toAppException())
                    })
                }
                return@launch
            }
            _state.update { it.copy(rooms = LoadState.Loading) }
            try {
                val list = rooms.list(s.query, s.topic, s.language, s.tab == RoomsTab.Favorites, 0)
                _state.update { it.copy(rooms = LoadState.Success(list), canLoadMore = list.size >= 30) }
            } catch (e: Exception) {
                _state.update { it.copy(rooms = LoadState.Failure(e.toAppException())) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val current = (s.rooms as? LoadState.Success)?.data ?: return
        viewModelScope.launch {
            runCatching { rooms.list(s.query, s.topic, s.language, s.tab == RoomsTab.Favorites, current.size) }
                .onSuccess { more ->
                    _state.update { it.copy(rooms = LoadState.Success((current + more).distinctBy { r -> r.id }), canLoadMore = more.size >= 30) }
                }
        }
    }

    fun join(room: RoomSummary, password: String? = null, inviteCode: String? = null) {
        if (room.visibility == RoomVisibility.Password && password == null && !room.isMember) {
            _state.update { it.copy(passwordPrompt = room) }
            return
        }
        joinById(room.id, password, inviteCode)
    }

    fun joinById(roomId: String, password: String? = null, inviteCode: String? = null) {
        if (_state.value.joining != null) return
        viewModelScope.launch {
            _state.update { it.copy(joining = roomId, passwordPrompt = null) }
            try {
                val joined = rooms.join(roomId, password, inviteCode)
                controller.enter(joined)
                _state.update { it.copy(enteredRoomId = roomId) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            } finally {
                _state.update { it.copy(joining = null) }
            }
        }
    }

    fun dismissPassword() = _state.update { it.copy(passwordPrompt = null) }
    fun consumeNavigation() = _state.update { it.copy(enteredRoomId = null) }
    fun consumeError() = _state.update { it.copy(error = null) }

    fun setEventSubscription(event: RoomEvent, subscribed: Boolean) {
        viewModelScope.launch {
            try {
                rooms.setEventSubscription(event.id, subscribed)
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            }
        }
    }

    fun cancelEvent(event: RoomEvent) {
        viewModelScope.launch {
            try {
                rooms.cancelEvent(event.id)
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

data class CreateRoomState(
    val title: String = "",
    val description: String = "",
    val topic: String? = null,
    val tags: String = "",
    val language: String = "tr",
    val visibility: RoomVisibility = RoomVisibility.Public,
    val password: String = "",
    val maxParticipants: Int = 30,
    val maxSpeakers: Int = 6,
    val handRaise: Boolean = true,
    val textChat: Boolean = true,
    val interests: List<Interest> = emptyList(),
    val submitting: Boolean = false,
    val error: AppException? = null,
    val createdRoomId: String? = null,
    val eventId: String? = null,
    // Event planning
    val eventStartsAt: Instant? = null,
    val eventCreated: Boolean = false,
) {
    val valid get() = title.trim().length in 3..60 && (visibility != RoomVisibility.Password || password.length in 4..64)
}

@HiltViewModel
class CreateRoomViewModel @Inject constructor(
    private val rooms: RoomsRepository,
    private val profiles: ProfileRepository,
    private val controller: ActiveRoomController,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateRoomState())
    val state: StateFlow<CreateRoomState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { profiles.interests() }.onSuccess { list -> _state.update { it.copy(interests = list) } }
            profiles.me.value?.languages?.firstOrNull()?.let { lang -> _state.update { it.copy(language = lang) } }
        }
    }

    fun update(transform: (CreateRoomState) -> CreateRoomState) = _state.update(transform)
    fun forEvent(eventId: String, title: String) = _state.update { it.copy(eventId = eventId, title = title) }
    fun consumeError() = _state.update { it.copy(error = null) }

    fun submit() {
        val s = _state.value
        if (!s.valid || s.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true) }
            try {
                val room = rooms.create(
                    CreateRoomInput(
                        title = s.title,
                        description = s.description,
                        topic = s.topic,
                        tags = s.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(5),
                        language = s.language,
                        visibility = s.visibility,
                        password = s.password.takeIf { s.visibility == RoomVisibility.Password },
                        maxParticipants = s.maxParticipants,
                        maxSpeakers = s.maxSpeakers,
                        handRaiseRequired = s.handRaise,
                        textChatEnabled = s.textChat,
                        eventId = s.eventId,
                    ),
                )
                controller.enter(room)
                _state.update { it.copy(createdRoomId = room.id) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }

    fun submitEvent() {
        val s = _state.value
        val start = s.eventStartsAt ?: return
        if (s.title.trim().length < 3 || s.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true) }
            try {
                rooms.createEvent(s.title.trim(), s.description.trim(), s.topic, s.language, start)
                _state.update { it.copy(eventCreated = true) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

data class RoomExtras(
    val myUserId: String? = null,
    val reportSending: Boolean = false,
    val message: String? = null,
    val error: AppException? = null,
    val inviteLink: String? = null,
)

@HiltViewModel
class RoomViewModel @Inject constructor(
    private val controller: ActiveRoomController,
    private val rooms: RoomsRepository,
    private val moderation: ModerationRepository,
    auth: AuthRepository,
) : ViewModel() {
    val active: StateFlow<ActiveRoom?> = controller.active
    val rtc: StateFlow<RtcState> = controller.rtcState

    private val _extras = MutableStateFlow(RoomExtras(myUserId = auth.currentUserId()))
    val extras: StateFlow<RoomExtras> = _extras.asStateFlow()

    private val roomId get() = active.value?.room?.id

    fun toggleMic() = controller.setMicMuted(!rtc.value.micMuted)
    fun raiseHand(raised: Boolean) = controller.raiseHand(raised)
    fun respondInvite(accept: Boolean) = controller.respondSpeakerInvite(accept)
    fun inviteToSpeak(userId: String) = controller.inviteToSpeak(userId)
    fun declineHand(userId: String) = controller.declineHand(userId)
    fun setRole(userId: String, role: RoomRole) = controller.setRole(userId, role)
    fun moderatorMute(userId: String, muted: Boolean) = controller.moderatorMute(userId, muted)
    fun kick(userId: String, ban: Boolean) = controller.kick(userId, ban, null)
    fun react(kind: String) = controller.sendReaction(kind)
    fun sendMessage(text: String) {
        if (text.isNotBlank()) controller.sendMessage(text.trim())
    }
    fun setFavorite(favorite: Boolean) = controller.setFavorite(favorite)
    fun close() = controller.close()
    fun leave() = controller.leave()
    fun retry() = controller.retryRtc()
    fun dismissRemoved() = controller.dismissRemoved()
    fun consumeControllerMessage() = controller.consumeMessage()

    fun report(target: ReportTarget, targetId: String, reason: ReportReason, details: String) {
        viewModelScope.launch {
            _extras.update { it.copy(reportSending = true) }
            try {
                moderation.report(target, targetId, reason, details)
                _extras.update { it.copy(message = "reported") }
            } catch (e: Exception) {
                _extras.update { it.copy(error = e.toAppException()) }
            } finally {
                _extras.update { it.copy(reportSending = false) }
            }
        }
    }

    fun createInviteLink() {
        val id = roomId ?: return
        viewModelScope.launch {
            try {
                val invite = rooms.createInvite(id, null)
                _extras.update { it.copy(inviteLink = "sitandtalk://room/$id" + (invite.code?.let { c -> "?code=$c" } ?: "")) }
            } catch (e: Exception) {
                _extras.update { it.copy(error = e.toAppException()) }
            }
        }
    }

    fun consume() = _extras.update { it.copy(message = null, error = null, inviteLink = null) }
}
