package com.sitandtalk.core.network

import com.sitandtalk.core.security.EncryptedStore
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the Supabase session encrypted with an Android Keystore key. supabase-kt owns refreshing;
 * this class only stores what it hands over, so there is exactly one refresh mechanism.
 */
@Singleton
class EncryptedSessionManager @Inject constructor(
    private val store: EncryptedStore,
) : SessionManager {

    override suspend fun saveSession(session: UserSession) = withContext(Dispatchers.IO) {
        store.write(FILE, AppJson.encodeToString(UserSession.serializer(), session).encodeToByteArray())
    }

    override suspend fun loadSession(): UserSession = withContext(Dispatchers.IO) {
        val bytes = store.read(FILE) ?: throw NoSessionFoundException()
        try {
            AppJson.decodeFromString(UserSession.serializer(), bytes.decodeToString())
        } catch (e: Exception) {
            store.delete(FILE)
            throw NoSessionFoundException()
        }
    }

    override suspend fun deleteSession() = withContext(Dispatchers.IO) {
        store.delete(FILE)
    }

    private companion object {
        const val FILE = "supabase_session"
    }
}
