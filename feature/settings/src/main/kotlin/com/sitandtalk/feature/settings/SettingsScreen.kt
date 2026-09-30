package com.sitandtalk.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sitandtalk.core.data.AccountRepository
import com.sitandtalk.core.data.AuthRepository
import com.sitandtalk.core.data.NotificationsRepository
import com.sitandtalk.core.data.ProfileRepository
import com.sitandtalk.core.designsystem.InfoBanner
import com.sitandtalk.core.designsystem.LoadingState
import com.sitandtalk.core.designsystem.NavRow
import com.sitandtalk.core.designsystem.SectionHeader
import com.sitandtalk.core.designsystem.StTextField
import com.sitandtalk.core.designsystem.StTopBar
import com.sitandtalk.core.designsystem.SwitchRow
import com.sitandtalk.core.designsystem.ToggleChip
import com.sitandtalk.core.designsystem.errorMessage
import com.sitandtalk.core.model.AppConfig
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.MessagePolicy
import com.sitandtalk.core.model.NotificationPreferences
import com.sitandtalk.core.model.PrivacySettings
import com.sitandtalk.core.model.VisibilityPolicy
import com.sitandtalk.core.network.toAppException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LegalLink { Terms, Privacy, Community, Support }

data class SettingsState(
    val privacy: PrivacySettings? = null,
    val prefs: NotificationPreferences? = null,
    val email: String? = null,
    val pushAvailable: Boolean = false,
    val busy: Boolean = false,
    val error: AppException? = null,
    val info: String? = null,
    val signedOut: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val notifications: NotificationsRepository,
    private val auth: AuthRepository,
    private val account: AccountRepository,
    val config: AppConfig,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState(email = auth.currentEmail(), pushAvailable = notifications.push.isAvailable))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _state.update { it.copy(privacy = profiles.privacy(), prefs = notifications.preferences()) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            }
        }
    }

    fun updatePrivacy(transform: (PrivacySettings) -> PrivacySettings) {
        val current = _state.value.privacy ?: return
        val next = transform(current)
        _state.update { it.copy(privacy = next) }
        act { profiles.updatePrivacy(next) }
    }

    fun updatePrefs(transform: (NotificationPreferences) -> NotificationPreferences) {
        val current = _state.value.prefs ?: return
        val next = transform(current)
        _state.update { it.copy(prefs = next) }
        act { notifications.savePreferences(next) }
    }

    fun changePassword(current: String, new: String) = act {
        auth.reauthenticate(current)
        auth.updatePassword(new)
        _state.update { it.copy(info = "password") }
    }

    fun export(target: Uri) = act {
        account.exportTo(target)
        _state.update { it.copy(info = "export") }
    }

    fun deleteAccount(password: String) = act {
        account.deleteAccount(password)
        _state.update { it.copy(signedOut = true) }
    }

    fun signOut() = act {
        auth.signOut()
        _state.update { it.copy(signedOut = true) }
    }

    fun consume() = _state.update { it.copy(error = null, info = null) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

@Composable
private fun <T> PolicyRow(title: String, value: T, options: List<Pair<T, Int>>, onChange: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, label) -> ToggleChip(stringResource(label), value == v, { onChange(v) }) }
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onBlocked: () -> Unit,
    onOpenLegal: (LegalLink) -> Unit,
    onSignedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    val errorText = state.error?.let { errorMessage(it) }
    val infoText = when (state.info) {
        "password" -> stringResource(R.string.settings_password_changed)
        "export" -> stringResource(R.string.settings_export_done)
        else -> null
    }
    LaunchedEffect(errorText, infoText) {
        val msg = errorText ?: infoText
        if (msg != null) { snackbar.showSnackbar(msg); viewModel.consume() }
    }
    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? -> uri?.let(viewModel::export) }

    val msgOptions = listOf(MessagePolicy.Everyone to R.string.settings_everyone, MessagePolicy.Friends to R.string.settings_friends, MessagePolicy.Nobody to R.string.settings_nobody)
    val visOptions = listOf(VisibilityPolicy.Everyone to R.string.settings_everyone, VisibilityPolicy.Friends to R.string.settings_friends, VisibilityPolicy.Nobody to R.string.settings_nobody)

    Scaffold(topBar = { StTopBar(stringResource(R.string.settings_title), onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val privacy = state.privacy
        val prefs = state.prefs
        if (privacy == null || prefs == null) {
            if (state.error == null) LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.settings_privacy))
            PolicyRow(stringResource(R.string.settings_message_policy), privacy.messagePolicy, msgOptions) { v -> viewModel.updatePrivacy { it.copy(messagePolicy = v) } }
            PolicyRow(stringResource(R.string.settings_call_policy), privacy.callPolicy, msgOptions.filter { it.first != MessagePolicy.Everyone }) { v -> viewModel.updatePrivacy { it.copy(callPolicy = v) } }
            PolicyRow(stringResource(R.string.settings_online), privacy.onlineVisibility, visOptions) { v -> viewModel.updatePrivacy { it.copy(onlineVisibility = v) } }
            PolicyRow(stringResource(R.string.settings_last_seen), privacy.lastSeenVisibility, visOptions) { v -> viewModel.updatePrivacy { it.copy(lastSeenVisibility = v) } }
            SwitchRow(stringResource(R.string.settings_read_receipts), privacy.readReceipts, { v -> viewModel.updatePrivacy { it.copy(readReceipts = v) } },
                subtitle = stringResource(R.string.settings_read_receipts_note))
            SwitchRow(stringResource(R.string.settings_media_non_friends), privacy.mediaFromNonFriends, { v -> viewModel.updatePrivacy { it.copy(mediaFromNonFriends = v) } })
            SwitchRow(stringResource(R.string.settings_share_avatar), privacy.shareAvatarInRandom, { v -> viewModel.updatePrivacy { it.copy(shareAvatarInRandom = v) } })
            SwitchRow(stringResource(R.string.settings_discoverable), privacy.profileDiscoverable, { v -> viewModel.updatePrivacy { it.copy(profileDiscoverable = v) } })
            NavRow(stringResource(R.string.settings_blocked), onBlocked, icon = Icons.Rounded.Block)
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_notifications))
            if (!state.pushAvailable) InfoBanner(stringResource(R.string.settings_push_unavailable), Modifier.padding(horizontal = 16.dp))
            SwitchRow(stringResource(R.string.settings_notif_friend_requests), prefs.friendRequests, { v -> viewModel.updatePrefs { it.copy(friendRequests = v) } })
            SwitchRow(stringResource(R.string.settings_notif_messages), prefs.messages, { v -> viewModel.updatePrefs { it.copy(messages = v) } })
            SwitchRow(stringResource(R.string.settings_notif_calls), prefs.calls, { v -> viewModel.updatePrefs { it.copy(calls = v) } })
            SwitchRow(stringResource(R.string.settings_notif_room_invites), prefs.roomInvites, { v -> viewModel.updatePrefs { it.copy(roomInvites = v) } })
            SwitchRow(stringResource(R.string.settings_notif_events), prefs.events, { v -> viewModel.updatePrefs { it.copy(events = v) } })
            SwitchRow(stringResource(R.string.settings_notif_comments), prefs.comments, { v -> viewModel.updatePrefs { it.copy(comments = v) } })
            SwitchRow(stringResource(R.string.settings_notif_purchases), prefs.purchases, { v -> viewModel.updatePrefs { it.copy(purchases = v) } })
            SwitchRow(stringResource(R.string.settings_notif_previews), prefs.showPreviews, { v -> viewModel.updatePrefs { it.copy(showPreviews = v) } })
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_account))
            state.email?.let { Text(stringResource(R.string.settings_email, it), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
            NavRow(stringResource(R.string.settings_change_password), { showPassword = true }, icon = Icons.Rounded.Password)
            NavRow(stringResource(R.string.settings_export), { exporter.launch("sitandtalk-data.json") }, icon = Icons.Rounded.Download)
            NavRow(stringResource(R.string.settings_sign_out), viewModel::signOut, icon = Icons.Rounded.Logout)
            NavRow(stringResource(R.string.settings_delete_account), { showDelete = true }, icon = Icons.Rounded.Delete, tint = MaterialTheme.colorScheme.error)
            HorizontalDivider()

            SectionHeader(stringResource(R.string.settings_legal))
            NavRow(stringResource(R.string.settings_terms), { onOpenLegal(LegalLink.Terms) })
            NavRow(stringResource(R.string.settings_privacy_policy), { onOpenLegal(LegalLink.Privacy) })
            NavRow(stringResource(R.string.settings_community), { onOpenLegal(LegalLink.Community) })
            NavRow(stringResource(R.string.settings_support), { onOpenLegal(LegalLink.Support) })
            Text(stringResource(R.string.settings_version, viewModel.config.versionName), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showPassword) {
        var current by rememberSaveable { mutableStateOf("") }
        var next by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPassword = false },
            title = { Text(stringResource(R.string.settings_change_password)) },
            text = {
                Column {
                    StTextField(current, { current = it }, stringResource(R.string.settings_current_password), isPassword = true)
                    StTextField(next, { next = it }, stringResource(R.string.settings_new_password), isPassword = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.changePassword(current, next); showPassword = false },
                    enabled = current.isNotEmpty() && next.length >= 8 && next.any { it.isDigit() } && next.any { it.isLetter() }) {
                    Text(stringResource(com.sitandtalk.core.designsystem.R.string.ds_save))
                }
            },
            dismissButton = { TextButton(onClick = { showPassword = false }) { Text(stringResource(com.sitandtalk.core.designsystem.R.string.ds_cancel)) } },
        )
    }
    if (showDelete) {
        var password by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!state.busy) showDelete = false },
            title = { Text(stringResource(R.string.settings_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_delete_body))
                    StTextField(password, { password = it }, stringResource(R.string.settings_current_password), isPassword = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAccount(password) }, enabled = password.isNotEmpty() && !state.busy) {
                    Text(stringResource(R.string.settings_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }, enabled = !state.busy) { Text(stringResource(com.sitandtalk.core.designsystem.R.string.ds_cancel)) } },
        )
    }
}
