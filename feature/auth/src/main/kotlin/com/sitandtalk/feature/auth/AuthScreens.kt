package com.sitandtalk.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sitandtalk.core.designsystem.EmptyState
import com.sitandtalk.core.designsystem.SitAndTalkWordmark
import com.sitandtalk.core.designsystem.StPrimaryButton
import com.sitandtalk.core.designsystem.StSecondaryButton
import com.sitandtalk.core.designsystem.StTextButton
import com.sitandtalk.core.designsystem.StTextField
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.errorMessage

enum class LegalDoc { Terms, Privacy, Community }

@Composable
fun AuthScreen(
    onOpenLegal: (LegalDoc) -> Unit,
    callbackMessage: String?,
    onCallbackMessageShown: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showForgot by rememberSaveable { mutableStateOf(false) }
    val errorText = state.error?.let { errorMessage(it) }
    val resentText = stringResource(R.string.auth_verify_resent)

    LaunchedEffect(errorText, state.info) {
        val msg = errorText ?: if (state.info == "resent") resentText else null
        if (msg != null) {
            snackbar.showSnackbar(msg)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(callbackMessage) {
        if (callbackMessage != null) {
            snackbar.showSnackbar(callbackMessage)
            onCallbackMessageShown()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            AuthHeader()
            when {
                state.pendingVerificationEmail != null -> VerifyEmailContent(
                    email = state.pendingVerificationEmail.orEmpty(),
                    cooldown = state.resendCooldown,
                    submitting = state.submitting,
                    onResend = viewModel::resendVerification,
                    onBack = viewModel::backToSignIn,
                )
                showForgot -> ForgotContent(
                    email = state.email,
                    emailError = state.emailError,
                    sent = state.forgotSent,
                    submitting = state.submitting,
                    onEmail = viewModel::setEmail,
                    onSend = viewModel::sendReset,
                    onBack = {
                        showForgot = false
                        viewModel.resetForgot()
                    },
                )
                else -> CredentialsContent(state, viewModel, onOpenLegal, onForgot = { showForgot = true })
            }
        }
    }
}

@Composable
private fun AuthHeader() {
    Box(
        Modifier
            .fillMaxWidth()
            .background(StTheme.extra.talkGradient)
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Column {
            SitAndTalkWordmark()
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.auth_welcome_title), style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.auth_welcome_subtitle), style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }
    }
}

@Composable
private fun CredentialsContent(state: AuthUiState, vm: AuthViewModel, onOpenLegal: (LegalDoc) -> Unit, onForgot: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp).navigationBarsPadding()) {
        SecondaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = Color.Transparent) {
            Tab(selected = state.tab == AuthTab.SignIn, onClick = { vm.setTab(AuthTab.SignIn) }, text = { Text(stringResource(R.string.auth_tab_sign_in)) })
            Tab(selected = state.tab == AuthTab.SignUp, onClick = { vm.setTab(AuthTab.SignUp) }, text = { Text(stringResource(R.string.auth_tab_sign_up)) })
        }
        Spacer(Modifier.height(16.dp))
        StTextField(
            value = state.email,
            onValueChange = vm::setEmail,
            label = stringResource(R.string.auth_email),
            error = if (state.emailError) stringResource(R.string.auth_invalid_email) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            maxLength = 254,
        )
        Spacer(Modifier.height(8.dp))
        StTextField(
            value = state.password,
            onValueChange = vm::setPassword,
            label = stringResource(R.string.auth_password),
            isPassword = true,
            error = if (state.passwordError) stringResource(R.string.auth_password_short) else null,
            supporting = if (state.tab == AuthTab.SignUp) stringResource(R.string.auth_password_hint) else null,
            keyboardOptions = KeyboardOptions(imeAction = if (state.tab == AuthTab.SignUp) ImeAction.Next else ImeAction.Done),
            maxLength = 72,
        )
        if (state.tab == AuthTab.SignUp) {
            Spacer(Modifier.height(8.dp))
            StTextField(
                value = state.passwordRepeat,
                onValueChange = vm::setPasswordRepeat,
                label = stringResource(R.string.auth_password_repeat),
                isPassword = true,
                error = if (state.repeatError) stringResource(R.string.auth_password_mismatch) else null,
                maxLength = 72,
            )
            Spacer(Modifier.height(8.dp))
            CheckRow(stringResource(R.string.auth_accept_age), state.confirmedAge, vm::setConfirmedAge)
            CheckRow(stringResource(R.string.auth_accept_terms), state.acceptedTerms, vm::setAcceptedTerms)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StTextButton(stringResource(R.string.auth_read_terms), { onOpenLegal(LegalDoc.Terms) })
                StTextButton(stringResource(R.string.auth_read_privacy), { onOpenLegal(LegalDoc.Privacy) })
            }
            StTextButton(stringResource(R.string.auth_read_community), { onOpenLegal(LegalDoc.Community) })
        }
        Spacer(Modifier.height(16.dp))
        StPrimaryButton(
            text = stringResource(if (state.tab == AuthTab.SignIn) R.string.auth_sign_in else R.string.auth_sign_up),
            onClick = vm::submit,
            loading = state.submitting,
            enabled = state.tab == AuthTab.SignIn || (state.acceptedTerms && state.confirmedAge),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.tab == AuthTab.SignIn) {
            StTextButton(stringResource(R.string.auth_forgot), onForgot)
        }
        if (state.googleAvailable) {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f))
                Text(stringResource(R.string.auth_or), Modifier.padding(horizontal = 12.dp), color = StTheme.extra.textSecondary)
                HorizontalDivider(Modifier.weight(1f))
            }
            StSecondaryButton(
                text = stringResource(R.string.auth_google),
                onClick = { vm.googleSignIn(context) },
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VerifyEmailContent(email: String, cooldown: Int, submitting: Boolean, onResend: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.heightIn(min = 280.dp)) {
            EmptyState(
                title = stringResource(R.string.auth_verify_title),
                message = stringResource(R.string.auth_verify_body, email),
                icon = Icons.Rounded.MarkEmailRead,
            )
        }
        StPrimaryButton(
            text = if (cooldown > 0) stringResource(R.string.auth_verify_resend_wait, cooldown) else stringResource(R.string.auth_verify_resend),
            onClick = onResend,
            enabled = cooldown == 0,
            loading = submitting,
            modifier = Modifier.fillMaxWidth(),
        )
        StTextButton(stringResource(R.string.auth_verify_back), onBack)
    }
}

