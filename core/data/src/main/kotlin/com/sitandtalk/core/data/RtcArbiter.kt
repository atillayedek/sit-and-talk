package com.sitandtalk.core.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Guarantees that at most one RTC session (a call or a room) is live at any time. */
@Singleton
class RtcArbiter @Inject constructor() {
    enum class Owner { Call, Room }

    private val mutex = Mutex()
    private var owner: Owner? = null
    private var release: (suspend () -> Unit)? = null

    /** Makes [newOwner] the RTC owner, first releasing whichever other session held it. */
    suspend fun acquire(newOwner: Owner, onRelease: suspend () -> Unit) {
        val previous = mutex.withLock {
            val prev = if (owner != null && owner != newOwner) release else null
            owner = newOwner
            release = onRelease
            prev
        }
        previous?.invoke()
    }

    suspend fun releaseIfOwner(who: Owner) = mutex.withLock {
        if (owner == who) {
            owner = null
            release = null
        }
    }

    fun currentOwner(): Owner? = owner
}
