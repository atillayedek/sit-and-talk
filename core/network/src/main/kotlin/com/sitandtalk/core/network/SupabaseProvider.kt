package com.sitandtalk.core.network

import com.sitandtalk.core.model.AppConfig
import com.sitandtalk.core.model.AppException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Lazily builds the single Supabase client. When the build has no backend configuration the client is
 * never created and callers get [AppException.NOT_CONFIGURED] instead of a crash or a fake backend.
 */
@Singleton
class SupabaseProvider @Inject constructor(
    private val config: AppConfig,
    private val sessionManager: EncryptedSessionManager,
) {
    val isConfigured: Boolean get() = config.isBackendConfigured

    val client: SupabaseClient by lazy {
        if (!config.isBackendConfigured) throw AppException(AppException.NOT_CONFIGURED)
        val redirect = config.authRedirectUrl.ifBlank { DEFAULT_REDIRECT }
        createSupabaseClient(config.supabaseUrl, config.supabasePublishableKey) {
            defaultSerializer = KotlinXSerializer(AppJson)
            defaultLogLevel = if (config.isDebug) LogLevel.WARNING else LogLevel.NONE
            requestTimeout = 20.seconds
            httpEngine = OkHttp.create()
            install(Auth) {
                flowType = FlowType.PKCE
                scheme = redirect.substringBefore("://")
                host = redirect.substringAfter("://").substringBefore('/')
                sessionManager = this@SupabaseProvider.sessionManager
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                autoSaveToStorage = true
            }
            install(Postgrest)
            install(Realtime)
            install(Functions)
            install(Storage)
        }
    }

    val redirectBase: String get() = config.authRedirectUrl.ifBlank { DEFAULT_REDIRECT }

    companion object {
        const val DEFAULT_REDIRECT = "sitandtalk://auth-callback"
    }
}
