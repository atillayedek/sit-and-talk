package com.sitandtalk.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sitandtalk.core.data.ApplicationScope
import com.sitandtalk.core.data.AuthRepository
import com.sitandtalk.core.data.PushRegistrar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PushService : FirebaseMessagingService() {
    @Inject lateinit var registrar: PushRegistrar
    @Inject lateinit var auth: AuthRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onNewToken(token: String) {
        if (auth.currentUserId() == null) return
        scope.launch { registrar.register(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Pushes that arrive after sign-out are dropped; the device token is removed on sign-out too.
        if (auth.currentUserId() == null) return
        PushNotifier.show(this, message.data)
    }
}
