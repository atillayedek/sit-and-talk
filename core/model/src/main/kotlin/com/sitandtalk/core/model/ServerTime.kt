package com.sitandtalk.core.model

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** Parses Postgres/ISO-8601 timestamps and tracks the offset between the server clock and this device. */
object ServerTime {
    @Volatile
    private var offsetMillis: Long = 0

    fun parse(value: String?): Instant? = value?.let {
        runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
    }

    /** Call with the `server_now` value of any RPC response. */
    fun sync(serverNow: String?) {
        val server = parse(serverNow) ?: return
        offsetMillis = server.toEpochMilli() - System.currentTimeMillis()
    }

    fun now(): Instant = Instant.ofEpochMilli(System.currentTimeMillis() + offsetMillis)

    /** Remaining time until [deadline] measured on the server clock; never negative. */
    fun remaining(deadline: String?): Duration? {
        val end = parse(deadline) ?: return null
        val d = Duration.between(now(), end)
        return if (d.isNegative) Duration.ZERO else d
    }
}
