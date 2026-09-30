package com.sitandtalk.core.network

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** SupabaseProvider, Api and EncryptedSessionManager use constructor injection; AppConfig comes from :app. */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule
