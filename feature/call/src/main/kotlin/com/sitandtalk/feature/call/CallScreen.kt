package com.sitandtalk.feature.call

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sitandtalk.core.data.ActiveCall
import com.sitandtalk.core.designsystem.Avatar
import com.sitandtalk.core.designsystem.CallControlButton
import com.sitandtalk.core.designsystem.CircularCountdown
import com.sitandtalk.core.designsystem.ConfirmDialog
import com.sitandtalk.core.designsystem.InfoBanner
import com.sitandtalk.core.designsystem.ReportDialog
import com.sitandtalk.core.designsystem.StColors
import com.sitandtalk.core.designsystem.StPrimaryButton
import com.sitandtalk.core.designsystem.StSecondaryButton
import com.sitandtalk.core.designsystem.StTextButton
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.ToggleChip
import com.sitandtalk.core.designsystem.errorMessage
import com.sitandtalk.core.designsystem.formatDuration
import com.sitandtalk.core.model.CallState
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.model.TalkMode
import com.sitandtalk.core.rtc.AudioRoute
import com.sitandtalk.core.rtc.RtcConnection
import com.sitandtalk.core.rtc.RtcState
import kotlinx.coroutines.delay
import java.time.Duration

@Composable
fun CallScreen(
    onClose: () -> Unit,
    onNewCall: () -> Unit,
    onOpenChat: (String) -> Unit,
    interestLabel: @Composable (String) -> String,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val active by viewModel.active.collectAsStateWithLifecycle()
    val rtc by viewModel.rtcState.collectAsStateWithLifecycle()
    val extras by viewModel.extras.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val current = active
    if (current == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val call = current.call

    val messageText = when (extras.message) {
        "friend_request_sent" -> stringResource(R.string.call_friend_request_sent)
        "reported" -> stringResource(R.string.call_reported)
        "gift_sent" -> stringResource(R.string.call_gift_sent)
        else -> null
    }
    val errorText = (extras.error ?: current.lastError?.takeIf { it.code != "mic_permission" })?.let { errorMessage(it) }
    LaunchedEffect(messageText, errorText) {
        val msg = messageText ?: errorText
        if (msg != null) {
            snackbar.showSnackbar(msg)
            viewModel.consumeMessage()
            viewModel.consumeControllerError()
        }
    }
    LaunchedEffect(extras.openChatId) {
        extras.openChatId?.let {
            viewModel.consumeMessage()
            viewModel.dismiss()
            onOpenChat(it)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(StTheme.extra.talkGradient),
    ) {
        when {
            call.isEnded || call.iLeft -> PostCall(call, extras, viewModel, onClose = {
                viewModel.dismiss()
                onClose()
            }, onNewCall = {
                viewModel.dismiss()
                onNewCall()
            })
            call.isRinging -> Ringing(call, viewModel)
            call.mode == TalkMode.Text -> TextCall(current, viewModel, interestLabel)
            call.mode == TalkMode.Video -> VideoCall(current, rtc, viewModel)
            else -> VoiceCall(current, rtc, viewModel, interestLabel)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun Ringing(call: CallState, vm: CallViewModel) {
    val context = LocalContext.current
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.answer(true)
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 48.dp)) {
            Avatar(call.peerProfile?.avatarPath, call.peerProfile?.displayName ?: call.peerAlias.orEmpty(), size = 112.dp)
            Spacer(Modifier.height(16.dp))
            Text(call.peerProfile?.displayName ?: call.peerAlias.orEmpty(), style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(
                stringResource(if (call.isCaller) R.string.call_ringing_out else R.string.call_ringing_in),
                style = MaterialTheme.typography.titleMedium, color = Color.White,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            CallControlButton(Icons.Rounded.CallEnd, stringResource(if (call.isCaller) R.string.call_end else R.string.call_decline),
                onClick = { if (call.isCaller) vm.end() else vm.answer(false) },
                containerColor = StTheme.extra.endCall, contentColor = StTheme.extra.onEndCall)
            if (!call.isCaller) {
                CallControlButton(Icons.Rounded.Call, stringResource(R.string.call_answer), onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        vm.answer(true)
                    } else {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }, containerColor = StColors.SuccessDeep, contentColor = Color.White)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceCall(active: ActiveCall, rtc: RtcState, vm: CallViewModel, interestLabel: @Composable (String) -> String) {
    val call = active.call
    var showReport by rememberSaveable { mutableStateOf(false) }
    var showBlock by rememberSaveable { mutableStateOf(false) }
    var showGifts by rememberSaveable { mutableStateOf(false) }
    var alsoBlock by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.requestMode(TalkMode.Video)
    }
    val requestVideo = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            vm.requestMode(TalkMode.Video)
        } else {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusLine(call, rtc)
        Spacer(Modifier.height(16.dp))
        Timer(call) {
            val speaking = call.peerRtcUid != null && call.peerRtcUid in rtc.speaking
            Box(
                Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (speaking) Color.White.copy(alpha = 0.35f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Avatar(call.peerAvatarPath, call.peerProfile?.displayName ?: call.peerAlias.orEmpty(), size = 116.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(call.peerProfile?.displayName ?: call.peerAlias.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = Color.White)
        if (call.commonInterests.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                call.commonInterests.forEach { ToggleChip(interestLabel(it), selected = true, onClick = {}, enabled = false) }
            }
        }
        RtcProblems(active, vm)
        RequestBanners(call, vm, requestVideo)
        if (call.isRandom) Icebreaker()
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            CallControlButton(
                if (rtc.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                stringResource(if (rtc.micMuted) R.string.call_unmute else R.string.call_mute),
                vm::toggleMic,
                active = !rtc.micMuted,
                stateLabel = stringResource(if (rtc.micMuted) R.string.call_mic_off else R.string.call_mic_on),
            )
            CallControlButton(
                when (rtc.audioRoute) {
                    AudioRoute.Bluetooth -> Icons.Rounded.BluetoothAudio
                    AudioRoute.WiredHeadset -> Icons.Rounded.Headset
                    else -> Icons.Rounded.VolumeUp
                },
                when (rtc.audioRoute) {
                    AudioRoute.Bluetooth -> stringResource(R.string.call_route_bluetooth)
                    AudioRoute.WiredHeadset -> stringResource(R.string.call_route_headset)
                    else -> stringResource(R.string.call_speaker)
                },
                vm::toggleSpeaker,
                active = rtc.speakerOn,
                enabled = rtc.audioRoute != AudioRoute.Bluetooth && rtc.audioRoute != AudioRoute.WiredHeadset,
                stateLabel = stringResource(if (rtc.speakerOn) R.string.call_speaker_on else R.string.call_speaker_off),
            )
            if (call.isRandom && call.endsAt != null) {
                CallControlButton(
                    Icons.Rounded.MoreTime,
                    stringResource(if (call.extensionRequestedByMe == true) R.string.call_extend_requested else R.string.call_extend),
                    { if (call.extensionRequestedByMe == true) vm.withdrawExtension() else vm.requestExtension() },
                    active = call.extensionRequestedByMe == true,
                    enabled = !active.busy,
                )
            }
            CallControlButton(Icons.Rounded.Videocam, stringResource(if (call.myUpgradeRequest == TalkMode.Video) R.string.call_video_requested else R.string.call_video_request),
                { if (call.myUpgradeRequest == TalkMode.Video) vm.requestMode(TalkMode.Voice) else requestVideo() },
                active = call.myUpgradeRequest == TalkMode.Video)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            if (call.isRandom) CallControlButton(Icons.Rounded.PersonAdd, stringResource(R.string.call_add_friend), vm::addFriend)
            CallControlButton(Icons.Rounded.CardGiftcard, stringResource(R.string.call_gift), {
                vm.loadGifts()
                showGifts = true
            })
            CallControlButton(Icons.Rounded.Flag, stringResource(R.string.call_report), { showReport = true })
            CallControlButton(Icons.Rounded.Block, stringResource(R.string.call_block), { showBlock = true })
        }
        Spacer(Modifier.height(24.dp))
        CallControlButton(Icons.Rounded.CallEnd, stringResource(R.string.call_end), { vm.end() },
            containerColor = StTheme.extra.endCall, contentColor = StTheme.extra.onEndCall)
        Spacer(Modifier.height(24.dp))
    }

    if (showReport) {
        ReportDialog(
            onSubmit = { reason, details ->
                vm.report(reason, details, alsoBlock)
                showReport = false
            },
            onDismiss = { showReport = false },
            alsoBlockLabel = stringResource(R.string.call_also_block),
            alsoBlock = alsoBlock,
            onAlsoBlockChange = { alsoBlock = it },
        )
    }
    if (showBlock) {
        ConfirmDialog(
            title = stringResource(R.string.call_block_confirm_title),
            message = stringResource(R.string.call_block_confirm_body),
            confirmLabel = stringResource(R.string.call_block),
            onConfirm = {
                showBlock = false
                vm.block()
            },
            onDismiss = { showBlock = false },
            destructive = true,
        )
    }
    if (showGifts) GiftSheet(vm) { showGifts = false }
}

@Composable
private fun StatusLine(call: CallState, rtc: RtcState) {
    val text = when {
        rtc.connection == RtcConnection.Reconnecting -> stringResource(R.string.call_reconnecting)
        call.isConnecting && !call.peerJoined && rtc.isInChannel -> stringResource(R.string.call_waiting_peer)
        call.isConnecting -> stringResource(R.string.call_connecting)
        else -> stringResource(R.string.call_active)
    }
    Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
}

/** Countdown ring for timed random calls (server deadline), elapsed time for direct calls. */
@Composable
private fun Timer(call: CallState, content: @Composable () -> Unit) {
    var nowTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(call.sessionId) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(500)
        }
    }
    val started = ServerTime.parse(call.startedAt)
    val ends = ServerTime.parse(call.endsAt)
    // Reading the ticking state makes this block recompose twice per second.
    val tick = nowTick
    if (tick < 0) return
    if (ends != null && started != null) {
        val total = Duration.between(started, ends).seconds.coerceAtLeast(1)
        val left = ServerTime.remaining(call.endsAt)?.seconds ?: 0
        val label = stringResource(R.string.call_time_left) + " " + formatDuration(left)
        CircularCountdown(fraction = left.toFloat() / total, label = label, modifier = Modifier.size(180.dp)) {
            content()
        }
        Text(formatDuration(left), color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
    } else {
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) { content() }
        if (started != null) {
            val elapsed = Duration.between(started, ServerTime.now()).seconds
            Text(formatDuration(elapsed), color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun RtcProblems(active: ActiveCall, vm: CallViewModel) {
    if (active.lastError?.code == "mic_permission") {
        Spacer(Modifier.height(12.dp))
        InfoBanner(stringResource(R.string.call_mic_permission), isWarning = true)
    } else if (active.rtcJoinFailed) {
        Spacer(Modifier.height(12.dp))
        InfoBanner(stringResource(R.string.call_rtc_failed), isWarning = true)
        StSecondaryButton(stringResource(R.string.call_retry), vm::retryRtc, modifier = Modifier.padding(top = 8.dp).background(Color.White, RoundedCornerShape(16.dp)))
    }
}

@Composable
private fun RequestBanners(call: CallState, vm: CallViewModel, requestVideo: () -> Unit) {
    val peer = call.peerProfile?.displayName ?: call.peerAlias.orEmpty()
    if (call.extensionRequestedByPeer == true) {
        Spacer(Modifier.height(12.dp))
        BannerWithAction(stringResource(R.string.call_extend_peer, peer), stringResource(R.string.call_extend_accept), vm::requestExtension)
    }
    if (call.peerUpgradeRequest == TalkMode.Video && call.mode != TalkMode.Video) {
        Spacer(Modifier.height(12.dp))
        BannerWithAction(stringResource(R.string.call_video_peer, peer), stringResource(R.string.call_video_accept), requestVideo)
    }
    if (call.peerUpgradeRequest == TalkMode.Voice && call.mode == TalkMode.Text) {
        Spacer(Modifier.height(12.dp))
        BannerWithAction(stringResource(R.string.call_voice_peer, peer), stringResource(R.string.call_voice_accept)) { vm.requestMode(TalkMode.Voice) }
    }
}

@Composable
private fun BannerWithAction(text: String, action: String, onAction: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = StColors.Ink, style = MaterialTheme.typography.bodyMedium)
            StTextButton(action, onAction)
        }
    }
}

@Composable
private fun Icebreaker() {
    val questions = stringArrayResource(R.array.icebreakers)
    var index by rememberSaveable { mutableIntStateOf(0) }
    Spacer(Modifier.height(16.dp))
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = 0.16f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.call_icebreaker), style = MaterialTheme.typography.labelLarge, color = Color.White)
            Text(questions[index % questions.size], style = MaterialTheme.typography.bodyLarge, color = Color.White)
            StTextButton(stringResource(R.string.call_icebreaker_next), { index++ })
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun VideoCall(active: ActiveCall, rtc: RtcState, vm: CallViewModel) {
    val call = active.call
    val context = LocalContext.current
    var showReport by rememberSaveable { mutableStateOf(false) }
    var alsoBlock by rememberSaveable { mutableStateOf(true) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.enableLocalVideo(true) else vm.requestMode(TalkMode.Voice)
    }
    LaunchedEffect(call.sessionId, call.mode) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            vm.enableLocalVideo(true)
        } else {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val peerUid = call.peerRtcUid
    val peerVideo = peerUid != null && rtc.remoteUsers[peerUid]?.videoActive == true

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (peerUid != null && peerVideo) {
            // Keyed by session: a new match never reuses the previous peer's surface or frame.
            key(call.sessionId, peerUid) {
                AndroidView(factory = { ctx -> SurfaceView(ctx).also { vm.bindRemote(it, peerUid) } }, modifier = Modifier.fillMaxSize())
                DisposableEffect(Unit) { onDispose { vm.unbindRemote(peerUid) } }
            }
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(call.peerAvatarPath, call.peerProfile?.displayName ?: call.peerAlias.orEmpty(), size = 96.dp)
                Text(stringResource(R.string.call_peer_video_off), color = Color.White, modifier = Modifier.padding(top = 12.dp))
            }
        }
        if (rtc.videoEnabled && !rtc.cameraOff) {
            Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.statusBarsPadding().padding(16.dp).size(width = 110.dp, height = 160.dp).align(Alignment.TopEnd)) {
                key(call.sessionId) {
                    AndroidView(factory = { ctx -> SurfaceView(ctx).also { sv -> sv.setZOrderMediaOverlay(true); vm.bindLocal(sv) } }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp)) {
            Text(call.peerProfile?.displayName ?: call.peerAlias.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium)
            val left = ServerTime.remaining(call.endsAt)
            if (left != null) Text(formatDuration(left.seconds), color = Color.White)
            RtcProblems(active, vm)
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) {
            RequestBanners(call, vm) {}
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                CallControlButton(if (rtc.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    stringResource(if (rtc.micMuted) R.string.call_unmute else R.string.call_mute), vm::toggleMic, active = !rtc.micMuted)
                CallControlButton(if (rtc.cameraOff) Icons.Rounded.VideocamOff else Icons.Rounded.Videocam,
                    stringResource(if (rtc.cameraOff) R.string.call_camera_off else R.string.call_camera_on), vm::toggleCamera, active = !rtc.cameraOff)
                CallControlButton(Icons.Rounded.Cameraswitch, stringResource(R.string.call_switch_camera), vm::switchCamera)
                CallControlButton(Icons.Rounded.Headset, stringResource(R.string.call_voice_request), { vm.requestMode(TalkMode.Voice) })
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                CallControlButton(Icons.Rounded.Flag, stringResource(R.string.call_report), { showReport = true })
                CallControlButton(Icons.Rounded.Block, stringResource(R.string.call_block), vm::block)
                if (call.isRandom) CallControlButton(Icons.Rounded.SkipNext, stringResource(R.string.call_next), { vm.end("next") })
                CallControlButton(Icons.Rounded.CallEnd, stringResource(R.string.call_end), { vm.end() },
                    containerColor = StTheme.extra.endCall, contentColor = StTheme.extra.onEndCall)
            }
        }
    }
    if (showReport) {
        ReportDialog(
            onSubmit = { reason, details ->
                vm.report(reason, details, alsoBlock)
                showReport = false
            },
            onDismiss = { showReport = false },
            alsoBlockLabel = stringResource(R.string.call_also_block),
            alsoBlock = alsoBlock,
            onAlsoBlockChange = { alsoBlock = it },
        )
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun TextCall(active: ActiveCall, vm: CallViewModel, interestLabel: @Composable (String) -> String) {
    val call = active.call
    var draft by rememberSaveable { mutableStateOf("") }
    var showReport by rememberSaveable { mutableStateOf(false) }
    var alsoBlock by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.requestMode(TalkMode.Voice)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(active.messages.size) {
        if (active.messages.isNotEmpty()) listState.animateScrollToItem(active.messages.size - 1)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(call.peerAlias.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleLarge)
                val left = ServerTime.remaining(call.endsAt)
                Text(
                    if (call.peerTyping == true) stringResource(R.string.call_typing) else left?.let { formatDuration(it.seconds) }.orEmpty(),
                    color = Color.White, style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = { showReport = true }) { Icon(Icons.Rounded.Flag, stringResource(R.string.call_report), tint = Color.White) }
            IconButton(onClick = vm::block) { Icon(Icons.Rounded.Block, stringResource(R.string.call_block), tint = Color.White) }
            IconButton(onClick = { vm.end() }) { Icon(Icons.Rounded.CallEnd, stringResource(R.string.call_end), tint = Color.White) }
        }
        Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StSecondaryButton(
                        stringResource(if (call.myUpgradeRequest == TalkMode.Voice) R.string.call_extend_requested else R.string.call_voice_request),
                        {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                vm.requestMode(TalkMode.Voice)
                            } else {
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = call.myUpgradeRequest != TalkMode.Voice,
                        modifier = Modifier.weight(1f),
                    )
                    StSecondaryButton(stringResource(R.string.call_add_friend), vm::addFriend, modifier = Modifier.weight(1f))
                }
                if (call.peerUpgradeRequest == TalkMode.Voice) {
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        BannerWithAction(stringResource(R.string.call_voice_peer, call.peerAlias.orEmpty()), stringResource(R.string.call_voice_accept)) {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
                if (call.commonInterests.isNotEmpty()) {
                    Text(
                        call.commonInterests.map { interestLabel(it) }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState) {
                    items(active.messages, key = { it.id }) { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (m.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (m.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ) { Text(m.body, Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it.take(1000)
                            vm.typing()
                        },
                        placeholder = { Text(stringResource(R.string.call_message_hint)) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        maxLines = 4,
                    )
                    IconButton(onClick = {
                        vm.sendMessage(draft)
                        draft = ""
                    }, enabled = draft.isNotBlank() && call.isActive) {
                        Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.call_send), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    if (showReport) {
        ReportDialog(
            onSubmit = { reason, details ->
                vm.report(reason, details, alsoBlock)
                showReport = false
            },
            onDismiss = { showReport = false },
            alsoBlockLabel = stringResource(R.string.call_also_block),
            alsoBlock = alsoBlock,
            onAlsoBlockChange = { alsoBlock = it },
        )
    }
}

// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GiftSheet(vm: CallViewModel, onDismiss: () -> Unit) {
    val extras by vm.extras.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.call_gifts_title), style = MaterialTheme.typography.titleLarge)
            extras.balance?.let { Text(stringResource(R.string.call_gift_balance, it.toInt()), color = StTheme.extra.textSecondary) }
            Spacer(Modifier.height(12.dp))
            val gifts = extras.gifts
            when {
                !extras.giftsEnabled -> Text(stringResource(R.string.call_gifts_disabled))
                gifts == null -> Text("…")
                gifts.isEmpty() -> Text(stringResource(R.string.call_gifts_empty))
                else -> gifts.forEach { g ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CardGiftcard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.nameTr, style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.call_gift_price, g.priceCoins), style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
                        }
                        StPrimaryButton(stringResource(R.string.call_send), { vm.sendGift(g.code) }, enabled = (extras.balance ?: 0) >= g.priceCoins)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun PostCall(call: CallState, extras: CallExtras, vm: CallViewModel, onClose: () -> Unit, onNewCall: () -> Unit) {
    var showReport by rememberSaveable { mutableStateOf(false) }
    val reason = when (call.endReason) {
        "time_up" -> R.string.call_ended_time_up
        "missed" -> R.string.call_ended_missed
        "declined" -> R.string.call_ended_declined
        "connect_failed", "rtc_failed", "peer_lost" -> R.string.call_ended_connect_failed
        "moderation" -> R.string.call_ended_moderation
        else -> R.string.call_ended_peer
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Avatar(call.peerAvatarPath, call.peerProfile?.displayName ?: call.peerAlias.orEmpty(), size = 88.dp)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.call_ended_title), style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(stringResource(reason), style = MaterialTheme.typography.bodyLarge, color = Color.White, textAlign = TextAlign.Center)
        }
        Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val feedback = extras.feedback
                val canFeedback = call.isRandom && call.startedAt != null
                when {
                    feedback?.mutual == true -> {
                        Text(stringResource(R.string.call_mutual), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        val peerId = feedback.peerId
                        if (peerId != null) {
                            Spacer(Modifier.height(12.dp))
                            StPrimaryButton(stringResource(R.string.call_open_chat), { vm.openChatWith(peerId) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    feedback != null || (canFeedback && call.feedbackGiven) -> Text(stringResource(R.string.call_feedback_saved), textAlign = TextAlign.Center)
                    canFeedback -> {
                        Text(stringResource(R.string.call_again_question), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.call_again_note), style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StSecondaryButton(stringResource(R.string.call_again_no), { vm.feedback(false) }, modifier = Modifier.weight(1f), enabled = !extras.feedbackSending)
                            StPrimaryButton(stringResource(R.string.call_again_yes), { vm.feedback(true) }, modifier = Modifier.weight(1f), loading = extras.feedbackSending)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (call.isRandom) StPrimaryButton(stringResource(R.string.call_new), onNewCall, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                StSecondaryButton(stringResource(R.string.call_close), onClose, modifier = Modifier.fillMaxWidth())
                if (call.startedAt != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        StTextButton(stringResource(R.string.call_report), { showReport = true })
                        StTextButton(stringResource(R.string.call_block), vm::block)
                    }
                }
            }
        }
    }
    if (showReport) {
        ReportDialog(
            onSubmit = { r, details ->
                vm.report(r, details, false)
                showReport = false
            },
            onDismiss = { showReport = false },
            submitting = extras.reportSending,
        )
    }
}
