package com.sitandtalk.feature.friends

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sitandtalk.core.data.ActiveCallController
import com.sitandtalk.core.data.CallRepository
import com.sitandtalk.core.data.ChatRepository
import com.sitandtalk.core.data.FriendsRepository
import com.sitandtalk.core.data.ProfileRepository
import com.sitandtalk.core.designsystem.Avatar
import com.sitandtalk.core.designsystem.EmptyState
import com.sitandtalk.core.designsystem.LoadStateContent
import com.sitandtalk.core.designsystem.StTextButton
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.StTopBar
import com.sitandtalk.core.designsystem.errorMessage
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.BlockedUser
import com.sitandtalk.core.model.Friend
import com.sitandtalk.core.model.FriendRequestItem
import com.sitandtalk.core.model.LoadState
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.model.TalkMode
import com.sitandtalk.core.model.UserSummary
import com.sitandtalk.core.network.toAppException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class FriendsTab { Friends, Requests, Blocked }

data class FriendsState(
    val tab: FriendsTab = FriendsTab.Friends,
    val friends: LoadState<List<Friend>> = LoadState.Loading,
    val requests: LoadState<List<FriendRequestItem>> = LoadState.Loading,
    val blocked: LoadState<List<BlockedUser>> = LoadState.Loading,
    val query: String = "",
    val results: List<UserSummary>? = null,
    val error: AppException? = null,
    val openChatId: String? = null,
    val callStarted: Boolean = false,
)

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val friends: FriendsRepository,
    private val profiles: ProfileRepository,
    private val chat: ChatRepository,
    private val calls: CallRepository,
    private val activeCall: ActiveCallController,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(
        FriendsState(tab = savedState.get<String>("tab")?.let { t -> FriendsTab.entries.firstOrNull { it.name.equals(t, true) } } ?: FriendsTab.Friends),
    )
    val state: StateFlow<FriendsState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        refresh()
        viewModelScope.launch { runCatching { friends.requestChanges().collect { loadRequests() } } }
    }

    fun setTab(tab: FriendsTab) {
        _state.update { it.copy(tab = tab) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            when (_state.value.tab) {
                FriendsTab.Friends -> _state.update { it.copy(friends = load { friends.friends() }) }
                FriendsTab.Requests -> loadRequests()
                FriendsTab.Blocked -> _state.update { it.copy(blocked = load { friends.blocked() }) }
            }
        }
    }

    private suspend fun loadRequests() = _state.update { it.copy(requests = load { friends.requests() }) }

    private suspend fun <T> load(block: suspend () -> T): LoadState<T> = try {
        LoadState.Success(block())
    } catch (e: Exception) {
        LoadState.Failure(e.toAppException())
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.trim().length < 2) {
            _state.update { it.copy(results = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            try {
                _state.update { it.copy(results = profiles.searchUsers(q)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            }
        }
    }

    fun respond(request: FriendRequestItem, accept: Boolean) = act { friends.respond(request.id, accept); loadRequests() }
    fun cancel(request: FriendRequestItem) = act { friends.cancel(request.id); loadRequests() }
    fun unblock(user: BlockedUser) = act { friends.unblock(user.id); refresh() }
    fun favorite(friend: Friend) = act { friends.setFavorite(friend.id, !friend.favorite); refresh() }
    fun message(userId: String) = act { _state.update { it.copy(openChatId = chat.openDirect(userId)) } }
    fun call(userId: String, mode: TalkMode) = act {
        activeCall.start(calls.startDirect(userId, mode))
        _state.update { it.copy(callStarted = true) }
    }

    fun consume() = _state.update { it.copy(error = null, openChatId = null, callStarted = false) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            }
        }
    }
}

