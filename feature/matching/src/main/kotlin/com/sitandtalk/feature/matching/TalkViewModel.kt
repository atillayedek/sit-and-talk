package com.sitandtalk.feature.matching

import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitandtalk.core.data.ActiveRoomController
import com.sitandtalk.core.data.BootstrapRepository
import com.sitandtalk.core.data.MatchPhase
import com.sitandtalk.core.data.MatchmakingController
import com.sitandtalk.core.data.ProfileRepository
import com.sitandtalk.core.data.TalkPreferences
import com.sitandtalk.core.data.TalkPrefs
import com.sitandtalk.core.model.AppConfig
import com.sitandtalk.core.model.TalkIntent
import com.sitandtalk.core.model.TalkMode
import com.sitandtalk.core.rtc.RtcManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TalkUiState(
    val prefs: TalkPrefs = TalkPrefs(),
    val phase: MatchPhase = MatchPhase.Idle,
    val profileLanguages: List<String> = emptyList(),
    val defaultAlias: String = "",
    val voiceEnabled: Boolean = true,
    val videoEnabled: Boolean = false,
    val textEnabled: Boolean = true,
    val backendConfigured: Boolean = true,
    val inRoom: Boolean = false,
) {
    val effectiveAlias get() = prefs.alias.ifBlank { defaultAlias }
    val effectiveLanguages get() = prefs.languages.ifEmpty { profileLanguages.toSet() }
    val modeEnabled get() = when (prefs.mode) {
        TalkMode.Voice -> voiceEnabled
        TalkMode.Video -> videoEnabled
        TalkMode.Text -> textEnabled
    }
}

@HiltViewModel
class TalkViewModel @Inject constructor(
    private val matchmaking: MatchmakingController,
    private val preferences: TalkPreferences,
    private val profiles: ProfileRepository,
    private val bootstrap: BootstrapRepository,
    private val rooms: ActiveRoomController,
    private val rtc: RtcManager,
    private val config: AppConfig,
) : ViewModel() {

    val state: StateFlow<TalkUiState> = combine(
        preferences.prefs,
        matchmaking.phase,
        profiles.me,
        bootstrap.current,
        rooms.active,
    ) { prefs, phase, me, boot, room ->
        TalkUiState(
            prefs = prefs,
            phase = phase,
            profileLanguages = me?.languages.orEmpty(),
            defaultAlias = me?.displayName.orEmpty(),
            voiceEnabled = boot?.flag("voice_matching") ?: true,
            videoEnabled = boot?.flag("video_matching") == true,
            textEnabled = boot?.flag("text_matching") ?: true,
            backendConfigured = config.isBackendConfigured && config.isRtcConfigured,
            inRoom = room != null && room.removed.not(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TalkUiState())

    init {
        viewModelScope.launch { if (profiles.me.value == null) runCatching { profiles.refreshMe() } }
    }

    private fun update(transform: (TalkPrefs) -> TalkPrefs) {
        viewModelScope.launch { preferences.update(transform, state.value.prefs) }
    }

    fun setMode(mode: TalkMode) = update { it.copy(mode = mode) }
    fun setIntent(intent: TalkIntent) = update { it.copy(intent = intent) }
    fun setAlias(alias: String) = update { it.copy(alias = alias.take(32)) }
    fun toggleLanguage(code: String) = update {
        val base = it.languages.ifEmpty { state.value.profileLanguages.toSet() }
        val next = if (code in base) base - code else base + code
        it.copy(languages = next.ifEmpty { base })
    }
    fun setStrict(strict: Boolean) = update { it.copy(strictLanguage = strict) }

    fun start() {
        val s = state.value
        if (!s.modeEnabled || !s.backendConfigured) return
        stopPreview()
        matchmaking.join(s.prefs.mode, s.prefs.intent, s.effectiveAlias, s.effectiveLanguages.toList(), s.prefs.strictLanguage)
    }

    fun cancel() = matchmaking.cancel()
    fun respond(accept: Boolean) = matchmaking.respond(accept)
    fun clearError() = matchmaking.clearError()
    fun leaveRoom() = rooms.leave()

    fun startPreview(view: View) {
        if (config.agoraAppId.isNotBlank()) rtc.startPreview(config.agoraAppId, view)
    }

    fun stopPreview() {
        if (!rtc.state.value.isInChannel) rtc.stopPreview()
    }
}
