package com.sitandtalk.core.data

import android.content.Context
import android.net.Uri
import com.sitandtalk.core.network.Api
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: Api,
    private val auth: AuthRepository,
    private val cleaner: SessionCleaner,
) {
    /** Writes the user's own data export (JSON) to a document the user picked. */
    suspend fun exportTo(target: Uri) {
        val raw = api.rpcRaw("export_my_data")
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(target, "wt")?.use { it.write(raw.toByteArray()) }
                ?: throw com.sitandtalk.core.model.AppException("upload_failed")
        }
    }

    /** Re-authenticates with the password, then deletes the account on the server and clears the device. */
    suspend fun deleteAccount(password: String) {
        auth.reauthenticate(password)
        api.function<JsonObject>("delete-account") { }
        cleaner.beforeSignOut()
        runCatching { auth.signOut() }
        cleaner.clearUserData()
    }
}
