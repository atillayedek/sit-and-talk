package com.sitandtalk.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitandtalk.app.work.OutboxWorker
import com.sitandtalk.core.data.ActiveCall
import com.sitandtalk.core.data.ActiveCallController
import com.sitandtalk.core.data.ActiveRoom
import com.sitandtalk.core.data.ActiveRoomController
import com.sitandtalk.core.data.AuthCallbackResult
import com.sitandtalk.core.data.AuthRepository
import com.sitandtalk.core.data.AuthStatus
import com.sitandtalk.core.data.BootstrapRepository
import com.sitandtalk.core.data.NotificationsRepository
import com.sitandtalk.core.data.ProfileRepository
import com.sitandtalk.core.data.PushRegistrar
import com.sitandtalk.core.model.AppConfig
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.Bootstrap
import com.sitandtalk.core.model.Interest
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.network.toAppException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

sealed interface AppState {
    data object Loading : AppState
    data class ConfigMissing(val keys: List<String>) : AppState
    data class BootstrapFailed(val error: AppException) : AppState
    data object UpdateRequired : AppState
    data class Maintenance(val message: String) : AppState
    data object SignedOut : AppState
    data object PasswordRecovery : AppState
    data object NeedsOnboarding : AppState
    data class Suspended(val bootstrap: Bootstrap) : AppState
    data class Ready(val bootstrap: Bootstrap) : AppState
}

