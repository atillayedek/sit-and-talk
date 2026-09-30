package com.sitandtalk.feature.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MailLock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sitandtalk.core.designsystem.Avatar
import com.sitandtalk.core.designsystem.EmptyState
import com.sitandtalk.core.designsystem.LoadStateContent
import com.sitandtalk.core.designsystem.StPrimaryButton
import com.sitandtalk.core.designsystem.StSecondaryButton
import com.sitandtalk.core.designsystem.StTextButton
import com.sitandtalk.core.designsystem.StTextField
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.StTopBar
import com.sitandtalk.core.designsystem.SupportedLanguages
import com.sitandtalk.core.designsystem.ToggleChip
import com.sitandtalk.core.designsystem.errorMessage
import com.sitandtalk.core.designsystem.interestLabel
import com.sitandtalk.core.designsystem.languageLabel
import com.sitandtalk.core.model.Interest
import com.sitandtalk.core.model.RoomEvent
import com.sitandtalk.core.model.RoomSummary
import com.sitandtalk.core.model.RoomVisibility
import com.sitandtalk.core.model.ServerTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun interestName(interests: List<Interest>, slug: String?): String? = interestLabel(interests, slug)

fun formatDateTime(iso: String?): String =
    ServerTime.parse(iso)?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)).orEmpty()

/** A room link opened from outside the list (invite link or notification). */
data class RoomJoinRequest(val roomId: String, val inviteCode: String?)

@Composable
fun RoomsScreen(
    onOpenRoom: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onPlanEvent: () -> Unit,
    onStartEventRoom: (RoomEvent) -> Unit,
    joinRequest: RoomJoinRequest? = null,
    onJoinRequestHandled: () -> Unit = {},
    viewModel: RoomsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(joinRequest) {
        joinRequest?.let {
            viewModel.joinById(it.roomId, inviteCode = it.inviteCode)
            onJoinRequestHandled()
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.let { errorMessage(it) }
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(state.enteredRoomId) {
        state.enteredRoomId?.let {
            viewModel.consumeNavigation()
            onOpenRoom(it)
        }
    }

    Scaffold(
        topBar = { StTopBar(stringResource(R.string.rooms_title)) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = if (state.tab == RoomsTab.Events) onPlanEvent else onCreateRoom,
                icon = { Icon(if (state.tab == RoomsTab.Events) Icons.Rounded.Event else Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(if (state.tab == RoomsTab.Events) R.string.events_create else R.string.rooms_create)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                RoomsTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.setTab(tab) },
                        text = {
                            Text(stringResource(when (tab) {
                                RoomsTab.Active -> R.string.rooms_tab_active
                                RoomsTab.Favorites -> R.string.rooms_tab_favorites
                                RoomsTab.Events -> R.string.rooms_tab_events
                            }))
                        },
                    )
                }
            }
            if (state.tab == RoomsTab.Events) {
                LoadStateContent(state.events, viewModel::refresh) { events ->
                    if (events.isEmpty()) {
                        EmptyState(stringResource(R.string.events_empty), icon = Icons.Rounded.Event,
                            actionLabel = stringResource(R.string.events_create), onAction = onPlanEvent)
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(events, key = { it.id }) { e ->
                                EventCard(e, state.interests, viewModel, onOpenRoom = { id -> viewModel.joinById(id) }, onStart = { onStartEventRoom(e) })
                            }
                        }
                    }
                }
            } else {
                Filters(state, viewModel)
                LoadStateContent(state.rooms, viewModel::refresh) { rooms ->
                    if (rooms.isEmpty()) {
                        EmptyState(
                            title = stringResource(if (state.tab == RoomsTab.Favorites) R.string.rooms_empty_favorites else R.string.rooms_empty),
                            message = if (state.tab == RoomsTab.Active) stringResource(R.string.rooms_empty_body) else null,
                            icon = Icons.Rounded.GraphicEq,
                            actionLabel = if (state.tab == RoomsTab.Active) stringResource(R.string.rooms_create) else null,
                            onAction = if (state.tab == RoomsTab.Active) onCreateRoom else null,
                        )
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(rooms, key = { it.id }) { room ->
                                RoomCard(
                                    room = room,
                                    topic = interestName(state.interests, room.topic),
                                    joining = state.joining == room.id,
                                    isActive = state.activeRoomId == room.id,
                                    onClick = {
                                        if (state.activeRoomId == room.id) onOpenRoom(room.id) else viewModel.join(room)
                                    },
                                )
                            }
                            if (state.canLoadMore) item { StTextButton(stringResource(R.string.rooms_load_more), viewModel::loadMore) }
                        }
                    }
                }
            }
        }
    }

    state.passwordPrompt?.let { room ->
        var password by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::dismissPassword,
            title = { Text(stringResource(R.string.rooms_join_password_title)) },
            text = { StTextField(password, { password = it }, stringResource(R.string.rooms_enter_password), isPassword = true, maxLength = 64) },
            confirmButton = { TextButton(onClick = { viewModel.join(room, password) }, enabled = password.length >= 4) { Text(stringResource(R.string.rooms_join)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissPassword) { Text(stringResource(com.sitandtalk.core.designsystem.R.string.ds_cancel)) } },
        )
    }
}

