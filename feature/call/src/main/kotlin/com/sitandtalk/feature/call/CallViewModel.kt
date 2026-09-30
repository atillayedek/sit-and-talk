package com.sitandtalk.feature.call

import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitandtalk.core.data.ActiveCall
import com.sitandtalk.core.data.ActiveCallController
import com.sitandtalk.core.data.BootstrapRepository
import com.sitandtalk.core.data.CallRepository
import com.sitandtalk.core.data.ChatRepository
import com.sitandtalk.core.data.ModerationRepository
import com.sitandtalk.core.data.WalletRepository
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.FeedbackResult
import com.sitandtalk.core.model.GiftItem
import com.sitandtalk.core.model.ReportReason
import com.sitandtalk.core.model.ReportTarget
import com.sitandtalk.core.model.TalkMode
import com.sitandtalk.core.network.toAppException
import com.sitandtalk.core.rtc.RtcManager
import com.sitandtalk.core.rtc.RtcState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CallExtras(
    val feedback: FeedbackResult? = null,
    val feedbackSending: Boolean = false,
    val reportSending: Boolean = false,
    val message: String? = null,
    val error: AppException? = null,
    val gifts: List<GiftItem>? = null,
    val balance: Long? = null,
    val giftsEnabled: Boolean = false,
    val openChatId: String? = null,
)

@HiltViewModel
class CallViewModel @Inject constructor(
    private val controller: ActiveCallController,
    private val calls: CallRepository,
    private val moderation: ModerationRepository,
    private val wallet: WalletRepository,
    private val chat: ChatRepository,
    private val bootstrap: BootstrapRepository,
    private val rtc: RtcManager,
) : ViewModel() {

    val active: StateFlow<ActiveCall?> = controller.active
    val rtcState: StateFlow<RtcState> = controller.rtcState

    private val _extras = MutableStateFlow(CallExtras(giftsEnabled = bootstrap.flag("gifts")))
    val extras: StateFlow<CallExtras> = _extras.asStateFlow()

    private val sessionId get() = active.value?.call?.sessionId

    fun toggleMic() = controller.setMicMuted(!rtcState.value.micMuted)
    fun toggleSpeaker() = controller.setSpeaker(!rtcState.value.speakerOn)
    fun switchCamera() = controller.switchCamera()
    fun toggleCamera() = controller.setCameraOff(!rtcState.value.cameraOff)
    fun enableLocalVideo(enabled: Boolean) = controller.setLocalVideo(enabled)
    fun requestExtension() = controller.requestExtension()
    fun withdrawExtension() = controller.withdrawExtension()
    fun requestMode(mode: TalkMode) = controller.requestMode(mode)
    fun end(reason: String = "hangup") = controller.end(reason)
    fun answer(accept: Boolean) = controller.answer(accept)
    fun retryRtc() = controller.retryRtc()
    fun sendMessage(body: String) {
        if (body.isNotBlank()) controller.sendMessage(body.trim())
    }
    fun typing() = controller.typing()
    fun consumeControllerError() = controller.consumeError()

    fun bindLocal(view: View) = rtc.bindLocalVideo(view)
    fun bindRemote(view: View, uid: Int) = rtc.bindRemoteVideo(view, uid)
    fun unbindRemote(uid: Int) = rtc.unbindRemoteVideo(uid)

    fun addFriend() = launch {
        val id = sessionId ?: return@launch
        calls.friendRequestToPeer(id)
        _extras.update { it.copy(message = "friend_request_sent") }
    }

    fun block() = launch {
        val id = sessionId ?: return@launch
        calls.blockPeer(id)
        controller.end("block")
    }

    fun report(reason: ReportReason, details: String, alsoBlock: Boolean) = launch {
        val id = sessionId ?: return@launch
        _extras.update { it.copy(reportSending = true) }
        try {
            moderation.report(ReportTarget.Call, id, reason, details)
            if (alsoBlock) {
                calls.blockPeer(id)
                controller.end("report")
            }
            _extras.update { it.copy(message = "reported") }
        } finally {
            _extras.update { it.copy(reportSending = false) }
        }
    }

    fun feedback(wantsAgain: Boolean) = launch {
        val id = sessionId ?: return@launch
        _extras.update { it.copy(feedbackSending = true) }
        try {
            val result = calls.feedback(id, wantsAgain, null)
            _extras.update { it.copy(feedback = result) }
        } finally {
            _extras.update { it.copy(feedbackSending = false) }
        }
    }

    fun openChatWith(userId: String) = launch {
        val conversation = chat.openDirect(userId)
        _extras.update { it.copy(openChatId = conversation) }
    }

    fun loadGifts() = launch {
        val ent = wallet.refresh()
        val items = if (ent.giftsEnabled) wallet.gifts() else emptyList()
        _extras.update { it.copy(gifts = items, balance = ent.balance, giftsEnabled = ent.giftsEnabled) }
    }

    fun sendGift(code: String) = launch {
        val id = sessionId ?: return@launch
        val result = wallet.sendGift(code, "call", id)
        _extras.update { it.copy(balance = result.balance, message = "gift_sent") }
    }

    fun dismiss() {
        controller.dismiss()
        _extras.value = CallExtras(giftsEnabled = bootstrap.flag("gifts"))
    }

    fun consumeMessage() = _extras.update { it.copy(message = null, error = null, openChatId = null) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _extras.update { it.copy(error = e.toAppException()) }
            }
        }
    }
}
