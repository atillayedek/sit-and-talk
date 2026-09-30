package com.sitandtalk.feature.auth

import android.content.Context
import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitandtalk.core.data.AuthRepository
import com.sitandtalk.core.data.BootstrapRepository
import com.sitandtalk.core.data.SignUpResult
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.network.toAppException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthTab { SignIn, SignUp }

data class AuthUiState(
    val tab: AuthTab = AuthTab.SignIn,
    val email: String = "",
    val password: String = "",
    val passwordRepeat: String = "",
    val acceptedTerms: Boolean = false,
    val confirmedAge: Boolean = false,
    val submitting: Boolean = false,
    val error: AppException? = null,
    val emailError: Boolean = false,
    val passwordError: Boolean = false,
    val repeatError: Boolean = false,
    val pendingVerificationEmail: String? = null,
    val resendCooldown: Int = 0,
    val info: String? = null,
    val forgotSent: Boolean = false,
    val passwordUpdated: Boolean = false,
    val googleAvailable: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val bootstrap: BootstrapRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()
    private var cooldownJob: Job? = null

    init {
        viewModelScope.launch {
            val flagOn = runCatching { bootstrap.current.value ?: bootstrap.refresh() }.getOrNull()?.flag("google_sign_in") == true
            _state.update { it.copy(googleAvailable = flagOn && auth.isGoogleSignInAvailable) }
        }
    }

    fun setTab(tab: AuthTab) = _state.update { it.copy(tab = tab, error = null) }
    fun setEmail(v: String) = _state.update { it.copy(email = v, emailError = false, error = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, passwordError = false, error = null) }
    fun setPasswordRepeat(v: String) = _state.update { it.copy(passwordRepeat = v, repeatError = false) }
    fun setAcceptedTerms(v: Boolean) = _state.update { it.copy(acceptedTerms = v) }
    fun setConfirmedAge(v: Boolean) = _state.update { it.copy(confirmedAge = v) }
    fun consumeError() = _state.update { it.copy(error = null, info = null) }
    fun showInfo(text: String) = _state.update { it.copy(info = text) }

    private fun validEmail(email: String) = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    private fun strongPassword(pw: String) = pw.length >= 8 && pw.any { it.isLetter() } && pw.any { it.isDigit() }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        val emailOk = validEmail(s.email)
        when (s.tab) {
            AuthTab.SignIn -> {
                if (!emailOk || s.password.isEmpty()) {
                    _state.update { it.copy(emailError = !emailOk, passwordError = s.password.isEmpty()) }
                    return
                }
                launchAction {
                    auth.signIn(s.email, s.password)
                    _state.update { it.copy(password = "") }
                }
            }
            AuthTab.SignUp -> {
                val pwOk = strongPassword(s.password)
                val repeatOk = s.password == s.passwordRepeat
                if (!emailOk || !pwOk || !repeatOk || !s.acceptedTerms || !s.confirmedAge) {
                    _state.update { it.copy(emailError = !emailOk, passwordError = !pwOk, repeatError = !repeatOk) }
                    return
                }
                launchAction {
                    when (val result = auth.signUp(s.email, s.password)) {
                        SignUpResult.SignedIn -> Unit
                        is SignUpResult.ConfirmationSent -> {
                            _state.update { it.copy(pendingVerificationEmail = result.email, password = "", passwordRepeat = "") }
                            startCooldown()
                        }
                    }
                }
            }
        }
    }

    fun googleSignIn(activityContext: Context) = launchAction { auth.signInWithGoogle(activityContext) }

    fun resendVerification() {
        val email = _state.value.pendingVerificationEmail ?: return
        if (_state.value.resendCooldown > 0) return
        launchAction {
            auth.resendConfirmation(email)
            startCooldown()
            _state.update { it.copy(info = "resent") }
        }
    }

    fun backToSignIn() = _state.update { it.copy(pendingVerificationEmail = null, tab = AuthTab.SignIn) }

    fun sendReset() {
        val email = _state.value.email
        if (!validEmail(email)) {
            _state.update { it.copy(emailError = true) }
            return
        }
        launchAction {
            auth.sendPasswordReset(email)
            _state.update { it.copy(forgotSent = true) }
        }
    }

    fun resetForgot() = _state.update { it.copy(forgotSent = false) }

    fun updatePassword() {
        val s = _state.value
        if (!strongPassword(s.password)) {
            _state.update { it.copy(passwordError = true) }
            return
        }
        if (s.password != s.passwordRepeat) {
            _state.update { it.copy(repeatError = true) }
            return
        }
        launchAction {
            auth.updatePassword(s.password)
            _state.update { it.copy(passwordUpdated = true, password = "", passwordRepeat = "") }
        }
    }

    fun cancelRecovery() = auth.cancelPasswordRecovery()

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (left in 60 downTo 0) {
                _state.update { it.copy(resendCooldown = left) }
                delay(1_000)
            }
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            try {
                block()
            } catch (e: Exception) {
                val mapped = e.toAppException()
                if (mapped.code != "cancelled") _state.update { it.copy(error = mapped) }
                if (mapped.code == AppException.RATE_LIMITED && mapped.retryAfterSeconds != null) {
                    _state.update { it.copy(resendCooldown = mapped.retryAfterSeconds ?: 60) }
                }
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }
}
