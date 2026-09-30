package com.sitandtalk.core.data

import com.sitandtalk.core.network.SafeLog
import com.sitandtalk.core.network.SupabaseProvider
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Realtime only signals that something changed; callers re-read the authoritative rows through RLS.
 * A failed subscription degrades to the callers' polling fallback instead of crashing.
 */
@Singleton
class RealtimeStreams @Inject constructor(private val provider: SupabaseProvider) {

    /** Emits every INSERT/UPDATE/DELETE on [table] matching [filter] (PostgREST syntax, e.g. "user_id=eq.<id>"). */
    fun changes(table: String, filter: String? = null): Flow<PostgresAction> = subscribe("$table:${filter.orEmpty()}") {
        postgresChangeFlow<PostgresAction>(schema = "public") {
            this.table = table
            if (filter != null) this.filter = filter
        }
    }

    private fun <T> subscribe(name: String, build: RealtimeChannel.() -> Flow<T>): Flow<T> = flow {
        val client = provider.client
        val channel = client.channel("st-${name.hashCode()}-${UUID.randomUUID()}")
        val stream = channel.build()
        try {
            channel.subscribe()
            emitAll(stream)
        } finally {
            withContext(NonCancellable) {
                runCatching { client.realtime.removeChannel(channel) }
            }
        }
    }.catch { e -> SafeLog.error("realtime", "subscription_failed", e) }
}
