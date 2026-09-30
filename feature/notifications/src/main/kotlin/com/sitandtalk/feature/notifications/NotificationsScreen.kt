package com.sitandtalk.feature.notifications

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallMissed
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sitandtalk.core.data.NotificationsRepository
import com.sitandtalk.core.designsystem.EmptyState
import com.sitandtalk.core.designsystem.LoadStateContent
import com.sitandtalk.core.designsystem.StTextButton
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.StTopBar
import com.sitandtalk.core.model.LoadState
import com.sitandtalk.core.model.NotificationItem
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.network.toAppException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(private val repo: NotificationsRepository) : ViewModel() {
    private val _items = MutableStateFlow<LoadState<List<NotificationItem>>>(LoadState.Loading)
    val items: StateFlow<LoadState<List<NotificationItem>>> = _items.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { runCatching { repo.changes().collect { refresh() } } }
    }

    fun refresh() {
        viewModelScope.launch {
            _items.value = try {
                LoadState.Success(repo.list())
            } catch (e: Exception) {
                LoadState.Failure(e.toAppException())
            }
        }
    }

    fun open(item: NotificationItem) {
        viewModelScope.launch { runCatching { repo.markRead(listOf(item.id)) } }
    }

    fun markAll() {
        viewModelScope.launch {
            runCatching { repo.markRead(null) }
            refresh()
        }
    }
}

private fun iconFor(kind: String): ImageVector = when (kind) {
    "friend_request", "friend_accepted" -> Icons.Rounded.PersonAdd
    "incoming_call" -> Icons.Rounded.Call
    "missed_call" -> Icons.Rounded.CallMissed
    "room_invite" -> Icons.Rounded.GraphicEq
    "event_reminder" -> Icons.Rounded.Event
    "comment", "reply" -> Icons.Rounded.ChatBubble
    "moderation" -> Icons.Rounded.Shield
    "purchase" -> Icons.Rounded.ShoppingBag
    "mutual_match" -> Icons.Rounded.Favorite
    "announcement" -> Icons.Rounded.Campaign
    else -> Icons.Rounded.Notifications
}

@Composable
fun notificationText(kind: String): String = stringResource(
    when (kind) {
        "friend_request" -> R.string.notif_friend_request
        "friend_accepted" -> R.string.notif_friend_accepted
        "incoming_call" -> R.string.notif_incoming_call
        "missed_call" -> R.string.notif_missed_call
        "room_invite" -> R.string.notif_room_invite
        "event_reminder" -> R.string.notif_event_reminder
        "comment" -> R.string.notif_comment
        "reply" -> R.string.notif_reply
        "moderation" -> R.string.notif_moderation
        "purchase" -> R.string.notif_purchase
        "mutual_match" -> R.string.notif_mutual_match
        else -> R.string.notif_announcement
    },
)

@Composable
fun NotificationsScreen(onBack: () -> Unit, onOpen: (NotificationItem) -> Unit, viewModel: NotificationsViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        StTopBar(stringResource(R.string.notif_title), onBack = onBack) {
            StTextButton(stringResource(R.string.notif_mark_all), viewModel::markAll)
        }
    }) { padding ->
        LoadStateContent(items, viewModel::refresh, Modifier.padding(padding)) { list ->
            if (list.isEmpty()) {
                EmptyState(stringResource(R.string.notif_empty), Modifier.padding(padding), icon = Icons.Rounded.Notifications)
            } else {
                LazyColumn(Modifier.padding(padding)) {
                    items(list, key = { it.id }) { n ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.open(n)
                                    onOpen(n)
                                }
                                .background(if (n.readAt == null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.background)
                                .heightIn(min = 64.dp)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(iconFor(n.kind), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(notificationText(n.kind), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    DateUtils.getRelativeTimeSpanString(ServerTime.parse(n.createdAt)?.toEpochMilli() ?: 0).toString(),
                                    style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
