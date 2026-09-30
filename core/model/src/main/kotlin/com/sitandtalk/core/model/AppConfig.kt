package com.sitandtalk.core.model

/** Public client configuration injected at build time. Server secrets never live here. */
data class AppConfig(
    val supabaseUrl: String,
    val supabasePublishableKey: String,
    val agoraAppId: String,
    val authRedirectUrl: String,
    val appLinkHost: String,
    val googleWebClientId: String,
    val firebaseProjectId: String,
    val firebaseAppId: String,
    val firebaseApiKey: String,
    val firebaseSenderId: String,
    val supportUrl: String,
    val privacyUrl: String,
    val termsUrl: String,
    val communityUrl: String,
    val versionCode: Int,
    val versionName: String,
    val isDebug: Boolean,
) {
    val isBackendConfigured: Boolean
        get() = supabaseUrl.startsWith("https://") && supabasePublishableKey.isNotBlank()

    val isRtcConfigured: Boolean get() = agoraAppId.isNotBlank()

    val isPushConfigured: Boolean
        get() = firebaseProjectId.isNotBlank() && firebaseAppId.isNotBlank() &&
            firebaseApiKey.isNotBlank() && firebaseSenderId.isNotBlank()

    val isGoogleSignInConfigured: Boolean get() = googleWebClientId.isNotBlank()

    val missingKeys: List<String>
        get() = buildList {
            if (!supabaseUrl.startsWith("https://")) add("SUPABASE_URL")
            if (supabasePublishableKey.isBlank()) add("SUPABASE_PUBLISHABLE_KEY")
            if (agoraAppId.isBlank()) add("AGORA_APP_ID")
        }
}
