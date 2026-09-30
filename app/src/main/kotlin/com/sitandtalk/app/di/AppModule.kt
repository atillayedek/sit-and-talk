package com.sitandtalk.app.di

import com.sitandtalk.app.BuildConfig
import com.sitandtalk.core.model.AppConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun appConfig(): AppConfig = AppConfig(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabasePublishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        agoraAppId = BuildConfig.AGORA_APP_ID,
        authRedirectUrl = BuildConfig.AUTH_REDIRECT_URL,
        appLinkHost = BuildConfig.APP_LINK_HOST,
        googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
        firebaseProjectId = BuildConfig.FIREBASE_PROJECT_ID,
        firebaseAppId = BuildConfig.FIREBASE_APP_ID,
        firebaseApiKey = BuildConfig.FIREBASE_API_KEY,
        firebaseSenderId = BuildConfig.FIREBASE_SENDER_ID,
        supportUrl = BuildConfig.SUPPORT_URL,
        privacyUrl = BuildConfig.PRIVACY_URL,
        termsUrl = BuildConfig.TERMS_URL,
        communityUrl = BuildConfig.COMMUNITY_URL,
        versionCode = BuildConfig.VERSION_CODE,
        versionName = BuildConfig.VERSION_NAME,
        isDebug = BuildConfig.DEBUG,
    )
}
