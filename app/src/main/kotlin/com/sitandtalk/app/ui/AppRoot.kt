package com.sitandtalk.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sitandtalk.app.AppState
import com.sitandtalk.app.AppStateViewModel
import com.sitandtalk.app.R
import com.sitandtalk.core.designsystem.LoadingState
import com.sitandtalk.core.designsystem.SitAndTalkMark
import com.sitandtalk.core.designsystem.StPrimaryButton
import com.sitandtalk.core.designsystem.StSecondaryButton
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.errorMessage
import com.sitandtalk.core.designsystem.errorRes
import com.sitandtalk.feature.auth.AuthScreen
import com.sitandtalk.feature.auth.LegalDoc
import com.sitandtalk.feature.auth.NewPasswordScreen
import com.sitandtalk.feature.auth.OnboardingScreen
import com.sitandtalk.feature.moderation.SafetyScreen

@Composable
fun AppRoot(vm: AppStateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val authCode by vm.authMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val config = vm.config

    when (val s = state) {
        AppState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { LoadingState() }
        is AppState.ConfigMissing -> GateScreen(
            title = stringResource(R.string.gate_config_title),
            body = stringResource(R.string.gate_config_body) + "\n\n" + stringResource(R.string.gate_config_missing, s.keys.joinToString()),
        )
        is AppState.BootstrapFailed -> GateScreen(
            title = stringResource(R.string.gate_error_title),
            body = errorMessage(s.error),
            primary = stringResource(R.string.gate_retry) to vm::retry,
            secondary = stringResource(R.string.gate_sign_out) to vm::signOut,
        )
        AppState.UpdateRequired -> GateScreen(
            title = stringResource(R.string.gate_update_title),
            body = stringResource(R.string.gate_update_body),
            primary = stringResource(R.string.gate_update_action) to {
                openUrl(context, "https://play.google.com/store/apps/details?id=" + context.packageName)
            },
            secondary = stringResource(R.string.gate_retry) to vm::retry,
        )
        is AppState.Maintenance -> GateScreen(
            title = stringResource(R.string.gate_maintenance_title),
            body = s.message.ifBlank { stringResource(R.string.gate_maintenance_default) },
            primary = stringResource(R.string.gate_retry) to vm::retry,
        )
        AppState.SignedOut -> AuthScreen(
            onOpenLegal = { doc ->
                openUrl(context, when (doc) {
                    LegalDoc.Terms -> config.termsUrl
                    LegalDoc.Privacy -> config.privacyUrl
                    LegalDoc.Community -> config.communityUrl
                })
            },
            callbackMessage = authCode?.let { stringResource(errorRes(it)) },
            onCallbackMessageShown = vm::consumeAuthMessage,
        )
        AppState.PasswordRecovery -> NewPasswordScreen(onDone = vm::cancelRecovery)
        AppState.NeedsOnboarding -> OnboardingScreen(onCompleted = vm::onboardingCompleted, onSignOut = vm::signOut)
        is AppState.Suspended -> SuspendedGate(vm)
        is AppState.Ready -> {
            NotificationPermissionRequest()
            MainScaffold(vm)
        }
    }
}

@Composable
private fun SuspendedGate(vm: AppStateViewModel) {
    var reviewing by rememberSaveable { mutableStateOf(false) }
    if (reviewing) {
        SafetyScreen(onBack = { reviewing = false })
    } else {
        GateScreen(
            title = stringResource(R.string.gate_suspended_title),
            body = stringResource(R.string.gate_suspended_body),
            primary = stringResource(R.string.gate_suspended_review) to { reviewing = true },
            secondary = stringResource(R.string.gate_sign_out) to vm::signOut,
        )
    }
}

@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!asked && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun GateScreen(
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(StTheme.extra.talkGradient)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SitAndTalkMark(size = 72.dp)
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = StTheme.extra.onTalkGradient, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = StTheme.extra.onTalkGradient, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        primary?.let { (label, action) -> StPrimaryButton(label, action, Modifier.fillMaxWidth()) }
        secondary?.let { (label, action) ->
            Spacer(Modifier.height(12.dp))
            StSecondaryButton(label, action, Modifier.fillMaxWidth())
        }
    }
}

fun openUrl(context: Context, url: String) {
    if (url.isBlank()) {
        Toast.makeText(context, R.string.link_not_configured, Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.link_no_browser, Toast.LENGTH_SHORT).show()
    }
}
