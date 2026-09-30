package com.sitandtalk.feature.chat

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sitandtalk.core.data.ChatItem
import com.sitandtalk.core.data.DeliveryState
import com.sitandtalk.core.designsystem.Avatar
import com.sitandtalk.core.designsystem.Buckets
import com.sitandtalk.core.designsystem.ConfirmDialog
import com.sitandtalk.core.designsystem.EmptyState
import com.sitandtalk.core.designsystem.InfoBanner
import com.sitandtalk.core.designsystem.LoadStateContent
import com.sitandtalk.core.designsystem.LoadingState
import com.sitandtalk.core.designsystem.LocalMediaResolver
import com.sitandtalk.core.designsystem.NavRow
import com.sitandtalk.core.designsystem.RemoteImage
import com.sitandtalk.core.designsystem.ReportDialog
import com.sitandtalk.core.designsystem.StPrimaryButton
import com.sitandtalk.core.designsystem.StTextButton
import com.sitandtalk.core.designsystem.StTextField
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.StTopBar
import com.sitandtalk.core.designsystem.errorMessage
import com.sitandtalk.core.designsystem.formatDuration
import com.sitandtalk.core.model.ConversationItem
import com.sitandtalk.core.model.MessageKind
import com.sitandtalk.core.model.ServerTime
import kotlinx.coroutines.launch

