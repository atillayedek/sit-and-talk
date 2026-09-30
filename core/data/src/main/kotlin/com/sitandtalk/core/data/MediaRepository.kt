package com.sitandtalk.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.sitandtalk.core.model.AppConfig
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.network.SupabaseProvider
import com.sitandtalk.core.network.apiCall
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.time.Duration.Companion.minutes

/**
 * Uploads media under unguessable names and resolves private files through short-lived signed URLs.
 * Images are decoded and re-encoded before upload, which drops EXIF metadata such as GPS location.
 */
@Singleton
class MediaRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val provider: SupabaseProvider,
    private val config: AppConfig,
) {
    private data class Signed(val url: String, val expiresAt: Long)
    private val signedCache = ConcurrentHashMap<String, Signed>()

    fun publicUrl(bucket: String, path: String): String? =
        if (!config.isBackendConfigured) null else "${config.supabaseUrl.trimEnd('/')}/storage/v1/object/public/$bucket/$path"

    suspend fun signedUrl(bucket: String, path: String): String? {
        val key = "$bucket/$path"
        signedCache[key]?.let { if (it.expiresAt > System.currentTimeMillis() + 60_000) return it.url }
        return try {
            val url = apiCall { provider.client.storage.from(bucket).createSignedUrl(path, 10.minutes) }
            signedCache[key] = Signed(url, System.currentTimeMillis() + 10 * 60_000)
            url
        } catch (_: AppException) {
            null
        }
    }

    fun clearCache() = signedCache.clear()

    /** Uploads an image picked by the user; returns the Storage path "<scope>/<random>.jpg". */
    suspend fun uploadImage(bucket: String, scope: String, uri: Uri, maxDimension: Int = 1600): String {
        val bytes = withContext(Dispatchers.IO) { reencodeJpeg(uri, maxDimension) }
        if (bytes.size > 7_500_000) throw AppException("invalid_media")
        val path = "$scope/${randomName()}.jpg"
        apiCall {
            provider.client.storage.from(bucket).upload(path, bytes) {
                contentType = ContentType.Image.JPEG
                upsert = false
            }
        }
        return path
    }

    /** Uploads a recorded AAC/M4A voice clip. */
    suspend fun uploadAudio(bucket: String, scope: String, file: java.io.File): String {
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        if (bytes.isEmpty()) throw AppException("upload_failed")
        if (bytes.size > 9_500_000) throw AppException("invalid_media")
        val path = "$scope/${randomName()}.m4a"
        apiCall {
            provider.client.storage.from(bucket).upload(path, bytes) {
                contentType = ContentType.parse("audio/mp4")
                upsert = false
            }
        }
        return path
    }

    suspend fun delete(bucket: String, path: String) = apiCall {
        provider.client.storage.from(bucket).delete(listOf(path))
    }

    private fun reencodeJpeg(uri: Uri, maxDimension: Int): ByteArray {
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val largest = max(info.size.width, info.size.height)
                if (largest > maxDimension) {
                    val scale = maxDimension.toFloat() / largest
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw AppException("invalid_media")
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun randomName(): String {
        val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            .replace('-', 'a').replace('_', 'b')
    }
}
