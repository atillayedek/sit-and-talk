package com.sitandtalk.core.security

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/** Small encrypted key/value files in no-backup storage (excluded from cloud backup and device transfer). */
@Singleton
class EncryptedStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cipher: KeystoreCipher,
) {
    private fun file(name: String): AtomicFile {
        require(name.matches(Regex("[a-z0-9_]{1,40}"))) { "invalid store name" }
        return AtomicFile(File(context.noBackupFilesDir, "$name.bin"))
    }

    @Synchronized
    fun read(name: String): ByteArray? {
        val f = file(name)
        val bytes = try {
            f.readFully()
        } catch (_: FileNotFoundException) {
            return null
        }
        return try {
            cipher.decrypt(bytes)
        } catch (_: Exception) {
            // Key lost (e.g. device restore) or file corrupted: the data is unrecoverable, drop it.
            f.delete()
            null
        }
    }

    @Synchronized
    fun write(name: String, data: ByteArray) {
        val f = file(name)
        val out = f.startWrite()
        try {
            out.write(cipher.encrypt(data))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    @Synchronized
    fun delete(name: String) {
        file(name).delete()
    }
}
