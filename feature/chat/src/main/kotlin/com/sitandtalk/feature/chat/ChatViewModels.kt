package com.sitandtalk.feature.chat

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitandtalk.core.data.AuthRepository
import com.sitandtalk.core.data.ChatItem
import com.sitandtalk.core.data.ChatRepository
import com.sitandtalk.core.data.ConversationDetails
import com.sitandtalk.core.data.FriendsRepository
import com.sitandtalk.core.data.ModerationRepository
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.ConversationItem
import com.sitandtalk.core.model.ConversationMember
import com.sitandtalk.core.model.Friend
import com.sitandtalk.core.model.LoadState
import com.sitandtalk.core.model.MessageReaction
import com.sitandtalk.core.model.ReportReason
import com.sitandtalk.core.model.ReportTarget
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.network.toAppException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import javax.inject.Inject

data class ConversationsState(
    val archived: Boolean = false,
    val items: LoadState<List<ConversationItem>> = LoadState.Loading,
    val offline: Boolean = false,
    val error: AppException? = null,
)

@HiltViewModel
class ConversationsViewModel @Inject constructor(private val chat: ChatRepository) : ViewModel() {
    private val _state = MutableStateFlow(ConversationsState())
    val state: StateFlow<ConversationsState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { runCatching { chat.conversationListChanges().collect { refresh(silent = true) } } }
        viewModelScope.launch {
            chat.markDelivered()
            chat.flushOutbox()
        }
    }

    fun setArchived(archived: Boolean) {
        _state.update { it.copy(archived = archived) }
        refresh()
    }

    fun refresh(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(items = LoadState.Loading) }
            try {
                val list = chat.refreshConversations(_state.value.archived)
                _state.update { it.copy(items = LoadState.Success(list), offline = false) }
            } catch (e: Exception) {
                val mapped = e.toAppException()
                if (mapped.code == AppException.NETWORK && !_state.value.archived) {
                    // Offline: show the last list we received from the server, clearly labeled.
                    val cached = chat.cachedConversations().first()
                    _state.update { it.copy(items = if (cached.isEmpty()) LoadState.Failure(mapped) else LoadState.Success(cached, fromCache = true), offline = true) }
                } else {
                    _state.update { it.copy(items = LoadState.Failure(mapped)) }
                }
            }
        }
    }

    fun setPinned(c: ConversationItem) = act { chat.updateSettings(c.id, pinned = !c.pinned) }
    fun setArchivedFor(c: ConversationItem) = act { chat.updateSettings(c.id, archived = !c.archived) }
    fun toggleMute(c: ConversationItem) = act {
        if (c.muted) chat.updateSettings(c.id, clearMute = true)
        else chat.updateSettings(c.id, mutedUntil = Instant.now().plusSeconds(8 * 3600).toString())
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                refresh(silent = true)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

data class ChatUiState(
    val conversation: ConversationItem? = null,
    val details: ConversationDetails? = null,
    val loading: Boolean = true,
    val loadingOlder: Boolean = false,
    val canLoadOlder: Boolean = false,
    val replyTo: ChatItem.Remote? = null,
    val editing: ChatItem.Remote? = null,
    val recording: Boolean = false,
    val searchQuery: String? = null,
    val error: AppException? = null,
    val info: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val chat: ChatRepository,
    private val auth: AuthRepository,
    private val friends: FriendsRepository,
    private val moderation: ModerationRepository,
) : ViewModel() {
    val conversationId: String = checkNotNull(savedState["conversationId"])
    val myId: String? = auth.currentUserId()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val members = MutableStateFlow<List<ConversationMember>>(emptyList())
    private val reactions = MutableStateFlow<Map<String, List<MessageReaction>>>(emptyMap())

    val items: StateFlow<List<ChatItem>> = chat.observeConversation(conversationId, members, reactions)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val peerTyping: StateFlow<Boolean> = members.map { list ->
        list.any { it.userId != myId && ServerTime.parse(it.typingUntil)?.isAfter(ServerTime.now()) == true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStartedAt = 0L
    private var lastTypingPing = 0L

    init {
        load()
        viewModelScope.launch { runCatching { chat.messageChanges(conversationId).collect { refreshLatest() } } }
        viewModelScope.launch { runCatching { chat.memberChanges(conversationId).collect { loadMembers() } } }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val list = chat.refreshConversations(false) + runCatching { chat.refreshConversations(true) }.getOrDefault(emptyList())
                val conv = list.firstOrNull { it.id == conversationId }
                _state.update { it.copy(conversation = conv) }
                loadMembers()
                val page = chat.loadMessages(conversationId)
                loadReactions(page.map { it.id })
                _state.update { it.copy(loading = false, canLoadOlder = page.size >= ChatRepository.PAGE_SIZE) }
                chat.markRead(conversationId)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toAppException()) }
            }
        }
    }

    private suspend fun loadMembers() {
        runCatching { chat.details(conversationId) }.onSuccess { d ->
            members.value = d.members
            _state.update { it.copy(details = d) }
        }
    }

    private suspend fun loadReactions(ids: List<String>) {
        runCatching { chat.reactions(ids) }.onSuccess { list ->
            reactions.update { current -> current + list.groupBy { it.messageId } + ids.filter { id -> list.none { it.messageId == id } }.associateWith { emptyList() } }
        }
    }

    private suspend fun refreshLatest() {
        runCatching {
            val page = chat.loadMessages(conversationId)
            loadReactions(page.map { it.id })
            chat.markRead(conversationId)
        }
    }

    fun loadOlder() {
        val oldest = items.value.filterIsInstance<ChatItem.Remote>().firstOrNull()?.message?.createdAt ?: return
        viewModelScope.launch {
            _state.update { it.copy(loadingOlder = true) }
            try {
                val page = chat.loadMessages(conversationId, oldest)
                loadReactions(page.map { it.id })
                _state.update { it.copy(canLoadOlder = page.size >= ChatRepository.PAGE_SIZE) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            } finally {
                _state.update { it.copy(loadingOlder = false) }
            }
        }
    }

    fun send(text: String) {
        val editing = _state.value.editing
        if (editing != null) {
            act { chat.edit(editing.message.id, text, conversationId) }
            _state.update { it.copy(editing = null) }
            return
        }
        chat.sendText(conversationId, text, _state.value.replyTo?.message?.id)
        _state.update { it.copy(replyTo = null) }
    }

    fun sendImage(uri: Uri) = chat.sendImage(conversationId, uri)

    fun typing() {
        val now = System.currentTimeMillis()
        if (now - lastTypingPing > 3_000) {
            lastTypingPing = now
            viewModelScope.launch { chat.typing(conversationId) }
        }
    }

    fun setReply(item: ChatItem.Remote?) = _state.update { it.copy(replyTo = item, editing = null) }
    fun setEditing(item: ChatItem.Remote?) = _state.update { it.copy(editing = item, replyTo = null) }
    fun retry(id: String) = chat.retry(id)
    fun discard(id: String) = chat.discard(id)
    fun react(item: ChatItem.Remote, emoji: String?) = act {
        chat.react(item.message.id, emoji)
        loadReactions(listOf(item.message.id))
    }
    fun deleteForMe(item: ChatItem.Remote) = act { chat.deleteForMe(item.message.id) }
    fun deleteForAll(item: ChatItem.Remote) = act { chat.deleteForEveryone(item.message.id, conversationId) }
    fun clear() = act { chat.clearHistory(conversationId) }
    fun leaveGroup() = act { myId?.let { chat.removeMember(conversationId, it) } }
    fun blockPeer() = act { _state.value.conversation?.peer?.id?.let { friends.block(it) } }
    fun setSearch(q: String?) = _state.update { it.copy(searchQuery = q) }

    fun report(item: ChatItem.Remote, reason: ReportReason, details: String) = act {
        moderation.report(ReportTarget.Message, item.message.id, reason, details)
        _state.update { it.copy(info = "reported") }
    }

    fun startRecording(): Boolean {
        return try {
            val file = File.createTempFile("voice_", ".m4a", context.cacheDir)
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setMaxDuration(120_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recordFile = file
            recordStartedAt = System.currentTimeMillis()
            _state.update { it.copy(recording = true) }
            true
        } catch (e: Exception) {
            _state.update { it.copy(error = AppException("upload_failed", cause = e), recording = false) }
            false
        }
    }

    fun stopRecording(send: Boolean) {
        val r = recorder ?: return
        val file = recordFile
        val duration = (System.currentTimeMillis() - recordStartedAt).toInt()
        runCatching { r.stop() }
        r.release()
        recorder = null
        _state.update { it.copy(recording = false) }
        if (send && file != null && duration >= 800) {
            chat.sendVoice(conversationId, file, duration)
        } else {
            file?.delete()
        }
    }

    fun consume() = _state.update { it.copy(error = null, info = null) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            }
        }
    }

    override fun onCleared() {
        stopRecording(send = false)
        super.onCleared()
    }
}

// ---------------------------------------------------------------------------------------------

data class NewGroupState(
    val title: String = "",
    val friends: LoadState<List<Friend>> = LoadState.Loading,
    val selected: Set<String> = emptySet(),
    val creating: Boolean = false,
    val createdId: String? = null,
    val error: AppException? = null,
)

@HiltViewModel
class NewGroupViewModel @Inject constructor(private val friends: FriendsRepository, private val chat: ChatRepository) : ViewModel() {
    private val _state = MutableStateFlow(NewGroupState())
    val state: StateFlow<NewGroupState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(friends = try {
                    LoadState.Success(friends.friends())
                } catch (e: Exception) {
                    LoadState.Failure(e.toAppException())
                })
            }
        }
    }

    fun setTitle(v: String) = _state.update { it.copy(title = v.take(60)) }
    fun toggle(id: String) = _state.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }

    fun create() {
        val s = _state.value
        if (s.title.isBlank() || s.selected.isEmpty() || s.creating) return
        viewModelScope.launch {
            _state.update { it.copy(creating = true) }
            try {
                val id = chat.createGroup(s.title, s.selected.toList())
                _state.update { it.copy(createdId = id) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            } finally {
                _state.update { it.copy(creating = false) }
            }
        }
    }
}
