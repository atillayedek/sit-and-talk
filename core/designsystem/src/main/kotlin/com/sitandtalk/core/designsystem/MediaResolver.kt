package com.sitandtalk.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf

/** Resolves Storage object paths to loadable URLs. Private buckets use short-lived signed URLs. */
interface MediaResolver {
    fun publicUrl(bucket: String, path: String): String?
    suspend fun signedUrl(bucket: String, path: String): String?
}

object NoMediaResolver : MediaResolver {
    override fun publicUrl(bucket: String, path: String): String? = null
    override suspend fun signedUrl(bucket: String, path: String): String? = null
}

val LocalMediaResolver = staticCompositionLocalOf<MediaResolver> { NoMediaResolver }

object Buckets {
    const val AVATARS = "avatars"
    const val POST_MEDIA = "post-media"
    const val STORIES = "stories"
    const val CHAT_MEDIA = "chat-media"
    const val REPORT_EVIDENCE = "report-evidence"
}
