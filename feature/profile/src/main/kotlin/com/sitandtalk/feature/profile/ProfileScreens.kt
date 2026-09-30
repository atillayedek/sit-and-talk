package com.sitandtalk.feature.profile

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.sitandtalk.core.data.BootstrapRepository
import com.sitandtalk.core.data.CallRepository
import com.sitandtalk.core.data.ChatRepository
import com.sitandtalk.core.data.FriendsRepository
import com.sitandtalk.core.data.ModerationRepository
import com.sitandtalk.core.data.ProfileRepository
import com.sitandtalk.core.designsystem.Avatar
import com.sitandtalk.core.designsystem.ConfirmDialog
import com.sitandtalk.core.designsystem.LoadStateContent
import com.sitandtalk.core.designsystem.NavRow
import com.sitandtalk.core.designsystem.ReportDialog
import com.sitandtalk.core.designsystem.SectionHeader
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
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.Interest
import com.sitandtalk.core.model.LoadState
import com.sitandtalk.core.model.ProfileCard
import com.sitandtalk.core.model.ReportTarget
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.model.TalkMode
import com.sitandtalk.core.network.toAppException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

data class ProfileState(
    val profile: LoadState<ProfileCard> = LoadState.Loading,
    val interests: List<Interest> = emptyList(),
    val isStaff: Boolean = false,
    val busy: Boolean = false,
    val error: AppException? = null,
    val info: String? = null,
    val openChatId: String? = null,
    val callStarted: Boolean = false,
    val blocked: Boolean = false,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val profiles: ProfileRepository,
    private val friends: FriendsRepository,
    private val chat: ChatRepository,
    private val calls: CallRepository,
    private val activeCall: ActiveCallController,
    private val moderation: ModerationRepository,
    private val bootstrap: BootstrapRepository,
) : ViewModel() {
    /** Null = the signed-in user's own profile. */
    private val userId: String? = savedState["userId"]
    private val _state = MutableStateFlow(ProfileState(isStaff = bootstrap.current.value?.isStaff == true))
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { runCatching { profiles.interests() }.onSuccess { list -> _state.update { it.copy(interests = list) } } }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update {
                it.copy(profile = try {
                    LoadState.Success(if (userId == null) profiles.refreshMe() else profiles.profile(userId))
                } catch (e: Exception) {
                    LoadState.Failure(e.toAppException())
                })
            }
        }
    }

    private val card get() = (_state.value.profile as? LoadState.Success)?.data

    fun addFriend() = act { card?.let { friends.sendRequest(it.id, "profile") }; refresh() }
    fun cancelRequest() = act { card?.pendingRequest?.let { friends.cancel(it.id) }; refresh() }
    fun acceptRequest() = act { card?.pendingRequest?.let { friends.respond(it.id, true) }; refresh() }
    fun removeFriend() = act { card?.let { friends.remove(it.id) }; refresh() }
    fun message() = act { card?.let { c -> _state.update { it.copy(openChatId = chat.openDirect(c.id)) } } }
    fun call(mode: TalkMode) = act {
        card?.let { c ->
            activeCall.start(calls.startDirect(c.id, mode))
            _state.update { it.copy(callStarted = true) }
        }
    }
    fun block() = act { card?.let { friends.block(it.id) }; _state.update { it.copy(blocked = true) } }
    fun report(reason: com.sitandtalk.core.model.ReportReason, details: String) = act {
        card?.let { moderation.report(ReportTarget.User, it.id, reason, details) }
        _state.update { it.copy(info = "reported") }
    }

    fun consume() = _state.update { it.copy(error = null, info = null, openChatId = null, callStarted = false) }

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

