package com.sitandtalk.core.network

import com.sitandtalk.core.model.Bootstrap
import com.sitandtalk.core.model.ServerTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapDecodingTest {
    // Verbatim response of POST /rest/v1/rpc/app_bootstrap from the deployed backend (anonymous caller).
    private val live = """
        {"flags": {"feed": true, "gifts": false, "rooms": true, "premium": false, "text_matching": true,
        "google_sign_in": false, "video_matching": false, "voice_matching": true}, "is_staff": false,
        "settings": {"call_initial_seconds": 180, "match_accept_seconds": 20, "call_extension_seconds": 300,
        "message_edit_window_minutes": 15, "message_unsend_window_minutes": 60}, "suspended": false,
        "server_now": "2026-09-30T11:21:21.732464+00:00", "staff_role": null,
        "maintenance": {"enabled": false, "message_en": "", "message_tr": ""},
        "announcement": {"active": false, "body_en": "", "body_tr": "", "title_en": "", "title_tr": ""},
        "restrictions": [], "update_required": false, "deletion_pending": false, "min_version_code": 1,
        "profile_complete": false}
    """.trimIndent()

    @Test
    fun decodesTheLiveBootstrapResponse() {
        val b = AppJson.decodeFromString(Bootstrap.serializer(), live)
        assertTrue(b.flag("voice_matching"))
        assertTrue(b.flag("rooms"))
        assertFalse(b.flag("video_matching"))
        assertFalse(b.flag("does_not_exist"))
        assertFalse(b.profileComplete)
        assertFalse(b.updateRequired)
        assertFalse(b.maintenance.enabled)
        assertEquals(1, b.minVersionCode)
        assertEquals("180", b.settings["call_initial_seconds"].toString())
        assertNotNull(ServerTime.parse(b.serverNow))
    }

    @Test
    fun unknownFieldsAndMissingOptionalFieldsDoNotCrash() {
        val b = AppJson.decodeFromString(Bootstrap.serializer(), """{"new_server_field": {"x": 1}, "flags": {}}""")
        assertFalse(b.suspended)
        assertTrue(b.restrictions.isEmpty())
    }
}
