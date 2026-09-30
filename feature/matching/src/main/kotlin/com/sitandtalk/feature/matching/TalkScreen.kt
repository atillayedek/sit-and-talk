package com.sitandtalk.feature.matching

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sitandtalk.core.data.MatchPhase
import com.sitandtalk.core.designsystem.BigRoundAction
import com.sitandtalk.core.designsystem.InfoBanner
import com.sitandtalk.core.designsystem.SitAndTalkWordmark
import com.sitandtalk.core.designsystem.StPrimaryButton
import com.sitandtalk.core.designsystem.StSecondaryButton
import com.sitandtalk.core.designsystem.StTextField
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.SupportedLanguages
import com.sitandtalk.core.designsystem.SwitchRow
import com.sitandtalk.core.designsystem.ToggleChip
import com.sitandtalk.core.designsystem.WaveRings
import com.sitandtalk.core.designsystem.errorMessage
import com.sitandtalk.core.designsystem.languageLabel
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.model.TalkIntent
import com.sitandtalk.core.model.TalkMode
import kotlinx.coroutines.delay

@Composable
fun TalkScreen(
    onOpenNotifications: () -> Unit,
    unreadNotifications: Int,
    interestLabel: @Composable (String) -> String,
    viewModel: TalkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    val requiredPermissions = remember(state.prefs.mode) {
        buildList {
            if (state.prefs.mode != TalkMode.Text) add(Manifest.permission.RECORD_AUDIO)
            if (state.prefs.mode == TalkMode.Video) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && state.prefs.mode != TalkMode.Text) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val essential = remember(state.prefs.mode) {
        buildList {
            if (state.prefs.mode != TalkMode.Text) add(Manifest.permission.RECORD_AUDIO)
            if (state.prefs.mode == TalkMode.Video) add(Manifest.permission.CAMERA)
        }
    }
    fun hasEssential() = essential.all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ok = essential.all { result[it] == true || ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        permissionDenied = !ok
        if (ok) viewModel.start()
    }
    val onStart = {
        if (hasEssential() && requiredPermissions.all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            permissionDenied = false
            viewModel.start()
        } else {
            launcher.launch(requiredPermissions.toTypedArray())
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(StTheme.extra.talkGradient)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SitAndTalkWordmark(Modifier.weight(1f))
            IconButton(onClick = onOpenNotifications) {
                Box {
                    Icon(Icons.Rounded.Notifications, contentDescription = stringResource(R.string.talk_notifications), tint = Color.White)
                    if (unreadNotifications > 0) {
                        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(StTheme.extra.coral).align(Alignment.TopEnd))
                    }
                }
            }
        }

        val phase = state.phase
        val idle = phase is MatchPhase.Idle || phase is MatchPhase.Failed
        if (idle) {
            ModeSelector(state.prefs.mode, viewModel::setMode)
        }

        Box(Modifier.fillMaxWidth().height(340.dp), contentAlignment = Alignment.Center) {
            when (phase) {
                is MatchPhase.Found -> FoundCard(phase.state.peerAlias.orEmpty(), phase.state.commonInterests, phase.state.expiresAt, interestLabel,
                    waiting = false, onAccept = { viewModel.respond(true) }, onSkip = { viewModel.respond(false) })
                is MatchPhase.WaitingForPeer -> FoundCard(phase.state.peerAlias.orEmpty(), phase.state.commonInterests, phase.state.expiresAt, interestLabel,
                    waiting = true, onAccept = {}, onSkip = { viewModel.respond(false) })
                else -> {
                    val searching = phase is MatchPhase.Searching || phase is MatchPhase.Joining
                    WaveRings(Modifier.size(320.dp), active = searching || idle)
                    if (state.prefs.mode == TalkMode.Video && idle && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED && state.videoEnabled) {
                        // Own camera preview before matching; the start action stays available.
                        Box(contentAlignment = Alignment.BottomCenter) {
                            CameraPreview(viewModel)
                            BigRoundAction(
                                text = stringResource(R.string.talk_start),
                                onClick = onStart,
                                enabled = state.modeEnabled && state.backendConfigured && !state.inRoom,
                                size = 124.dp,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    } else {
                        BigRoundAction(
                            text = stringResource(if (searching) R.string.talk_searching else R.string.talk_start),
                            subtitle = if (phase is MatchPhase.Joining) stringResource(R.string.talk_joining) else null,
                            onClick = { if (idle) onStart() },
                            enabled = idle && state.modeEnabled && state.backendConfigured && !state.inRoom,
                        )
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (phase) {
                is MatchPhase.Searching, MatchPhase.Joining -> {
                    Text(stringResource(R.string.talk_searching_body), color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    StSecondaryButton(stringResource(R.string.talk_cancel), viewModel::cancel, modifier = Modifier.background(Color.White, RoundedCornerShape(16.dp)))
                }
                is MatchPhase.Failed -> {
                    InfoBanner(errorMessage(phase.error), isWarning = true)
                    LaunchedEffect(phase) {
                        delay(6_000)
                        viewModel.clearError()
                    }
                }
                else -> Unit
            }
            if (idle) {
                when {
                    !state.backendConfigured -> InfoBanner(stringResource(R.string.talk_not_configured), isWarning = true)
                    state.inRoom -> {
                        InfoBanner(stringResource(R.string.talk_in_room))
                        Spacer(Modifier.height(8.dp))
                        StPrimaryButton(stringResource(R.string.talk_leave_room), viewModel::leaveRoom)
                    }
                    !state.modeEnabled -> InfoBanner(stringResource(if (state.prefs.mode == TalkMode.Video) R.string.talk_video_disabled else R.string.talk_mode_disabled))
                    else -> Text(stringResource(R.string.talk_start_hint), color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
                if (permissionDenied) {
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(stringResource(if (state.prefs.mode == TalkMode.Video) R.string.talk_camera_needed else R.string.talk_mic_needed), isWarning = true)
                    StSecondaryButton(stringResource(R.string.talk_open_settings), {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }, modifier = Modifier.padding(top = 8.dp).background(Color.White, RoundedCornerShape(16.dp)))
                }
            }
        }

        if (idle) {
            Spacer(Modifier.height(20.dp))
            PreferencesPanel(state, viewModel)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModeSelector(mode: TalkMode, onMode: (TalkMode) -> Unit) {
    val modes = listOf(TalkMode.Voice to R.string.talk_mode_voice, TalkMode.Video to R.string.talk_mode_video, TalkMode.Text to R.string.talk_mode_text)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        modes.forEachIndexed { index, (m, label) ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onMode(m) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Color.White,
                    activeContentColor = MaterialTheme.colorScheme.primary,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = Color.White,
                    activeBorderColor = Color.White,
                    inactiveBorderColor = Color.White,
                ),
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun CameraPreview(viewModel: TalkViewModel) {
    val description = stringResource(R.string.talk_preview)
    Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.size(width = 220.dp, height = 300.dp)) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { viewModel.startPreview(it) }.apply { contentDescription = description } },
            modifier = Modifier.fillMaxSize(),
        )
    }
    DisposableEffect(Unit) { onDispose { viewModel.stopPreview() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoundCard(
    alias: String,
    common: List<String>,
    expiresAt: String?,
    interestLabel: @Composable (String) -> String,
    waiting: Boolean,
    onAccept: () -> Unit,
    onSkip: () -> Unit,
) {
    var secondsLeft by remember { mutableIntStateOf(ServerTime.remaining(expiresAt)?.seconds?.toInt() ?: 0) }
    LaunchedEffect(expiresAt) {
        while (true) {
            secondsLeft = ServerTime.remaining(expiresAt)?.seconds?.toInt() ?: 0
            if (secondsLeft <= 0) break
            delay(500)
        }
    }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(20.dp).fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.talk_found), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.talk_found_with, alias), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            if (common.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.talk_common), style = MaterialTheme.typography.labelLarge, color = StTheme.extra.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    common.forEach { ToggleChip(interestLabel(it), selected = true, onClick = {}, enabled = false) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.talk_seconds_left, secondsLeft), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(12.dp))
            if (waiting) {
                Text(stringResource(R.string.talk_waiting_peer), color = StTheme.extra.textSecondary)
                Spacer(Modifier.height(8.dp))
                StSecondaryButton(stringResource(R.string.talk_cancel), onSkip, modifier = Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StSecondaryButton(stringResource(R.string.talk_skip), onSkip, modifier = Modifier.weight(1f))
                    StPrimaryButton(stringResource(R.string.talk_accept), onAccept, modifier = Modifier.weight(1f), enabled = secondsLeft > 0)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreferencesPanel(state: TalkUiState, vm: TalkViewModel) {
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.talk_preferences), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.talk_intent), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TalkIntent.entries.forEach { intent ->
                    ToggleChip(intentLabel(intent), state.prefs.intent == intent, { vm.setIntent(intent) })
                }
            }
            Text(stringResource(R.string.talk_intent_note), style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.talk_languages), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val selected = state.effectiveLanguages
                (SupportedLanguages.filter { it in selected } + SupportedLanguages.filter { it !in selected }.take(6)).forEach { code ->
                    ToggleChip(languageLabel(code), code in selected, { vm.toggleLanguage(code) })
                }
            }
            SwitchRow(stringResource(R.string.talk_strict_language), state.prefs.strictLanguage, vm::setStrict)
            if (state.prefs.strictLanguage) {
                Text(stringResource(R.string.talk_no_match_filters), style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
            }
            Spacer(Modifier.height(8.dp))
            StTextField(
                value = state.effectiveAlias,
                onValueChange = vm::setAlias,
                label = stringResource(R.string.talk_alias),
                supporting = stringResource(R.string.talk_alias_hint),
                maxLength = 32,
            )
        }
    }
}

@Composable
private fun intentLabel(intent: TalkIntent): String = stringResource(
    when (intent) {
        TalkIntent.Friends -> R.string.talk_intent_friends
        TalkIntent.Chat -> R.string.talk_intent_chat
        TalkIntent.Gaming -> R.string.talk_intent_gaming
        TalkIntent.Language -> R.string.talk_intent_language
        TalkIntent.Topic -> R.string.talk_intent_topic
        TalkIntent.Listen -> R.string.talk_intent_listen
    },
)
