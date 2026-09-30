package com.sitandtalk.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.sitandtalk.core.database.SitAndTalkDatabase
import com.sitandtalk.core.rtc.RtcManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Removes everything tied to the signed-in account so the next account never sees it. */
@Singleton
class SessionCleaner @Inject constructor(
    private val database: SitAndTalkDatabase,
    private val preferences: DataStore<Preferences>,
    private val rtc: RtcManager,
    private val push: Provider<PushRegistrar>,
    private val media: Provider<MediaRepository>,
) {
    /** Runs while the session is still valid, so the server can drop this device's push token. */
    suspend fun beforeSignOut() {
        runCatching { push.get().unregisterCurrentDevice() }
        rtc.release()
    }

    suspend fun clearUserData() = withContext(Dispatchers.IO) {
        database.clearAllTables()
        preferences.updateData { it.toMutablePreferences().apply { clear() } }
        media.get().clearCache()
    }
}
