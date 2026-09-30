package com.sitandtalk.core.data

import android.content.Context
import android.net.Uri
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.sitandtalk.core.model.AppConfig
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.network.SafeLog
import com.sitandtalk.core.network.SupabaseProvider
import com.sitandtalk.core.network.apiCall
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthStatus {
    data object Loading : AuthStatus
    data object SignedOut : AuthStatus
    data class SignedIn(val userId: String, val email: String?, val emailConfirmed: Boolean) : AuthStatus
    /** Session could not be refreshed because of the network; the user stays signed in. */
    data class Offline(val userId: String?) : AuthStatus
}

sealed interface SignUpResult {
    data object SignedIn : SignUpResult
    /** Normal outcome with e-mail confirmation on: no session until the link is opened. */
    data class ConfirmationSent(val email: String) : SignUpResult
}

sealed interface AuthCallbackResult {
    data object SignedIn : AuthCallbackResult
    data object PasswordRecovery : AuthCallbackResult
    data class Failed(val code: String) : AuthCallbackResult
}

@Singleton
class AuthRepository @Inject constructor(
    private val provider: SupabaseProvider,
    private val config: AppConfig,
    private val sessionCleaner: SessionCleaner,
) {
    private var lastUserId: String? = null

    private val _passwordRecovery = MutableStateFlow(false)
    /** True after a recovery link was opened, until the user sets a new password. */
    val passwordRecovery: StateFlow<Boolean> = _passwordRecovery.asStateFlow()

    val status: Flow<AuthStatus>
        get() = if (!provider.isConfigured) {
            flowOf(AuthStatus.SignedOut)
        } else {
            provider.client.auth.sessionStatus.map { s ->
                when (s) {
                    is SessionStatus.Initializing -> AuthStatus.Loading
                    is SessionStatus.Authenticated -> {
                        val user = s.session.user
                        val id = user?.id ?: lastUserId
                        lastUserId = id
                        if (id == null) {
                            AuthStatus.Loading
                        } else {
                            AuthStatus.SignedIn(id, user?.email, user?.emailConfirmedAt != null)
                        }
                    }
                    is SessionStatus.NotAuthenticated -> AuthStatus.SignedOut
                    is SessionStatus.RefreshFailure -> AuthStatus.Offline(lastUserId)
                }
            }
        }

    fun currentUserId(): String? =
        if (!provider.isConfigured) null else provider.client.auth.currentUserOrNull()?.id ?: lastUserId

    fun requireUserId(): String = currentUserId() ?: throw AppException(AppException.UNAUTHORIZED)

    fun currentEmail(): String? = if (!provider.isConfigured) null else provider.client.auth.currentUserOrNull()?.email

    val isGoogleSignInAvailable: Boolean get() = config.isGoogleSignInConfigured

    suspend fun signIn(email: String, password: String) = apiCall {
        val previous = lastUserId
        provider.client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        val now = provider.client.auth.currentUserOrNull()?.id
        if (previous != null && now != null && previous != now) sessionCleaner.clearUserData()
        lastUserId = now
    }

    suspend fun signUp(email: String, password: String): SignUpResult = apiCall {
        val trimmed = email.trim()
        val user = provider.client.auth.signUpWith(Email, redirectUrl = "${provider.redirectBase}/signup") {
            this.email = trimmed
            this.password = password
        }
        // With confirmations enabled Supabase returns the user without a session.
        if (user == null && provider.client.auth.currentSessionOrNull() != null) {
            SignUpResult.SignedIn
        } else {
            SignUpResult.ConfirmationSent(trimmed)
        }
    }

    suspend fun resendConfirmation(email: String) = apiCall {
        provider.client.auth.resendEmail(
            io.github.jan.supabase.auth.OtpType.Email.SIGNUP,
            email.trim(),
            redirectUrl = "${provider.redirectBase}/signup",
        )
    }

    suspend fun sendPasswordReset(email: String) = apiCall {
        provider.client.auth.resetPasswordForEmail(email.trim(), redirectUrl = "${provider.redirectBase}/recovery")
    }

    suspend fun updatePassword(newPassword: String) = apiCall {
        provider.client.auth.updateUser { password = newPassword }
        _passwordRecovery.value = false
    }

    /** Fresh password sign-in right before sensitive actions (account deletion). */
    suspend fun reauthenticate(password: String) = apiCall {
        val email = provider.client.auth.currentUserOrNull()?.email ?: throw AppException(AppException.UNAUTHORIZED)
        provider.client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    /**
     * Handles sitandtalk://auth-callback/{signup|recovery|oauth}?code=... (PKCE). Tokens never travel in the
     * link: only a one-time code, useless without the verifier stored on this device.
     */
    suspend fun handleCallback(uri: Uri): AuthCallbackResult {
        val error = uri.getQueryParameter("error_code") ?: uri.getQueryParameter("error")
        if (error != null) {
            SafeLog.error("auth", "callback_error")
            return AuthCallbackResult.Failed(if (error.contains("expired")) "link_expired" else "auth_failed")
        }
        val code = uri.getQueryParameter("code") ?: return AuthCallbackResult.Failed("link_expired")
        val flow = uri.lastPathSegment
        return try {
            apiCall { provider.client.auth.exchangeCodeForSession(code) }
            if (flow == "recovery") {
                _passwordRecovery.value = true
                AuthCallbackResult.PasswordRecovery
            } else {
                AuthCallbackResult.SignedIn
            }
        } catch (e: AppException) {
            // A confirmation link opened on another device cannot finish PKCE here, but the address is
            // still confirmed on the server; the user can simply sign in.
            AuthCallbackResult.Failed(if (flow == "signup") "email_confirmed_sign_in" else e.code)
        }
    }

    fun cancelPasswordRecovery() {
        _passwordRecovery.value = false
    }

    /** Google sign-in through Credential Manager + Supabase ID token exchange (nonce protected). */
    suspend fun signInWithGoogle(activityContext: Context) {
        if (!config.isGoogleSignInConfigured) throw AppException(AppException.NOT_CONFIGURED)
        val rawNonce = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val hashedNonce = MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray()).joinToString("") { "%02x".format(it) }
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(config.googleWebClientId)
            .setFilterByAuthorizedAccounts(false)
            .setNonce(hashedNonce)
            .build()
        val credential = try {
            CredentialManager.create(activityContext)
                .getCredential(activityContext, GetCredentialRequest.Builder().addCredentialOption(option).build())
                .credential
        } catch (_: GetCredentialCancellationException) {
            throw AppException("cancelled")
        } catch (_: NoCredentialException) {
            throw AppException("no_google_account")
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        apiCall {
            provider.client.auth.signInWith(IDToken) {
                this.idToken = idToken
                this.provider = Google
                this.nonce = rawNonce
            }
        }
    }

    /** Local session is always cleared, even offline; server revocation is best effort. */
    suspend fun signOut() {
        if (!provider.isConfigured) return
        sessionCleaner.beforeSignOut()
        try {
            provider.client.auth.signOut()
        } catch (e: Exception) {
            SafeLog.error("auth", "remote_sign_out_failed", e)
            runCatching { provider.client.auth.clearSession() }
        }
        sessionCleaner.clearUserData()
        lastUserId = null
    }
}
