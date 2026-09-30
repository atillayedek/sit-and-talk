package com.sitandtalk.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLinkTest {
    private val room = "631af447-c1db-4c2a-9aaf-41993a94c90f"

    private fun parse(scheme: String, host: String, path: List<String>, query: Map<String, String> = emptyMap(), appHost: String = "") =
        AppLink.parseParts(scheme, host, path, appHost) { query[it] }

    @Test
    fun roomInviteWithCode() {
        assertEquals(AppLink.Room(room, "abc123"), parse("sitandtalk", "room", listOf(room), mapOf("code" to "abc123")))
    }

    @Test
    fun inviteCodeIsBounded() {
        val link = parse("sitandtalk", "room", listOf(room), mapOf("code" to "x".repeat(500))) as AppLink.Room
        assertEquals(64, link.inviteCode!!.length)
    }

    @Test
    fun postAndProfileLinks() {
        assertEquals(AppLink.Post(room), parse("sitandtalk", "post", listOf(room)))
        assertEquals(AppLink.Profile(room), parse("sitandtalk", "user", listOf(room)))
        assertEquals(AppLink.Chat(room), parse("sitandtalk", "chat", listOf(room)))
    }

    @Test
    fun malformedIdsAreRejected() {
        assertNull(parse("sitandtalk", "room", listOf("not-a-uuid")))
        assertNull(parse("sitandtalk", "post", listOf("../../etc")))
        assertNull(parse("sitandtalk", "room", emptyList()))
    }

    @Test
    fun unknownSchemesAndHostsAreIgnored() {
        assertNull(parse("http", "room", listOf(room)))
        assertNull(parse("https", "evil.example", listOf("r", room), appHost = "sitandtalk.app"))
        assertNull(parse("sitandtalk", "admin", listOf(room)))
    }

    @Test
    fun verifiedAppLinksUseShortPaths() {
        assertEquals(AppLink.Room(room, null), parse("https", "sitandtalk.app", listOf("r", room), appHost = "sitandtalk.app"))
        assertEquals(AppLink.Post(room), parse("https", "sitandtalk.app", listOf("p", room), appHost = "sitandtalk.app"))
    }

    @Test
    fun appLinksAreOffWithoutConfiguredHost() {
        assertNull(parse("https", "sitandtalk.app", listOf("r", room), appHost = ""))
    }

    @Test
    fun internalDestinations() {
        assertEquals(AppLink.FriendRequests, parse("sitandtalk", "friends", emptyList()))
        assertEquals(AppLink.ActiveCall, parse("sitandtalk", "call", emptyList()))
        assertEquals(AppLink.ActiveRoom, parse("sitandtalk", "active-room", emptyList()))
        assertEquals(AppLink.Notifications, parse("sitandtalk", "notifications", emptyList()))
    }
}