private fun relative(iso: String?): String {
    val millis = ServerTime.parse(iso)?.toEpochMilli() ?: return ""
    return DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onOpenChat: (String) -> Unit,
    onOpenFriends: () -> Unit,
    onNewGroup: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ConversationItem?>(null) }
    Scaffold(
        topBar = {
            StTopBar(stringResource(if (state.archived) R.string.chat_archived else R.string.chat_title), onBack = if (state.archived) ({ viewModel.setArchived(false) }) else null) {
                IconButton(onClick = onOpenFriends) { Icon(Icons.Rounded.People, stringResource(R.string.chat_friends)) }
                IconButton(onClick = onNewGroup) { Icon(Icons.Rounded.GroupAdd, stringResource(R.string.chat_new_group)) }
                if (!state.archived) IconButton(onClick = { viewModel.setArchived(true) }) { Icon(Icons.Rounded.Archive, stringResource(R.string.chat_archived)) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.offline) InfoBanner(stringResource(R.string.chat_offline_cache), Modifier.padding(12.dp))
            LoadStateContent(state.items, { viewModel.refresh() }) { list ->
                if (list.isEmpty()) {
                    EmptyState(
                        title = stringResource(if (state.archived) R.string.chat_archived_empty else R.string.chat_empty),
                        message = if (state.archived) null else stringResource(R.string.chat_empty_body),
                        icon = Icons.Rounded.Forum,
                        actionLabel = if (state.archived) null else stringResource(R.string.chat_friends),
                        onAction = if (state.archived) null else onOpenFriends,
                    )
                } else {
                    LazyColumn {
                        items(list, key = { it.id }) { c ->
                            ConversationRow(c, onClick = { onOpenChat(c.id) }, onLongClick = { menuFor = c })
                        }
                    }
                }
            }
        }
    }
    menuFor?.let { c ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                NavRow(stringResource(if (c.pinned) R.string.chat_unpin else R.string.chat_pin), { viewModel.setPinned(c); menuFor = null }, icon = Icons.Rounded.PushPin)
                NavRow(stringResource(if (c.archived) R.string.chat_unarchive else R.string.chat_archive), { viewModel.setArchivedFor(c); menuFor = null }, icon = Icons.Rounded.Archive)
                NavRow(stringResource(if (c.muted) R.string.chat_unmute else R.string.chat_mute), { viewModel.toggleMute(c); menuFor = null }, icon = Icons.Rounded.NotificationsOff)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(c: ConversationItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val name = if (c.isGroup) c.title.orEmpty() else c.peer?.displayName.orEmpty()
    val last = c.lastMessage
    val preview = when {
        last == null -> ""
        last.deleted -> stringResource(R.string.chat_deleted)
        last.kind == MessageKind.Image -> stringResource(R.string.chat_image)
        last.kind == MessageKind.Voice -> stringResource(R.string.chat_voice)
        last.kind == MessageKind.System -> stringResource(R.string.chat_system_group_created)
        else -> last.body.orEmpty()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(if (c.isGroup) null else c.peer?.avatarPath, name, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.pinned) Icon(Icons.Rounded.PushPin, contentDescription = stringResource(R.string.chat_pin), modifier = Modifier.size(16.dp))
                if (c.muted) Icon(Icons.Rounded.NotificationsOff, contentDescription = stringResource(R.string.chat_muted), modifier = Modifier.size(16.dp))
                Text(relative(last?.createdAt ?: c.sortAt), style = MaterialTheme.typography.labelSmall, color = StTheme.extra.textSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preview, style = MaterialTheme.typography.bodyMedium, color = StTheme.extra.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.unreadCount > 0) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                        Text(c.unreadCount.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(onBack: () -> Unit, onOpenProfile: (String) -> Unit, viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val typing by viewModel.peerTyping.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf("") }
    var actionFor by remember { mutableStateOf<ChatItem.Remote?>(null) }
    var reportFor by remember { mutableStateOf<ChatItem.Remote?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val errorText = state.error?.let { errorMessage(it) }
    val reportedText = stringResource(com.sitandtalk.core.designsystem.R.string.ds_report_sent)
    LaunchedEffect(errorText, state.info) {
        val msg = errorText ?: if (state.info == "reported") reportedText else null
        if (msg != null) {
            snackbar.showSnackbar(msg)
            viewModel.consume()
        }
    }
    LaunchedEffect(items.size) { if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? -> uri?.let(viewModel::sendImage) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) viewModel.startRecording() }

    val conv = state.conversation
    val title = if (conv?.isGroup == true) conv.title.orEmpty() else conv?.peer?.displayName.orEmpty()
    val names = state.details?.profiles?.mapValues { it.value.displayName }.orEmpty()
    val query = state.searchQuery
    val visibleItems = if (query.isNullOrBlank()) items else items.filter { it is ChatItem.Remote && it.message.body?.contains(query, ignoreCase = true) == true }

    Scaffold(
        topBar = {
            StTopBar(title, onBack = onBack) {
                IconButton(onClick = { viewModel.setSearch(if (query == null) "" else null) }) { Icon(Icons.Rounded.Search, stringResource(R.string.chat_search)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        conv?.peer?.let { peer ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_profile)) }, onClick = { menu = false; onOpenProfile(peer.id) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_block)) }, onClick = { menu = false; viewModel.blockPeer(); onBack() })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_clear)) }, onClick = { menu = false; confirmClear = true })
                        if (conv?.isGroup == true) DropdownMenuItem(text = { Text(stringResource(R.string.chat_leave_group)) }, onClick = { menu = false; viewModel.leaveGroup(); onBack() })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (query != null) {
                OutlinedTextField(query, viewModel::setSearch, Modifier.fillMaxWidth().padding(8.dp), placeholder = { Text(stringResource(R.string.chat_search)) }, singleLine = true)
            }
            if (state.loading && items.isEmpty()) {
                Box(Modifier.weight(1f)) { LoadingState() }
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState) {
                    if (state.canLoadOlder && query == null) item { StTextButton(stringResource(R.string.chat_load_older), viewModel::loadOlder) }
                    if (query != null && visibleItems.isEmpty()) item { Text(stringResource(R.string.chat_no_results), Modifier.padding(16.dp)) }
                    items(visibleItems, key = { it.id }) { item ->
                        when (item) {
                            is ChatItem.Remote -> RemoteBubble(item, names, isGroup = conv?.isGroup == true, onLongPress = { actionFor = item })
                            is ChatItem.Pending -> PendingBubble(item, onRetry = { viewModel.retry(item.id) }, onDiscard = { viewModel.discard(item.id) })
                        }
                    }
                    if (typing) item { Text(stringResource(R.string.chat_typing), style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary, modifier = Modifier.padding(8.dp)) }
                }
            }
            state.replyTo?.let { r ->
                ContextBar(stringResource(R.string.chat_replying, r.message.body ?: stringResource(R.string.chat_image))) { viewModel.setReply(null) }
            }
            state.editing?.let { e ->
                ContextBar(stringResource(R.string.chat_edit) + ": " + e.message.body.orEmpty()) {
                    viewModel.setEditing(null)
                    draft = ""
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.recording) {
                    Text(stringResource(R.string.chat_recording), Modifier.weight(1f).padding(8.dp), color = MaterialTheme.colorScheme.error)
                    IconButton(onClick = { viewModel.stopRecording(false) }) { Icon(Icons.Rounded.Close, stringResource(R.string.chat_cancel_record)) }
                    IconButton(onClick = { viewModel.stopRecording(true) }) { Icon(Icons.Rounded.Stop, stringResource(R.string.chat_stop_record)) }
                } else {
                    IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Rounded.Image, stringResource(R.string.chat_attach))
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it.take(4000)
                            viewModel.typing()
                        },
                        placeholder = { Text(stringResource(R.string.chat_hint)) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        maxLines = 5,
                    )
                    if (draft.isBlank() && state.editing == null) {
                        IconButton(onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                viewModel.startRecording()
                            } else {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }) { Icon(Icons.Rounded.Mic, stringResource(R.string.chat_record)) }
                    } else {
                        IconButton(onClick = {
                            viewModel.send(draft)
                            draft = ""
                        }) { Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.chat_send), tint = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }

    actionFor?.let { item ->
        ModalBottomSheet(onDismissRequest = { actionFor = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("👍", "❤️", "😂", "😮", "😢").forEach { e ->
                        Text(e, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clickable { viewModel.react(item, e); actionFor = null }.padding(8.dp))
                    }
                }
                if (item.reactions.any { it.userId == viewModel.myId }) {
                    NavRow(stringResource(R.string.chat_react) + " ×", { viewModel.react(item, null); actionFor = null })
                }
                if (!item.message.isDeleted) {
                    NavRow(stringResource(R.string.chat_reply), { viewModel.setReply(item); actionFor = null })
                    if (item.message.kind == MessageKind.Text) {
                        NavRow(stringResource(R.string.chat_copy), {
                            scope.launch { clipboard.setClipEntry(ClipData.newPlainText("", item.message.body.orEmpty()).toClipEntry()) }
                            actionFor = null
                        })
                    }
                    if (item.mine && item.message.kind == MessageKind.Text) {
                        NavRow(stringResource(R.string.chat_edit), {
                            viewModel.setEditing(item)
                            draft = item.message.body.orEmpty()
                            actionFor = null
                        })
                    }
                    if (item.mine) NavRow(stringResource(R.string.chat_delete_all), { viewModel.deleteForAll(item); actionFor = null }, tint = MaterialTheme.colorScheme.error)
                }
                NavRow(stringResource(R.string.chat_delete_me), { viewModel.deleteForMe(item); actionFor = null })
                if (!item.mine) NavRow(stringResource(R.string.chat_report), { reportFor = item; actionFor = null }, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    reportFor?.let { item ->
        ReportDialog(onSubmit = { reason, details -> viewModel.report(item, reason, details); reportFor = null }, onDismiss = { reportFor = null })
    }
    if (confirmClear) {
        ConfirmDialog(stringResource(R.string.chat_clear), stringResource(R.string.chat_clear_confirm), stringResource(R.string.chat_clear),
            onConfirm = { viewModel.clear(); confirmClear = false }, onDismiss = { confirmClear = false }, destructive = true)
    }
}

@Composable
private fun ContextBar(text: String, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(com.sitandtalk.core.designsystem.R.string.ds_cancel)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteBubble(item: ChatItem.Remote, names: Map<String, String>, isGroup: Boolean, onLongPress: () -> Unit) {
    val m = item.message
    if (m.kind == MessageKind.System) {
        Text(stringResource(R.string.chat_system_group_created), style = MaterialTheme.typography.labelSmall, color = StTheme.extra.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(8.dp))
        return
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalAlignment = if (item.mine) Alignment.End else Alignment.Start) {
        if (isGroup && !item.mine) Text(names[m.senderId] ?: "", style = MaterialTheme.typography.labelSmall, color = StTheme.extra.textSecondary)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (item.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (item.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                when {
                    m.isDeleted -> Text(stringResource(R.string.chat_deleted), style = MaterialTheme.typography.bodyMedium)
                    m.kind == MessageKind.Image && m.mediaPath != null ->
                        RemoteImage(Buckets.CHAT_MEDIA, m.mediaPath!!, stringResource(R.string.chat_image), Modifier.size(220.dp))
                    m.kind == MessageKind.Voice && m.mediaPath != null -> VoicePlayer(m.mediaPath!!, m.mediaDurationMs ?: 0)
                    else -> Text(m.body.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (m.editedAt != null && !m.isDeleted) Text(stringResource(R.string.chat_edited) + " · ", style = MaterialTheme.typography.labelSmall)
                    Text(relative(m.createdAt), style = MaterialTheme.typography.labelSmall)
                    item.delivery?.let { d ->
                        Text(" · " + stringResource(when (d) {
                            DeliveryState.Read -> R.string.chat_read
                            DeliveryState.Delivered -> R.string.chat_delivered
                            DeliveryState.Sending -> R.string.chat_sending
                            DeliveryState.Failed -> R.string.chat_failed
                            DeliveryState.Sent -> R.string.chat_sent
                        }), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (item.reactions.isNotEmpty()) {
            Text(item.reactions.groupBy { it.emoji }.entries.joinToString("  ") { "${it.key} ${it.value.size}" }, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PendingBubble(item: ChatItem.Pending, onRetry: () -> Unit, onDiscard: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalAlignment = Alignment.End) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    when (item.outbox.kind) {
                        "image" -> stringResource(R.string.chat_image)
                        "voice" -> stringResource(R.string.chat_voice)
                        else -> item.outbox.body.orEmpty()
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.failed) Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    Text(stringResource(if (item.failed) R.string.chat_failed else R.string.chat_sending), style = MaterialTheme.typography.labelSmall,
                        color = if (item.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        if (item.failed) {
            Row {
                StTextButton(stringResource(R.string.chat_discard), onDiscard)
                StTextButton(stringResource(R.string.chat_retry), onRetry)
            }
        }
    }
}

@Composable
private fun VoicePlayer(path: String, durationMs: Int) {
    val resolver = LocalMediaResolver.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(path) { onDispose { player?.release() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            val p = player
            if (p != null && playing) {
                p.pause()
                playing = false
            } else if (p != null) {
                p.start()
                playing = true
            } else {
                scope.launch {
                    val url = resolver.signedUrl(Buckets.CHAT_MEDIA, path) ?: return@launch
                    val mp = MediaPlayer()
                    runCatching {
                        mp.setDataSource(url)
                        mp.setOnPreparedListener { it.start(); playing = true }
                        mp.setOnCompletionListener { playing = false }
                        mp.prepareAsync()
                        player = mp
                    }.onFailure { mp.release() }
                }
            }
        }) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (playing) R.string.chat_pause else R.string.chat_play))
        }
        Text(stringResource(R.string.chat_voice) + " · " + formatDuration(durationMs / 1000L), style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun NewGroupScreen(onBack: () -> Unit, onCreated: (String) -> Unit, viewModel: NewGroupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.let { errorMessage(it) }
    LaunchedEffect(errorText) { if (errorText != null) snackbar.showSnackbar(errorText) }
    LaunchedEffect(state.createdId) { state.createdId?.let(onCreated) }
    Scaffold(topBar = { StTopBar(stringResource(R.string.chat_new_group), onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            StTextField(state.title, viewModel::setTitle, stringResource(R.string.chat_group_title), maxLength = 60)
            Text(stringResource(R.string.chat_group_members), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 8.dp))
            Box(Modifier.weight(1f)) {
                LoadStateContent(state.friends, {}) { friends ->
                    if (friends.isEmpty()) {
                        EmptyState(stringResource(R.string.chat_group_need_friends))
                    } else {
                        LazyColumn {
                            items(friends, key = { it.id }) { f ->
                                Row(Modifier.fillMaxWidth().clickable { viewModel.toggle(f.id) }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = f.id in state.selected, onCheckedChange = { viewModel.toggle(f.id) })
                                    Avatar(f.avatarPath, f.displayName, size = 36.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(f.displayName)
                                }
                            }
                        }
                    }
                }
            }
            StPrimaryButton(stringResource(R.string.chat_group_create), viewModel::create, loading = state.creating,
                enabled = state.title.isNotBlank() && state.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
    }
}
