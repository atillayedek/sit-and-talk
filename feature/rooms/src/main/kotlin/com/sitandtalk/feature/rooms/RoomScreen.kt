package com.sitandtalk.feature.rooms

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BackHand
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sitandtalk.core.data.ActiveRoom
import com.sitandtalk.core.designsystem.Avatar
import com.sitandtalk.core.designsystem.ConfirmDialog
import com.sitandtalk.core.designsystem.EmptyState
import com.sitandtalk.core.designsystem.InfoBanner
import com.sitandtalk.core.designsystem.LoadingState
import com.sitandtalk.core.designsystem.NavRow
import com.sitandtalk.core.designsystem.ReportDialog
import com.sitandtalk.core.designsystem.StPrimaryButton
import com.sitandtalk.core.designsystem.StSecondaryButton
import com.sitandtalk.core.designsystem.StTextButton
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.StTopBar
import com.sitandtalk.core.designsystem.errorMessage
import com.sitandtalk.core.model.ReportTarget
import com.sitandtalk.core.model.RoomMember
import com.sitandtalk.core.model.RoomRole
import com.sitandtalk.core.rtc.RtcState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(onBack: () -> Unit, onOpenProfile: (String) -> Unit, viewModel: RoomViewModel = hiltViewModel()) {
    val active by viewModel.active.collectAsStateWithLifecycle()
    val rtc by viewModel.rtc.collectAsStateWithLifecycle()
    val extras by viewModel.extras.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val current = active
    if (current == null) {
        LoadingState()
        LaunchedEffect(Unit) { onBack() }
        return
    }
    if (current.removed) {
        Scaffold(topBar = { StTopBar(current.room.title) }) { padding ->
            EmptyState(
                title = stringResource(if (current.room.isOpen) R.string.room_removed else R.string.room_closed),
                actionLabel = stringResource(R.string.room_back),
                onAction = {
                    viewModel.dismissRemoved()
                    onBack()
                },
                modifier = Modifier.padding(padding),
            )
        }
        return
    }

    val room = current.room
    val me = room.members.firstOrNull { it.userId == extras.myUserId }
    var memberSheet by remember { mutableStateOf<RoomMember?>(null) }
    var showChat by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var reportTarget by remember { mutableStateOf<Pair<ReportTarget, String>?>(null) }

    val errorText = (extras.error ?: current.lastError?.takeIf { it.code != "mic_permission" })?.let { errorMessage(it) }
    val infoText = when {
        extras.message == "reported" -> stringResource(com.sitandtalk.core.designsystem.R.string.ds_report_sent)
        current.info == "agora_rest_not_configured" -> stringResource(R.string.room_kick_soft)
        else -> null
    }
    LaunchedEffect(errorText, infoText) {
        val msg = errorText ?: infoText
        if (msg != null) {
            snackbar.showSnackbar(msg)
            viewModel.consume()
            viewModel.consumeControllerMessage()
        }
    }
    val resources = LocalResources.current
    LaunchedEffect(extras.inviteLink) {
        extras.inviteLink?.let { link ->
            val text = resources.getString(R.string.room_share_text, room.title, link)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            viewModel.consume()
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.toggleMic()
    }

    Scaffold(
        topBar = {
            StTopBar(room.title, onBack = onBack) {
                IconButton(onClick = { viewModel.setFavorite(!room.isFavorite) }) {
                    Icon(if (room.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        stringResource(if (room.isFavorite) R.string.room_unfavorite else R.string.room_favorite))
                }
                IconButton(onClick = viewModel::createInviteLink) { Icon(Icons.Rounded.Share, stringResource(R.string.room_share)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.room_report)) }, onClick = {
                            menu = false
                            reportTarget = ReportTarget.Room to room.id
                        })
                        if (me?.role == RoomRole.Owner) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.room_close)) }, onClick = {
                                menu = false
                                confirmClose = true
                            })
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            MyControls(current, rtc, me, onToggleMic = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED || !rtc.micMuted) {
                    viewModel.toggleMic()
                } else {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }, vm = viewModel, onChat = { showChat = true }, onLeave = {
                viewModel.leave()
                onBack()
            })
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(88.dp),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (room.description.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(room.description, style = MaterialTheme.typography.bodyMedium, color = StTheme.extra.textSecondary)
                }
            }
            if (current.rtcFailed) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        InfoBanner(stringResource(R.string.room_rtc_failed), isWarning = true)
                        StTextButton(stringResource(R.string.room_retry), viewModel::retry)
                    }
                }
            }
            if (room.myInvite != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.room_invited_to_speak), Modifier.weight(1f))
                            StTextButton(stringResource(R.string.room_decline), { viewModel.respondInvite(false) })
                            StTextButton(stringResource(R.string.room_accept), { viewModel.respondInvite(true) })
                        }
                    }
                }
            }
            if (me?.role?.canModerate == true && room.pendingRequests.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(stringResource(R.string.room_requests), style = MaterialTheme.typography.titleSmall)
                        room.pendingRequests.filter { it.kind == "hand" }.forEach { req ->
                            val member = room.members.firstOrNull { it.userId == req.userId } ?: return@forEach
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                Avatar(member.avatarPath, member.displayName, size = 32.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(member.displayName, Modifier.weight(1f))
                                StTextButton(stringResource(R.string.room_decline), { viewModel.declineHand(member.userId) })
                                StTextButton(stringResource(R.string.room_approve), { viewModel.inviteToSpeak(member.userId) })
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.room_speakers), style = MaterialTheme.typography.titleMedium) }
            items(room.speakers, key = { "s" + it.userId }) { m -> MemberTile(m, rtc, extras.myUserId, onClick = { memberSheet = m }) }
            item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.room_listeners), style = MaterialTheme.typography.titleMedium) }
            items(room.listeners, key = { "l" + it.userId }) { m -> MemberTile(m, rtc, extras.myUserId, onClick = { memberSheet = m }) }
        }
    }

    memberSheet?.let { member ->
        ModalBottomSheet(onDismissRequest = { memberSheet = null }) {
            MemberActions(member, me, onAction = { memberSheet = null }, vm = viewModel, onProfile = { onOpenProfile(member.userId) },
                onReport = { reportTarget = ReportTarget.User to member.userId })
        }
    }
    if (showChat) {
        ModalBottomSheet(onDismissRequest = { showChat = false }) { RoomChat(current, viewModel, onReport = { id -> reportTarget = ReportTarget.RoomMessage to id }) }
    }
    if (confirmClose) {
        ConfirmDialog(
            title = stringResource(R.string.room_close),
            message = stringResource(R.string.room_close_confirm),
            confirmLabel = stringResource(R.string.room_close),
            onConfirm = {
                confirmClose = false
                viewModel.close()
            },
            onDismiss = { confirmClose = false },
            destructive = true,
        )
    }
    reportTarget?.let { (type, id) ->
        ReportDialog(
            onSubmit = { reason, details ->
                viewModel.report(type, id, reason, details)
                reportTarget = null
            },
            onDismiss = { reportTarget = null },
            submitting = extras.reportSending,
        )
    }
}