@HiltViewModel
class AppStateViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    val config: AppConfig,
    private val auth: AuthRepository,
    private val bootstrap: BootstrapRepository,
    private val profiles: ProfileRepository,
    private val notifications: NotificationsRepository,
    private val push: PushRegistrar,
    private val activeCall: ActiveCallController,
    private val activeRoom: ActiveRoomController,
) : ViewModel() {

    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _pendingLink = MutableStateFlow<AppLink?>(null)
    /** A destination to open once the user reaches the main UI. */
    val pendingLink: StateFlow<AppLink?> = _pendingLink.asStateFlow()

    private val _authMessage = MutableStateFlow<String?>(null)
    /** Error code from an auth callback, shown on the sign-in screen. */
    val authMessage: StateFlow<String?> = _authMessage.asStateFlow()

    private val _unread = MutableStateFlow(0)
    val unreadNotifications: StateFlow<Int> = _unread.asStateFlow()

    private val _catalog = MutableStateFlow<List<Interest>>(emptyList())
    val interestCatalog: StateFlow<List<Interest>> = _catalog.asStateFlow()

    private val _incomingCall = MutableStateFlow(false)
    /** True when a call started elsewhere (incoming direct call, restored session) should be shown. */
    val openCall: StateFlow<Boolean> = _incomingCall.asStateFlow()

    val activeCallState: StateFlow<ActiveCall?> get() = activeCall.active
    val activeRoomState: StateFlow<ActiveRoom?> get() = activeRoom.active

    private var sessionJobs: List<Job> = emptyList()
    private var readyFor: String? = null

    init {
        if (!config.isBackendConfigured) {
            _state.value = AppState.ConfigMissing(config.missingKeys)
        } else {
            viewModelScope.launch {
                combine(auth.status, auth.passwordRecovery) { s, r -> s to r }
                    .distinctUntilChanged()
                    .collect { (status, recovery) -> onAuth(status, recovery) }
            }
        }
    }

    private suspend fun onAuth(status: AuthStatus, recovery: Boolean) {
        when (status) {
            AuthStatus.Loading -> if (_state.value !is AppState.Ready) _state.value = AppState.Loading
            AuthStatus.SignedOut -> {
                stopSession()
                _state.value = AppState.SignedOut
            }
            is AuthStatus.SignedIn -> if (recovery) _state.value = AppState.PasswordRecovery else loadBootstrap(status.userId)
            // Token refresh failed because of the network: keep the current screen if one is shown.
            is AuthStatus.Offline -> if (_state.value !is AppState.Ready) {
                _state.value = AppState.BootstrapFailed(AppException(AppException.NETWORK))
            }
        }
    }

    fun retry() {
        val uid = auth.currentUserId()
        viewModelScope.launch { if (uid != null) loadBootstrap(uid) else _state.value = AppState.SignedOut }
    }

    private suspend fun loadBootstrap(userId: String) {
        if (_state.value !is AppState.Ready) _state.value = AppState.Loading
        val b = try {
            bootstrap.refresh()
        } catch (e: Exception) {
            _state.value = AppState.BootstrapFailed(e.toAppException())
            return
        }
        _state.value = when {
            b.updateRequired -> AppState.UpdateRequired
            b.maintenance.enabled && !b.isStaff -> AppState.Maintenance(
                if (java.util.Locale.getDefault().language == "tr") b.maintenance.messageTr else b.maintenance.messageEn.ifBlank { b.maintenance.messageTr },
            )
            b.suspended -> AppState.Suspended(b)
            !b.profileComplete -> AppState.NeedsOnboarding
            else -> AppState.Ready(b)
        }
        if (_state.value is AppState.Ready && readyFor != userId) {
            readyFor = userId
            startSession()
        }
    }

    fun onboardingCompleted() = retry()

    private fun startSession() {
        stopSession()
        sessionJobs = listOf(
            viewModelScope.launch { runCatching { push.registerCurrentDevice() } },
            viewModelScope.launch { runCatching { _catalog.value = profiles.interests() } },
            viewModelScope.launch {
                // Restore a call or match that survived process death, and surface incoming calls.
                if (activeCall.active.value == null && runCatching { activeCall.resumeIfAny() }.getOrDefault(false)) {
                    _incomingCall.value = true
                }
            },
            viewModelScope.launch {
                refreshNotifications()
                runCatching { notifications.changes().collect { refreshNotifications() } }
            },
            viewModelScope.launch {
                while (isActive) {
                    profiles.touchPresence()
                    delay(60_000)
                }
            },
        )
        OutboxWorker.schedule(context)
    }

    private fun stopSession() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs = emptyList()
        readyFor = null
        _unread.value = 0
    }

    private suspend fun refreshNotifications() {
        val list = runCatching { notifications.list() }.getOrNull() ?: return
        _unread.value = list.count { it.readAt == null }
        val now = ServerTime.now()
        val ringing = list.any { n ->
            n.kind == "incoming_call" && n.readAt == null &&
                n.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }?.isAfter(now) == true
        }
        if (ringing && activeCall.active.value == null && runCatching { activeCall.resumeIfAny() }.getOrDefault(false)) {
            _incomingCall.value = true
        }
    }

    fun consumeOpenCall() {
        _incomingCall.value = false
    }

    /** Called on resume: presence, queued messages and bootstrap flags stay fresh. */
    fun onForeground() {
        if (_state.value !is AppState.Ready) return
        OutboxWorker.schedule(context)
        viewModelScope.launch {
            runCatching { bootstrap.refresh() }.onSuccess { b ->
                if (b.updateRequired) _state.value = AppState.UpdateRequired
                else if (b.suspended) _state.value = AppState.Suspended(b)
                else _state.value = AppState.Ready(b)
            }
            refreshNotifications()
        }
    }

    fun handleUri(uri: Uri, notificationId: String?) {
        val redirect = Uri.parse(config.authRedirectUrl.ifBlank { "sitandtalk://auth-callback" })
        val link = AppLink.parse(uri, redirect.scheme.orEmpty(), redirect.host.orEmpty(), config.appLinkHost) ?: return
        if (notificationId != null) {
            viewModelScope.launch { runCatching { notifications.markRead(listOf(notificationId)) }; refreshNotifications() }
        }
        when (link) {
            is AppLink.AuthCallback -> viewModelScope.launch {
                when (val r = auth.handleCallback(link.uri)) {
                    AuthCallbackResult.SignedIn, AuthCallbackResult.PasswordRecovery -> Unit
                    is AuthCallbackResult.Failed -> _authMessage.value = r.code
                }
            }
            AppLink.ActiveCall -> viewModelScope.launch {
                if (activeCall.active.value != null || runCatching { activeCall.resumeIfAny() }.getOrDefault(false)) {
                    _incomingCall.value = true
                } else {
                    _pendingLink.value = AppLink.Notifications
                }
            }
            else -> _pendingLink.value = link
        }
    }

    fun openActiveSession() {
        when {
            activeCall.active.value != null -> _incomingCall.value = true
            activeRoom.active.value != null -> _pendingLink.value = AppLink.ActiveRoom
        }
    }

    fun consumeLink() {
        _pendingLink.value = null
    }

    fun consumeAuthMessage() {
        _authMessage.value = null
    }

    fun cancelRecovery() {
        auth.cancelPasswordRecovery()
    }

    fun signOut() {
        viewModelScope.launch { runCatching { auth.signOut() } }
    }
}