private fun formatDate(iso: String?): String =
    ServerTime.parse(iso)?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)).orEmpty()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileHeader(p: ProfileCard, interests: List<Interest>) {
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(p.avatarPath, p.displayName, size = 104.dp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.displayName, style = MaterialTheme.typography.headlineSmall)
            if (p.isPremium) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.WorkspacePremium, stringResource(R.string.profile_premium), tint = StTheme.extra.coral)
            }
        }
        Text("@" + p.username, color = StTheme.extra.textSecondary)
        when {
            p.online == true -> Text(stringResource(R.string.profile_online), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            p.lastSeenAt != null -> Text(stringResource(R.string.profile_last_seen,
                DateUtils.getRelativeTimeSpanString(ServerTime.parse(p.lastSeenAt)?.toEpochMilli() ?: 0).toString()),
                style = MaterialTheme.typography.labelMedium, color = StTheme.extra.textSecondary)
        }
        if (p.isFriend) Text(stringResource(R.string.profile_friends_badge), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(p.bio.ifBlank { stringResource(R.string.profile_bio_empty) }, style = MaterialTheme.typography.bodyMedium)
        Text(listOf(stringResource(R.string.profile_member_since, formatDate(p.createdAt)), stringResource(R.string.profile_gifts, p.giftsReceived)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
        if (p.interests.isNotEmpty()) {
            SectionHeader(stringResource(R.string.profile_interests))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.interests.forEach { ToggleChip(interestLabel(interests, it) ?: it, true, {}, enabled = false) }
            }
        }
        if (p.languages.isNotEmpty()) {
            SectionHeader(stringResource(R.string.profile_languages))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.languages.forEach { ToggleChip(languageLabel(it), true, {}, enabled = false) }
            }
        }
    }
}