@Composable
fun FriendsScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onCallStarted: () -> Unit,
    viewModel: FriendsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.let { errorMessage(it) }
    LaunchedEffect(errorText, state.openChatId, state.callStarted) {
        state.openChatId?.let { onOpenChat(it) }
        if (state.callStarted) onCallStarted()
        if (errorText != null) snackbar.showSnackbar(errorText)
        if (errorText != null || state.openChatId != null || state.callStarted) viewModel.consume()
    }

    Scaffold(topBar = { StTopBar(stringResource(R.string.friends_title), onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(stringResource(R.string.friends_search)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            )
            val results = state.results
            if (results != null) {
                if (results.isEmpty()) {
                    EmptyState(stringResource(R.string.friends_search_empty), icon = Icons.Rounded.PersonSearch)
                } else {
                    LazyColumn {
                        items(results, key = { it.id }) { u ->
                            PersonRow(u.displayName, "@" + u.username, u.avatarPath, onClick = { onOpenProfile(u.id) })
                        }
                    }
                }
                return@Column
            }
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                FriendsTab.entries.forEach { tab ->
                    Tab(selected = state.tab == tab, onClick = { viewModel.setTab(tab) }, text = {
                        Text(stringResource(when (tab) {
                            FriendsTab.Friends -> R.string.friends_tab_friends
                            FriendsTab.Requests -> R.string.friends_tab_requests
                            FriendsTab.Blocked -> R.string.friends_tab_blocked
                        }))
                    })
                }
            }
            when (state.tab) {
                FriendsTab.Friends -> LoadStateContent(state.friends, viewModel::refresh) { list ->
                    if (list.isEmpty()) {
                        EmptyState(stringResource(R.string.friends_empty), message = stringResource(R.string.friends_empty_body))
                    } else {
                        LazyColumn {
                            items(list, key = { it.id }) { f ->
                                val subtitle = when {
                                    f.online == true -> stringResource(R.string.friends_online)
                                    f.lastSeenAt != null -> stringResource(R.string.friends_last_seen,
                                        DateUtils.getRelativeTimeSpanString(ServerTime.parse(f.lastSeenAt)?.toEpochMilli() ?: 0).toString())
                                    else -> "@" + f.username
                                }
                                PersonRow(f.displayName, subtitle, f.avatarPath, onClick = { onOpenProfile(f.id) }) {
                                    IconButton(onClick = { viewModel.favorite(f) }) {
                                        Icon(if (f.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, stringResource(R.string.friends_favorite))
                                    }
                                    IconButton(onClick = { viewModel.message(f.id) }) { Icon(Icons.AutoMirrored.Rounded.Chat, stringResource(R.string.friends_message)) }
                                    if (f.canCall) {
                                        IconButton(onClick = { viewModel.call(f.id, TalkMode.Voice) }) { Icon(Icons.Rounded.Call, stringResource(R.string.friends_voice_call)) }
                                        IconButton(onClick = { viewModel.call(f.id, TalkMode.Video) }) { Icon(Icons.Rounded.Videocam, stringResource(R.string.friends_video_call)) }
                                    }
                                }
                            }
                        }
                    }
                }
                FriendsTab.Requests -> LoadStateContent(state.requests, viewModel::refresh) { list ->
                    if (list.isEmpty()) {
                        EmptyState(stringResource(R.string.friends_requests_empty))
                    } else {
                        LazyColumn {
                            items(list, key = { it.id }) { r ->
                                PersonRow(r.user.displayName, "@" + r.user.username, r.user.avatarPath, onClick = { onOpenProfile(r.user.id) }) {
                                    if (r.outgoing) {
                                        StTextButton(stringResource(R.string.friends_cancel), { viewModel.cancel(r) })
                                    } else {
                                        StTextButton(stringResource(R.string.friends_decline), { viewModel.respond(r, false) })
                                        StTextButton(stringResource(R.string.friends_accept), { viewModel.respond(r, true) })
                                    }
                                }
                            }
                        }
                    }
                }
                FriendsTab.Blocked -> LoadStateContent(state.blocked, viewModel::refresh) { list ->
                    if (list.isEmpty()) {
                        EmptyState(stringResource(R.string.friends_blocked_empty))
                    } else {
                        LazyColumn {
                            items(list, key = { it.id }) { b ->
                                PersonRow(b.displayName, "@" + b.username, b.avatarPath, onClick = {}) {
                                    StTextButton(stringResource(R.string.friends_unblock), { viewModel.unblock(b) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PersonRow(name: String, subtitle: String, avatarPath: String?, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatarPath, name, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
        }
        trailing()
    }
}
