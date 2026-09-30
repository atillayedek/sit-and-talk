package com.sitandtalk.core.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.sitandtalk.core.model.AppConfig
import com.sitandtalk.core.network.Api
import com.sitandtalk.core.network.SafeLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.put
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers the FCM token of this device with the signed-in account. When Firebase is not configured
 * for this build nothing is registered and [isAvailable] is false — the app never claims pushes work.
 */
@Singleton
class PushRegistrar @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: Api,
    private val config: AppConfig,
) {
    val isAvailable: Boolean get() = config.isPushConfigured && FirebaseApp.getApps(context).isNotEmpty()

    suspend fun registerCurrentDevice() {
        if (!isAvailable) return
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        register(token)
    }

    suspend fun register(token: String) {
        if (!isAvailable) return
        try {
            api.rpcUnit("register_device") {
                put("p_token", token)
                put("p_version_code", config.versionCode)
                put("p_locale", Locale.getDefault().toLanguageTag())
            }
        } catch (e: Exception) {
            SafeLog.error("push", "register_failed", e)
        }
    }

    suspend fun unregisterCurrentDevice() {
        if (!isAvailable) return
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        runCatching { api.rpcUnit("unregister_device") { put("p_token", token) } }
        runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
    }
}