@Composable
fun MyProfileScreen(
    onEdit: () -> Unit,
    onFriends: () -> Unit,
    onPosts: (String, String) -> Unit,
    onWallet: () -> Unit,
    onSettings: () -> Unit,
    onAdmin: () -> Unit,
    onSafety: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { StTopBar(stringResource(R.string.profile_title)) }) { padding ->
        LoadStateContent(state.profile, viewModel::refresh, Modifier.padding(padding)) { p ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                ProfileHeader(p, state.interests)
                StPrimaryButton(stringResource(R.string.profile_edit), onEdit, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                NavRow(stringResource(R.string.profile_friends), onFriends, icon = Icons.Rounded.People)
                NavRow(stringResource(R.string.profile_posts), { onPosts(p.id, p.displayName) }, icon = Icons.Rounded.Article)
                NavRow(stringResource(R.string.profile_wallet), onWallet, icon = Icons.Rounded.Wallet)
                NavRow(stringResource(R.string.profile_reports), onSafety, icon = Icons.Rounded.Flag)
                NavRow(stringResource(R.string.profile_settings), onSettings, icon = Icons.Rounded.Settings)
                // Shown only to staff; every admin action is re-authorized on the server.
                if (state.isStaff) NavRow(stringResource(R.string.profile_admin), onAdmin, icon = Icons.Rounded.AdminPanelSettings)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun UserProfileScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onCallStarted: () -> Unit,
    onPosts: (String, String) -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmBlock by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }
    val errorText = state.error?.let { errorMessage(it) }
    val reported = stringResource(com.sitandtalk.core.designsystem.R.string.ds_report_sent)
    LaunchedEffect(errorText, state.info, state.openChatId, state.callStarted) {
        state.openChatId?.let(onOpenChat)
        if (state.callStarted) onCallStarted()
        val msg = errorText ?: if (state.info == "reported") reported else null
        if (msg != null) snackbar.showSnackbar(msg)
        if (msg != null || state.openChatId != null || state.callStarted) viewModel.consume()
    }
    LaunchedEffect(state.blocked) { if (state.blocked) onBack() }

    Scaffold(topBar = { StTopBar(stringResource(R.string.profile_title), onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LoadStateContent(state.profile, viewModel::refresh, Modifier.padding(padding)) { p ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                ProfileHeader(p, state.interests)
                if (!p.isSelf) {
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val pending = p.pendingRequest
                        when {
                            p.isFriend -> Unit
                            pending != null && pending.outgoing -> StSecondaryButton(stringResource(R.string.profile_cancel_request), viewModel::cancelRequest, modifier = Modifier.fillMaxWidth())
                            pending != null -> StPrimaryButton(stringResource(R.string.profile_accept_request), viewModel::acceptRequest, modifier = Modifier.fillMaxWidth())
                            else -> StPrimaryButton(stringResource(R.string.profile_add_friend), viewModel::addFriend, loading = state.busy, modifier = Modifier.fillMaxWidth())
                        }
                        if (p.canMessage) StSecondaryButton(stringResource(R.string.profile_message), viewModel::message, modifier = Modifier.fillMaxWidth())
                        if (p.canCall) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StSecondaryButton(stringResource(R.string.profile_voice_call), { viewModel.call(TalkMode.Voice) }, modifier = Modifier.weight(1f))
                                StSecondaryButton(stringResource(R.string.profile_video_call), { viewModel.call(TalkMode.Video) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    NavRow(stringResource(R.string.profile_posts), { onPosts(p.id, p.displayName) }, icon = Icons.Rounded.Article)
                    if (p.isFriend) NavRow(stringResource(R.string.profile_remove_friend), viewModel::removeFriend)
                    NavRow(stringResource(R.string.profile_report), { report = true }, tint = MaterialTheme.colorScheme.error)
                    NavRow(stringResource(R.string.profile_block), { confirmBlock = true }, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmBlock) {
        ConfirmDialog(stringResource(R.string.profile_block), stringResource(R.string.profile_block_confirm), stringResource(R.string.profile_block),
            onConfirm = { confirmBlock = false; viewModel.block() }, onDismiss = { confirmBlock = false }, destructive = true)
    }
    if (report) ReportDialog(onSubmit = { r, d -> viewModel.report(r, d); report = false }, onDismiss = { report = false })
}

// ---------------------------------------------------------------------------------------------

data class EditProfileState(
    val loaded: Boolean = false,
    val displayName: String = "",
    val bio: String = "",
    val country: String = "",
    val username: String = "",
    val originalUsername: String = "",
    val avatarPath: String? = null,
    val interests: Set<String> = emptySet(),
    val privateInterests: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val catalog: List<Interest> = emptyList(),
    val saving: Boolean = false,
    val uploading: Boolean = false,
    val error: AppException? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class EditProfileViewModel @Inject constructor(private val profiles: ProfileRepository) : ViewModel() {
    private val _state = MutableStateFlow(EditProfileState())
    val state: StateFlow<EditProfileState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val me = profiles.refreshMe()
                val catalog = profiles.interests()
                val privateSlugs = profiles.privateInterestSlugs()
                _state.update {
                    it.copy(
                        loaded = true, displayName = me.displayName, bio = me.bio, country = me.countryCode.orEmpty(),
                        username = me.username, originalUsername = me.username, avatarPath = me.avatarPath,
                        interests = (me.interests + privateSlugs).toSet(), privateInterests = privateSlugs.toSet(),
                        languages = me.languages.toSet(), catalog = catalog,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            }
        }
    }

    fun update(transform: (EditProfileState) -> EditProfileState) = _state.update(transform)

    fun toggleInterest(slug: String) = _state.update {
        it.copy(interests = if (slug in it.interests) it.interests - slug else if (it.interests.size < 15) it.interests + slug else it.interests,
            privateInterests = it.privateInterests - slug)
    }

    fun togglePrivate(slug: String) = _state.update {
        it.copy(privateInterests = if (slug in it.privateInterests) it.privateInterests - slug else it.privateInterests + slug)
    }

    fun toggleLanguage(code: String) = _state.update {
        val next = if (code in it.languages) it.languages - code else it.languages + code
        it.copy(languages = next.ifEmpty { it.languages })
    }

    fun uploadAvatar(uri: Uri) = act(upload = true) {
        profiles.uploadAvatar(uri)
        _state.update { s -> s.copy(avatarPath = profiles.me.value?.avatarPath) }
    }

    fun removeAvatar() = act(upload = true) {
        profiles.removeAvatar()
        _state.update { it.copy(avatarPath = null) }
    }

    fun changeUsername() = act { profiles.changeUsername(_state.value.username); _state.update { it.copy(originalUsername = it.username) } }

    fun save() = act {
        val s = _state.value
        profiles.updateBasics(s.displayName, s.bio, s.country.ifBlank { null })
        profiles.setInterests(s.interests.toList(), s.privateInterests.toList())
        profiles.setLanguages(s.languages.toList())
        _state.update { it.copy(saved = true) }
    }

    fun consume() = _state.update { it.copy(error = null, saved = false) }

    private fun act(upload: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { if (upload) it.copy(uploading = true) else it.copy(saving = true) }
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            } finally {
                _state.update { it.copy(saving = false, uploading = false) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditProfileScreen(onBack: () -> Unit, viewModel: EditProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.let { errorMessage(it) }
    val savedText = stringResource(R.string.edit_saved)
    LaunchedEffect(errorText, state.saved) {
        val msg = errorText ?: if (state.saved) savedText else null
        if (msg != null) {
            snackbar.showSnackbar(msg)
            viewModel.consume()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? -> uri?.let(viewModel::uploadAvatar) }
    Scaffold(topBar = { StTopBar(stringResource(R.string.edit_title), onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (!state.loaded) {
            com.sitandtalk.core.designsystem.LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(state.avatarPath, state.displayName, size = 72.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    StTextButton(stringResource(if (state.uploading) R.string.edit_uploading else R.string.edit_avatar),
                        { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.uploading)
                    if (state.avatarPath != null) StTextButton(stringResource(R.string.edit_avatar_remove), viewModel::removeAvatar, enabled = !state.uploading)
                }
            }
            StTextField(state.displayName, { v -> viewModel.update { it.copy(displayName = v) } }, stringResource(R.string.edit_display_name), maxLength = 32)
            StTextField(state.bio, { v -> viewModel.update { it.copy(bio = v) } }, stringResource(R.string.edit_bio), singleLine = false, minLines = 3, maxLength = 280)
            StTextField(state.country, { v -> viewModel.update { it.copy(country = v.uppercase().filter { c -> c in 'A'..'Z' }.take(2)) } }, stringResource(R.string.edit_country))
            Spacer(Modifier.height(8.dp))
            StTextField(state.username, { v -> viewModel.update { it.copy(username = v.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' }) } },
                stringResource(R.string.edit_username), supporting = stringResource(R.string.edit_username_note), maxLength = 24)
            if (state.username != state.originalUsername) StTextButton(stringResource(R.string.edit_username_save), viewModel::changeUsername)
            SectionHeader(stringResource(R.string.edit_interests))
            Text(stringResource(R.string.edit_interests_private), style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.catalog.forEach { i -> ToggleChip(interestLabel(i), i.slug in state.interests, { viewModel.toggleInterest(i.slug) }) }
            }
            if (state.interests.isNotEmpty()) {
                Text(stringResource(R.string.edit_private), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.catalog.filter { it.slug in state.interests }.forEach { i ->
                        ToggleChip(interestLabel(i), i.slug in state.privateInterests, { viewModel.togglePrivate(i.slug) })
                    }
                }
            }
            SectionHeader(stringResource(R.string.edit_languages))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SupportedLanguages.forEach { code -> ToggleChip(languageLabel(code), code in state.languages, { viewModel.toggleLanguage(code) }) }
            }
            Spacer(Modifier.height(16.dp))
            StPrimaryButton(stringResource(R.string.edit_save), viewModel::save, loading = state.saving,
                enabled = state.displayName.trim().length in 2..32, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }
}