@Composable
private fun Filters(state: RoomsUiState, vm: RoomsViewModel) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.rooms_search)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ToggleChip(stringResource(R.string.rooms_all_languages), state.language == null, { vm.setLanguage(null) }) }
            items(SupportedLanguages.take(6)) { code -> ToggleChip(languageLabel(code), state.language == code, { vm.setLanguage(if (state.language == code) null else code) }) }
        }
        if (state.interests.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { ToggleChip(stringResource(R.string.rooms_all_topics), state.topic == null, { vm.setTopic(null) }) }
                items(state.interests, key = { it.slug }) { i ->
                    ToggleChip(interestName(state.interests, i.slug).orEmpty(), state.topic == i.slug, { vm.setTopic(if (state.topic == i.slug) null else i.slug) })
                }
            }
        }
    }
}

@Composable
private fun RoomCard(room: RoomSummary, topic: String?, joining: Boolean, isActive: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = !joining,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(room.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                when (room.visibility) {
                    RoomVisibility.Password -> Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.rooms_password), tint = StTheme.extra.textSecondary)
                    RoomVisibility.Invite -> Icon(Icons.Rounded.MailLock, contentDescription = stringResource(R.string.rooms_private), tint = StTheme.extra.textSecondary)
                    RoomVisibility.Public -> Unit
                }
                if (room.isFavorite) Icon(Icons.Rounded.Star, contentDescription = null, tint = StTheme.extra.coral, modifier = Modifier.padding(start = 4.dp))
            }
            if (room.description.isNotBlank()) {
                Text(room.description, style = MaterialTheme.typography.bodyMedium, color = StTheme.extra.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                room.owner?.let { Avatar(it.avatarPath, it.displayName, size = 28.dp) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        listOfNotNull(topic, languageLabel(room.languageCode)).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        stringResource(R.string.rooms_participants, room.participantCount, room.maxParticipants) + " · " +
                            stringResource(R.string.rooms_speakers, room.speakerCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = StTheme.extra.textSecondary,
                    )
                }
                when {
                    isActive -> Text(stringResource(R.string.rooms_joined), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    room.isFull -> Text(stringResource(R.string.rooms_full), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    else -> Icon(Icons.Rounded.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: RoomEvent, interests: List<Interest>, vm: RoomsViewModel, onOpenRoom: (String) -> Unit, onStart: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (event.status == "live") Text(stringResource(R.string.events_live), color = StTheme.extra.coral, style = MaterialTheme.typography.labelLarge)
            }
            Text(formatDateTime(event.startsAt), style = MaterialTheme.typography.bodyMedium)
            if (event.description.isNotBlank()) Text(event.description, style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
            Text(
                listOfNotNull(interestName(interests, event.topic), languageLabel(event.languageCode), event.host?.let { stringResource(R.string.events_by, it.displayName) })
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(stringResource(R.string.events_subscribers, event.subscriberCount), style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    event.status == "live" && event.roomId != null -> StPrimaryButton(stringResource(R.string.events_join_live), { onOpenRoom(event.roomId!!) })
                    event.isMine -> {
                        StPrimaryButton(stringResource(R.string.events_start_room), onStart)
                        StTextButton(stringResource(R.string.events_cancel), { vm.cancelEvent(event) })
                    }
                    else -> StSecondaryButton(
                        stringResource(if (event.subscribed) R.string.events_reminding else R.string.events_remind),
                        { vm.setEventSubscription(event, !event.subscribed) },
                    )
                }
            }
        }
    }
}
