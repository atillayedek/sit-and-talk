package com.sitandtalk.core.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** Records AAC voice clips to the cache directory. One instance per screen (not a singleton). */
class AudioRecorder @Inject constructor(@param:ApplicationContext private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(maxDurationMs: Int): Boolean = try {
        val out = File.createTempFile("voice_", ".m4a", context.cacheDir)
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64_000)
        r.setAudioSamplingRate(44_100)
        r.setMaxDuration(maxDurationMs)
        r.setOutputFile(out.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = out
        startedAt = System.currentTimeMillis()
        true
    } catch (_: Exception) {
        release()
        false
    }

    /** Stops and returns the clip with its duration, or null when cancelled / too short. */
    fun stop(keep: Boolean, minDurationMs: Int = 800): Pair<File, Int>? {
        val r = recorder ?: return null
        val duration = (System.currentTimeMillis() - startedAt).toInt()
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        recorder = null
        val out = file
        file = null
        return if (keep && ok && out != null && duration >= minDurationMs) out to duration else {
            out?.delete()
            null
        }
    }

    fun release() {
        runCatching { recorder?.release() }
        recorder = null
        file?.delete()
        file = null
    }
}