@Composable
private fun ForgotContent(
    email: String,
    emailError: Boolean,
    sent: Boolean,
    submitting: Boolean,
    onEmail: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.padding(20.dp)) {
        Text(stringResource(R.string.auth_forgot_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (sent) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(stringResource(R.string.auth_forgot_sent), Modifier.padding(16.dp))
            }
        } else {
            Text(stringResource(R.string.auth_forgot_body), color = StTheme.extra.textSecondary)
            Spacer(Modifier.height(16.dp))
            StTextField(
                value = email,
                onValueChange = onEmail,
                label = stringResource(R.string.auth_email),
                error = if (emailError) stringResource(R.string.auth_invalid_email) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            Spacer(Modifier.height(16.dp))
            StPrimaryButton(stringResource(R.string.auth_forgot_send), onSend, loading = submitting, modifier = Modifier.fillMaxWidth())
        }
        StTextButton(stringResource(R.string.auth_verify_back), onBack)
    }
}

/** Shown after a recovery link was opened: sets a new password through Supabase Auth. */
@Composable
fun NewPasswordScreen(onDone: () -> Unit, viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.let { errorMessage(it) }
    val done = stringResource(R.string.auth_new_password_done)
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(state.passwordUpdated) {
        if (state.passwordUpdated) {
            snackbar.showSnackbar(done)
            onDone()
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            AuthHeader()
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.auth_new_password_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                StTextField(
                    state.password, viewModel::setPassword, stringResource(R.string.auth_new_password), isPassword = true,
                    error = if (state.passwordError) stringResource(R.string.auth_password_short) else null,
                    supporting = stringResource(R.string.auth_password_hint), maxLength = 72,
                )
                Spacer(Modifier.height(8.dp))
                StTextField(
                    state.passwordRepeat, viewModel::setPasswordRepeat, stringResource(R.string.auth_password_repeat), isPassword = true,
                    error = if (state.repeatError) stringResource(R.string.auth_password_mismatch) else null, maxLength = 72,
                )
                Spacer(Modifier.height(16.dp))
                StPrimaryButton(stringResource(R.string.auth_new_password_save), viewModel::updatePassword, loading = state.submitting, modifier = Modifier.fillMaxWidth())
                StTextButton(stringResource(R.string.auth_verify_back), {
                    viewModel.cancelRecovery()
                    onDone()
                })
            }
        }
    }
}
