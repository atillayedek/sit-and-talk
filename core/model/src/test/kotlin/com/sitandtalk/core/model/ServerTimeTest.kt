package com.sitandtalk.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ServerTimeTest {
    @Test
    fun parsesPostgresTimestampWithOffsetAndMicroseconds() {
        // Exact format returned by the live app_bootstrap RPC (timestamptz as JSON).
        val parsed = ServerTime.parse("2026-09-30T11:21:21.732464+00:00")
        assertEquals(Instant.parse("2026-09-30T11:21:21.732464Z"), parsed)
    }

    @Test
    fun parsesNonUtcOffsetsAndZulu() {
        assertEquals(Instant.parse("2026-09-30T11:00:00Z"), ServerTime.parse("2026-09-30T14:00:00+03:00"))
        assertEquals(Instant.parse("2026-09-30T11:00:00Z"), ServerTime.parse("2026-09-30T11:00:00Z"))
    }

    @Test
    fun invalidInputIsNull() {
        assertNull(ServerTime.parse(null))
        assertNull(ServerTime.parse(""))
        assertNull(ServerTime.parse("yesterday"))
    }

    @Test
    fun remainingIsNeverNegative() {
        assertEquals(Duration.ZERO, ServerTime.remaining("2000-01-01T00:00:00+00:00"))
        assertNull(ServerTime.remaining(null))
    }

    @Test
    fun syncAlignsTheClockWithTheServer() {
        val serverNow = Instant.now().plusSeconds(3600)
        ServerTime.sync(serverNow.toString())
        val drift = Duration.between(serverNow, ServerTime.now()).abs()
        assertTrue("drift was $drift", drift < Duration.ofSeconds(5))
        ServerTime.sync(Instant.now().toString())
    }
}
