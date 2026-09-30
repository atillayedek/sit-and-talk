package com.sitandtalk.app

import com.sitandtalk.core.data.MediaRepository
import com.sitandtalk.core.designsystem.MediaResolver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppMediaResolver @Inject constructor(private val media: MediaRepository) : MediaResolver {
    override fun publicUrl(bucket: String, path: String): String? = media.publicUrl(bucket, path)
    override suspend fun signedUrl(bucket: String, path: String): String? = media.signedUrl(bucket, path)
}
