package com.sitandtalk.core.rtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import androidx.core.content.ContextCompat
import com.sitandtalk.core.model.RtcCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only owner of the Agora engine. Exactly one channel can be joined at a time; joining a new
 * channel requires leaving the previous one first. All media flags reflect real engine state.
 */
@Singleton
class RtcManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private var engine: RtcEngine? = null
    private var engineAppId: String? = null
    // True while the microphone track is captured and published (a speaker without mic permission joins without it).
    private var micPublishing = false

    private val _state = MutableStateFlow(RtcState())
    val state: StateFlow<RtcState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RtcEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<RtcEvent> = _events.asSharedFlow()

    private val handler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            _state.update { it.copy(connection = RtcConnection.Connected, channel = channel, localUid = uid, lastErrorCode = null) }
            _events.tryEmit(RtcEvent.JoinedChannel)
        }

        override fun onRejoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            _state.update { it.copy(connection = RtcConnection.Connected) }
        }

        override fun onLeaveChannel(stats: RtcStats?) {
            _state.update { RtcState(speakerOn = it.speakerOn) }
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            _state.update { it.copy(remoteUsers = it.remoteUsers + (uid to (it.remoteUsers[uid] ?: RemoteUser(uid)))) }
            _events.tryEmit(RtcEvent.RemoteJoined(uid))
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            _state.update { it.copy(remoteUsers = it.remoteUsers - uid, speaking = it.speaking - uid) }
            _events.tryEmit(RtcEvent.RemoteLeft(uid, dropped = reason == Constants.USER_OFFLINE_DROPPED))
        }

        override fun onUserMuteAudio(uid: Int, muted: Boolean) {
            _state.update { s ->
                val user = (s.remoteUsers[uid] ?: RemoteUser(uid)).copy(audioMuted = muted)
                s.copy(remoteUsers = s.remoteUsers + (uid to user))
            }
        }

        override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
            val active = state == Constants.REMOTE_VIDEO_STATE_DECODING || state == Constants.REMOTE_VIDEO_STATE_STARTING
            _state.update { s ->
                val user = (s.remoteUsers[uid] ?: RemoteUser(uid)).copy(videoActive = active)
                s.copy(remoteUsers = s.remoteUsers + (uid to user))
            }
        }

        override fun onAudioVolumeIndication(speakers: Array<out AudioVolumeInfo>?, totalVolume: Int) {
            // Speaking indicators come from measured volume + voice activity, never from animation.
            val active = speakers.orEmpty().filter { it.volume > 12 && (it.uid != 0 || it.vad == 1) }.map { it.uid }.toSet()
            _state.update { it.copy(speaking = if (it.micMuted) active - 0 else active) }
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            val mapped = when (state) {
                Constants.CONNECTION_STATE_CONNECTING -> RtcConnection.Connecting
                Constants.CONNECTION_STATE_CONNECTED -> RtcConnection.Connected
                Constants.CONNECTION_STATE_RECONNECTING -> RtcConnection.Reconnecting
                Constants.CONNECTION_STATE_FAILED -> RtcConnection.Failed
                else -> RtcConnection.Disconnected
            }
            val reasonCode = when (reason) {
                Constants.CONNECTION_CHANGED_BANNED_BY_SERVER -> "banned"
                Constants.CONNECTION_CHANGED_TOKEN_EXPIRED -> "token_expired"
                Constants.CONNECTION_CHANGED_INVALID_TOKEN -> "invalid_token"
                Constants.CONNECTION_CHANGED_REJECTED_BY_SERVER -> "rejected"
                Constants.CONNECTION_CHANGED_INVALID_APP_ID -> "invalid_app_id"
                else -> null
            }
            _state.update { it.copy(connection = mapped, disconnectReason = reasonCode ?: it.disconnectReason) }
            when (reasonCode) {
                "banned", "rejected" -> _events.tryEmit(RtcEvent.Kicked(reasonCode))
                "token_expired" -> _events.tryEmit(RtcEvent.TokenExpired)
            }
        }

        override fun onTokenPrivilegeWillExpire(token: String?) {
            _events.tryEmit(RtcEvent.TokenWillExpire)
        }

        override fun onRequestToken() {
            _events.tryEmit(RtcEvent.TokenExpired)
        }

        override fun onAudioRouteChanged(routing: Int) {
            val route = when (routing) {
                Constants.AUDIO_ROUTE_EARPIECE -> AudioRoute.Earpiece
                Constants.AUDIO_ROUTE_SPEAKERPHONE, Constants.AUDIO_ROUTE_LOUDSPEAKER -> AudioRoute.Speaker
                Constants.AUDIO_ROUTE_HEADSET, Constants.AUDIO_ROUTE_HEADSETNOMIC -> AudioRoute.WiredHeadset
                Constants.AUDIO_ROUTE_BLUETOOTH_DEVICE_HFP, Constants.AUDIO_ROUTE_BLUETOOTH_DEVICE_A2DP -> AudioRoute.Bluetooth
                else -> AudioRoute.Unknown
            }
            _state.update { it.copy(audioRoute = route, speakerOn = route == AudioRoute.Speaker) }
        }

        override fun onError(err: Int) {
            _state.update { it.copy(lastErrorCode = err) }
            if (err == Constants.ERR_INVALID_TOKEN || err == Constants.ERR_TOKEN_EXPIRED) {
                _events.tryEmit(RtcEvent.TokenExpired)
            }
        }
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    private fun engineFor(appId: String): RtcEngine {
        engine?.let { if (engineAppId == appId) return it }
        engine?.let { RtcEngine.destroy() }
        val config = RtcEngineConfig().apply {
            mContext = context
            mAppId = appId
            mEventHandler = handler
            mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            mAudioScenario = Constants.AUDIO_SCENARIO_CHATROOM
        }
        val created = RtcEngine.create(config)
        created.setAudioProfile(Constants.AUDIO_PROFILE_SPEECH_STANDARD)
        created.enableAudioVolumeIndication(300, 3, true)
        created.setDefaultAudioRoutetoSpeakerphone(false)
        engine = created
        engineAppId = appId
        return created
    }

    /**
     * Joins the channel described by server-issued [credentials]. The microphone starts muted unless
     * [startWithMicOn] is true; the camera is used only when [video] is true.
     * Returns the Agora result code (0 = accepted; join success arrives as [RtcEvent.JoinedChannel]).
     */
    @Synchronized
    fun join(credentials: RtcCredentials, video: Boolean, startWithMicOn: Boolean): Int {
        val current = _state.value
        check(!current.isInChannel || current.channel == credentials.channelName) { "Already in another channel" }
        val e = engineFor(credentials.appId)
        val publish = credentials.canPublish
        // Without the permission the speaker still joins and hears the room; the mic is added once granted.
        val micAvailable = publish && hasMicPermission()
        val micOn = micAvailable && startWithMicOn
        if (video && publish) {
            e.enableVideo()
            e.setVideoEncoderConfiguration(
                VideoEncoderConfiguration(
                    VideoEncoderConfiguration.VD_640x360,
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                    VideoEncoderConfiguration.STANDARD_BITRATE,
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE,
                ),
            )
        } else {
            e.disableVideo()
        }
        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            clientRoleType = if (publish) Constants.CLIENT_ROLE_BROADCASTER else Constants.CLIENT_ROLE_AUDIENCE
            publishMicrophoneTrack = micAvailable
            publishCameraTrack = video && publish
            autoSubscribeAudio = true
            autoSubscribeVideo = video
        }
        e.enableLocalAudio(micAvailable)
        e.muteLocalAudioStream(!micOn)
        micPublishing = micAvailable
        _state.value = RtcState(
            connection = RtcConnection.Connecting,
            channel = credentials.channelName,
            canPublish = publish,
            micMuted = !micOn,
            videoEnabled = video && publish,
            speakerOn = current.speakerOn,
        )
        val result = e.joinChannel(credentials.token, credentials.channelName, credentials.uid, options)
        if (result != 0) {
            _state.update { it.copy(connection = RtcConnection.Failed, lastErrorCode = result) }
        }
        return result
    }

    @Synchronized
    fun renewToken(token: String) {
        engine?.renewToken(token)
    }

    /** Switches between speaker (publisher) and listener (audience) after the server changed the role. */
    @Synchronized
    fun updateRole(credentials: RtcCredentials) {
        val e = engine ?: return
        e.renewToken(credentials.token)
        val publish = credentials.canPublish
        val micAvailable = publish && hasMicPermission()
        e.enableLocalAudio(micAvailable)
        e.updateChannelMediaOptions(ChannelMediaOptions().apply {
            clientRoleType = if (publish) Constants.CLIENT_ROLE_BROADCASTER else Constants.CLIENT_ROLE_AUDIENCE
            publishMicrophoneTrack = micAvailable
        })
        e.muteLocalAudioStream(true)
        micPublishing = micAvailable
        _state.update { it.copy(canPublish = publish, micMuted = true) }
    }

    @Synchronized
    fun setMicMuted(muted: Boolean) {
        val e = engine ?: return
        if (!muted && (!_state.value.canPublish || !hasMicPermission())) return
        if (!muted && !micPublishing) {
            // Permission was granted after joining: start capturing and publishing the microphone now.
            e.enableLocalAudio(true)
            e.updateChannelMediaOptions(ChannelMediaOptions().apply { publishMicrophoneTrack = true })
            micPublishing = true
        }
        e.muteLocalAudioStream(muted)
        _state.update { it.copy(micMuted = muted, speaking = if (muted) it.speaking - 0 else it.speaking) }
    }

    @Synchronized
    fun setSpeakerphone(on: Boolean) {
        engine?.setEnableSpeakerphone(on)
        _state.update { it.copy(speakerOn = on) }
    }

    /** Turns this device's camera track on or off (both sides must have agreed to video on the server). */
    @Synchronized
    fun setVideo(enabled: Boolean) {
        val e = engine ?: return
        if (enabled) {
            if (!hasCameraPermission() || !_state.value.canPublish) return
            e.enableVideo()
            e.enableLocalVideo(true)
            e.updateChannelMediaOptions(ChannelMediaOptions().apply {
                publishCameraTrack = true
                autoSubscribeVideo = true
            })
        } else {
            e.updateChannelMediaOptions(ChannelMediaOptions().apply { publishCameraTrack = false })
            e.enableLocalVideo(false)
            e.stopPreview()
        }
        _state.update { it.copy(videoEnabled = enabled, cameraOff = !enabled) }
    }

    @Synchronized
    fun setCameraOff(off: Boolean) {
        val e = engine ?: return
        e.muteLocalVideoStream(off)
        e.enableLocalVideo(!off)
        _state.update { it.copy(cameraOff = off) }
    }

    @Synchronized
    fun switchCamera() {
        engine?.switchCamera()
    }

    /** Pre-call camera preview (no channel joined yet). */
    @Synchronized
    fun startPreview(appId: String, view: View) {
        if (!hasCameraPermission()) return
        val e = engineFor(appId)
        e.enableVideo()
        e.setupLocalVideo(VideoCanvas(view, VideoCanvas.RENDER_MODE_HIDDEN, 0))
        e.startPreview()
    }

    @Synchronized
    fun stopPreview() {
        engine?.stopPreview()
    }

    @Synchronized
    fun bindLocalVideo(view: View) {
        val e = engine ?: return
        e.setupLocalVideo(VideoCanvas(view, VideoCanvas.RENDER_MODE_HIDDEN, 0))
        e.startPreview()
    }

    @Synchronized
    fun bindRemoteVideo(view: View, uid: Int) {
        engine?.setupRemoteVideo(VideoCanvas(view, VideoCanvas.RENDER_MODE_HIDDEN, uid))
    }

    /** Detaches a remote renderer so no stale frame of a previous peer can be shown again. */
    @Synchronized
    fun unbindRemoteVideo(uid: Int) {
        engine?.setupRemoteVideo(VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, uid))
    }

    @Synchronized
    fun leave() {
        val e = engine ?: return
        e.stopPreview()
        e.setupLocalVideo(VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, 0))
        _state.value.remoteUsers.keys.forEach { uid -> e.setupRemoteVideo(VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, uid)) }
        e.leaveChannel()
        e.disableVideo()
        micPublishing = false
        _state.value = RtcState()
    }

    /** Releases native resources (e.g. on sign-out). */
    @Synchronized
    fun release() {
        leave()
        if (engine != null) {
            RtcEngine.destroy()
            engine = null
            engineAppId = null
        }
    }
}
