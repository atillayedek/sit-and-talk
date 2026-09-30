package com.sitandtalk.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sitandtalk.app.push.PushNotifier
import com.sitandtalk.app.ui.AppRoot
import com.sitandtalk.core.designsystem.LocalMediaResolver
import com.sitandtalk.core.designsystem.SitAndTalkTheme
import com.sitandtalk.core.rtc.CallService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var mediaResolver: AppMediaResolver

    private val appState: AppStateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { appState.onForeground() }
        }

        setContent {
            SitAndTalkTheme {
                CompositionLocalProvider(LocalMediaResolver provides mediaResolver) {
                    AppRoot(appState)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(CallService.EXTRA_OPEN_ACTIVE, false)) {
            appState.openActiveSession()
            return
        }
        val data = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        appState.handleUri(data, intent.getStringExtra(PushNotifier.EXTRA_NOTIFICATION_ID))
    }
}