@Composable
private fun MemberTile(member: RoomMember, rtc: RtcState, myId: String?, onClick: () -> Unit) {
    val uid = if (member.userId == myId) 0 else member.rtcUid
    val speaking = uid in rtc.speaking && !member.selfMuted
    val speakingLabel = stringResource(R.string.room_speaking)
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
            .semantics { if (speaking) contentDescription = "${member.displayName}, $speakingLabel" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Avatar(
                member.avatarPath, member.displayName, size = 64.dp,
                modifier = if (speaking) Modifier.border(3.dp, StTheme.extra.success, CircleShape) else Modifier,
            )
            if (member.role.canSpeak) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(22.dp)) {
                    Icon(
                        if (member.selfMuted || member.mutedByModerator) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        contentDescription = stringResource(if (member.selfMuted) R.string.room_mic_off else R.string.room_mic_on),
                        modifier = Modifier.padding(3.dp),
                    )
                }
            } else if (member.handRaisedAt != null) {
                Icon(Icons.Rounded.BackHand, contentDescription = stringResource(R.string.room_raise_hand), tint = StTheme.extra.coral)
            }
        }
        Text(member.displayName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (member.role == RoomRole.Owner || member.role == RoomRole.Moderator) {
            Text(
                stringResource(if (member.role == RoomRole.Owner) R.string.room_role_owner else R.string.room_role_moderator),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun MyControls(
    active: ActiveRoom,
    rtc: RtcState,
    me: RoomMember?,
    onToggleMic: () -> Unit,
    vm: RoomViewModel,
    onChat: () -> Unit,
    onLeave: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            if (me?.mutedByModerator == true) Text(stringResource(R.string.room_muted_by_mod), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (me?.role == RoomRole.Listener && me.handRaisedAt != null) Text(stringResource(R.string.room_hand_raised), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StSecondaryButton(stringResource(R.string.room_leave), onLeave)
                Spacer(Modifier.weight(1f))
                if (active.room.textChatEnabled) IconButton(onClick = onChat) { Icon(Icons.Rounded.Chat, stringResource(R.string.room_chat)) }
                listOf("clap" to "👏", "heart" to "❤️").forEach { (kind, emoji) ->
                    IconButton(onClick = { vm.react(kind) }) { Text(emoji) }
                }
                when {
                    me == null -> Unit
                    me.role.canSpeak -> StPrimaryButton(
                        stringResource(if (rtc.micMuted) R.string.room_unmute else R.string.room_mute),
                        onToggleMic,
                        icon = if (rtc.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        enabled = !me.mutedByModerator || !rtc.micMuted,
                    )
                    else -> StPrimaryButton(
                        stringResource(if (me.handRaisedAt != null) R.string.room_lower_hand else if (active.room.handRaiseRequired) R.string.room_raise_hand else R.string.room_request_speak),
                        { vm.raiseHand(me.handRaisedAt == null) },
                        icon = Icons.Rounded.BackHand,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberActions(member: RoomMember, me: RoomMember?, onAction: () -> Unit, vm: RoomViewModel, onProfile: () -> Unit, onReport: () -> Unit) {
    val myRole = me?.role
    val isSelf = me?.userId == member.userId
    val canManage = myRole != null && myRole.canModerate && !isSelf && member.role != RoomRole.Owner &&
        !(myRole == RoomRole.Moderator && member.role == RoomRole.Moderator)
    Column(Modifier.padding(bottom = 24.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(member.avatarPath, member.displayName, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(member.displayName, style = MaterialTheme.typography.titleMedium)
                if (member.username.isNotBlank()) Text("@" + member.username, color = StTheme.extra.textSecondary)
            }
        }
        HorizontalDivider()
        NavRow(stringResource(R.string.room_member_profile), { onAction(); onProfile() })
        if (canManage) {
            if (member.role == RoomRole.Listener) NavRow(stringResource(R.string.room_member_invite), { onAction(); vm.inviteToSpeak(member.userId) })
            if (member.role == RoomRole.Speaker || member.role == RoomRole.Moderator) {
                NavRow(stringResource(R.string.room_member_make_listener), { onAction(); vm.setRole(member.userId, RoomRole.Listener) })
            }
            if (myRole == RoomRole.Owner && member.role != RoomRole.Moderator) {
                NavRow(stringResource(R.string.room_member_make_moderator), { onAction(); vm.setRole(member.userId, RoomRole.Moderator) })
            }
            if (myRole == RoomRole.Owner && member.role == RoomRole.Moderator) {
                NavRow(stringResource(R.string.room_member_remove_moderator), { onAction(); vm.setRole(member.userId, RoomRole.Speaker) })
            }
            if (member.role.canSpeak) {
                NavRow(
                    stringResource(if (member.mutedByModerator) R.string.room_member_unmute else R.string.room_member_mute),
                    { onAction(); vm.moderatorMute(member.userId, !member.mutedByModerator) },
                )
            }
            NavRow(stringResource(R.string.room_member_kick), { onAction(); vm.kick(member.userId, false) }, tint = MaterialTheme.colorScheme.error)
            NavRow(stringResource(R.string.room_member_ban), { onAction(); vm.kick(member.userId, true) }, tint = MaterialTheme.colorScheme.error)
        }
        if (!isSelf) NavRow(stringResource(R.string.room_member_report), { onAction(); onReport() }, tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun RoomChat(active: ActiveRoom, vm: RoomViewModel, onReport: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val names = active.room.members.associate { it.userId to it.displayName }
    Column(Modifier.fillMaxWidth().height(460.dp).imePadding().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.room_chat), style = MaterialTheme.typography.titleLarge)
        if (!active.room.textChatEnabled) {
            Text(stringResource(R.string.room_chat_disabled))
            return@Column
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (active.messages.isEmpty()) {
                Text(stringResource(R.string.room_chat_empty), color = StTheme.extra.textSecondary)
            }
            active.messages.takeLast(40).forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { onReport(m.id) }.padding(vertical = 3.dp)) {
                    Text((names[m.userId] ?: "…") + ": ", style = MaterialTheme.typography.labelLarge)
                    Text(m.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it.take(500) }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.room_chat_hint)) },
                shape = RoundedCornerShape(20.dp), maxLines = 3)
            IconButton(onClick = {
                vm.sendMessage(draft)
                draft = ""
            }, enabled = draft.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.Send, null) }
        }
    }
}
