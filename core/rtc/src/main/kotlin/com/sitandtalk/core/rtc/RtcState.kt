package com.sitandtalk.core.rtc

enum class RtcConnection { Idle, Connecting, Connected, Reconnecting, Failed, Disconnected }

enum class AudioRoute { Earpiece, Speaker, WiredHeadset, Bluetooth, Unknown }

data class RemoteUser(
    val uid: Int,
    val audioMuted: Boolean = false,
    val videoActive: Boolean = false,
)

data class RtcState(
    val connection: RtcConnection = RtcConnection.Idle,
    val channel: String? = null,
    val localUid: Int = 0,
    val canPublish: Boolean = false,
    val remoteUsers: Map<Int, RemoteUser> = emptyMap(),
    /** UIDs currently speaking according to Agora's volume indication (0 = this device). */
    val speaking: Set<Int> = emptySet(),
    val micMuted: Boolean = true,
    val speakerOn: Boolean = false,
    val audioRoute: AudioRoute = AudioRoute.Unknown,
    val videoEnabled: Boolean = false,
    val cameraOff: Boolean = false,
    val lastErrorCode: Int? = null,
    val disconnectReason: String? = null,
) {
    val isInChannel get() = connection == RtcConnection.Connected || connection == RtcConnection.Reconnecting
}

sealed interface RtcEvent {
    data object JoinedChannel : RtcEvent
    data object TokenWillExpire : RtcEvent
    data object TokenExpired : RtcEvent
    data class RemoteJoined(val uid: Int) : RtcEvent
    data class RemoteLeft(val uid: Int, val dropped: Boolean) : RtcEvent
    data class Kicked(val reason: String) : RtcEvent
    data class FatalError(val code: Int) : RtcEvent
}

/** What an ongoing RTC session represents, used by the foreground notification. */
data class RtcSessionInfo(val kind: Kind, val title: String, val video: Boolean) {
    enum class Kind { Call, Room }
}
