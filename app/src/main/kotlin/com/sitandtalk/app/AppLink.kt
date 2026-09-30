package com.sitandtalk.app

import android.net.Uri

/** Destinations the app can be opened at from links, notifications and the ongoing-call notification. */
sealed interface AppLink {
    data class AuthCallback(val uri: Uri) : AppLink
    data class Room(val roomId: String, val inviteCode: String?) : AppLink
    data class Post(val postId: String) : AppLink
    data class Chat(val conversationId: String) : AppLink
    data class Profile(val userId: String) : AppLink
    data object FriendRequests : AppLink
    data object Events : AppLink
    data object ActiveCall : AppLink
    data object ActiveRoom : AppLink
    data object Notifications : AppLink

    companion object {
        const val SCHEME = "sitandtalk"
        private val UUID = Regex("^[0-9a-fA-F-]{36}$")

        fun parse(uri: Uri, authScheme: String, authHost: String, appLinkHost: String): AppLink? {
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase().orEmpty()
            if (scheme == authScheme.lowercase() && host == authHost.lowercase()) return AuthCallback(uri)
            return parseParts(scheme, host, uri.pathSegments, appLinkHost) { uri.getQueryParameter(it) }
        }

        /** Link routing without android.net.Uri, so it can be unit-tested on the JVM. */
        fun parseParts(
            scheme: String,
            host: String,
            pathSegments: List<String>,
            appLinkHost: String,
            query: (String) -> String?,
        ): AppLink? {
            val segments: List<String> = when {
                scheme == SCHEME -> listOf(host) + pathSegments
                scheme == "https" && appLinkHost.isNotBlank() && host == appLinkHost.lowercase() -> pathSegments
                else -> return null
            }
            val id = segments.getOrNull(1)?.takeIf { UUID.matches(it) }
            return when (segments.firstOrNull()) {
                "room", "r" -> id?.let { Room(it, query("code")?.take(64)) }
                "post", "p" -> id?.let { Post(it) }
                "chat" -> id?.let { Chat(it) }
                "user", "u" -> id?.let { Profile(it) }
                "friends" -> FriendRequests
                "events" -> Events
                "call" -> ActiveCall
                "active-room" -> ActiveRoom
                "notifications" -> Notifications
                else -> null
            }
        }

        fun uriFor(link: AppLink): Uri = Uri.parse(
            when (link) {
                is Room -> "$SCHEME://room/${link.roomId}"
                is Post -> "$SCHEME://post/${link.postId}"
                is Chat -> "$SCHEME://chat/${link.conversationId}"
                is Profile -> "$SCHEME://user/${link.userId}"
                FriendRequests -> "$SCHEME://friends"
                Events -> "$SCHEME://events"
                ActiveCall -> "$SCHEME://call"
                ActiveRoom -> "$SCHEME://active-room"
                Notifications -> "$SCHEME://notifications"
                is AuthCallback -> link.uri.toString()
            },
        )
    }
}
