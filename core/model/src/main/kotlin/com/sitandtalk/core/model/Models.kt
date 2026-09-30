package com.sitandtalk.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Field names mirror the JSON produced by the Supabase RPCs (snake_case).

// ---------------------------------------------------------------------------------------------
// People
// ---------------------------------------------------------------------------------------------

@Serializable
data class UserSummary(
    val id: String,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_path") val avatarPath: String? = null,
)

@Serializable
data class PendingRequestRef(val id: String, val outgoing: Boolean)

@Serializable
data class ProfileCard(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    val bio: String = "",
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("gifts_received") val giftsReceived: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("is_self") val isSelf: Boolean = false,
    @SerialName("is_friend") val isFriend: Boolean = false,
    @SerialName("pending_request") val pendingRequest: PendingRequestRef? = null,
    @SerialName("is_premium") val isPremium: Boolean = false,
    val interests: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    val online: Boolean? = null,
    @SerialName("can_message") val canMessage: Boolean = false,
    @SerialName("can_call") val canCall: Boolean = false,
)

@Serializable
enum class MessagePolicy { @SerialName("everyone") Everyone, @SerialName("friends") Friends, @SerialName("nobody") Nobody }

@Serializable
enum class VisibilityPolicy { @SerialName("everyone") Everyone, @SerialName("friends") Friends, @SerialName("nobody") Nobody }

@Serializable
data class PrivacySettings(
    @SerialName("message_policy") val messagePolicy: MessagePolicy = MessagePolicy.Friends,
    @SerialName("call_policy") val callPolicy: MessagePolicy = MessagePolicy.Friends,
    @SerialName("online_visibility") val onlineVisibility: VisibilityPolicy = VisibilityPolicy.Friends,
    @SerialName("last_seen_visibility") val lastSeenVisibility: VisibilityPolicy = VisibilityPolicy.Friends,
    @SerialName("read_receipts") val readReceipts: Boolean = true,
    @SerialName("media_from_non_friends") val mediaFromNonFriends: Boolean = false,
    @SerialName("share_avatar_in_random") val shareAvatarInRandom: Boolean = false,
    @SerialName("profile_discoverable") val profileDiscoverable: Boolean = true,
    @SerialName("onboarding_completed_at") val onboardingCompletedAt: String? = null,
)

@Serializable
data class Interest(
    val slug: String,
    @SerialName("name_tr") val nameTr: String,
    @SerialName("name_en") val nameEn: String,
    val category: String = "general",
    val sort: Int = 0,
)

@Serializable
data class Friend(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_path") val avatarPath: String? = null,
    val favorite: Boolean = false,
    val since: String? = null,
    val online: Boolean? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("can_call") val canCall: Boolean = false,
)

@Serializable
data class FriendRequestItem(
    val id: String,
    val outgoing: Boolean,
    @SerialName("created_at") val createdAt: String,
    val source: String = "profile",
    val user: UserSummary,
)

@Serializable
data class BlockedUser(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("blocked_at") val blockedAt: String,
)

// ---------------------------------------------------------------------------------------------
// Matching and calls
// ---------------------------------------------------------------------------------------------

@Serializable
enum class TalkMode { @SerialName("voice") Voice, @SerialName("video") Video, @SerialName("text") Text }

enum class TalkIntent(val wire: String) { Friends("friends"), Chat("chat"), Gaming("gaming"), Language("language"), Topic("topic"), Listen("listen") }

@Serializable
data class QueueState(
    val state: String,
    @SerialName("session_id") val sessionId: String? = null,
    val mode: TalkMode? = null,
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("match_id") val matchId: String? = null,
    @SerialName("peer_alias") val peerAlias: String? = null,
    @SerialName("common_interests") val commonInterests: List<String> = emptyList(),
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("i_accepted") val iAccepted: Boolean = false,
    @SerialName("server_now") val serverNow: String? = null,
) {
    val isIdle get() = state == "idle"
    val isWaiting get() = state == "waiting"
    val isMatched get() = state == "matched"
    val isInSession get() = state == "in_session"
}

@Serializable
data class CallState(
    @SerialName("session_id") val sessionId: String,
    val kind: String,
    val mode: TalkMode,
    val status: String,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("my_slot") val mySlot: Int = 0,
    @SerialName("my_alias") val myAlias: String = "",
    @SerialName("i_left") val iLeft: Boolean = false,
    @SerialName("my_upgrade_request") val myUpgradeRequest: TalkMode? = null,
    @SerialName("peer_alias") val peerAlias: String? = null,
    @SerialName("peer_rtc_uid") val peerRtcUid: Int? = null,
    @SerialName("peer_avatar_path") val peerAvatarPath: String? = null,
    @SerialName("peer_profile") val peerProfile: UserSummary? = null,
    @SerialName("peer_joined") val peerJoined: Boolean = false,
    @SerialName("peer_upgrade_request") val peerUpgradeRequest: TalkMode? = null,
    @SerialName("peer_typing") val peerTyping: Boolean? = null,
    @SerialName("common_interests") val commonInterests: List<String> = emptyList(),
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("ring_expires_at") val ringExpiresAt: String? = null,
    @SerialName("extension_requested_by_me") val extensionRequestedByMe: Boolean? = null,
    @SerialName("extension_requested_by_peer") val extensionRequestedByPeer: Boolean? = null,
    @SerialName("extensions_applied") val extensionsApplied: Int = 0,
    @SerialName("feedback_given") val feedbackGiven: Boolean = false,
    @SerialName("server_now") val serverNow: String? = null,
) {
    val isRandom get() = kind == "random"
    val isDirect get() = kind == "direct"
    val isRinging get() = status == "ringing"
    val isConnecting get() = status == "connecting"
    val isActive get() = status == "active"
    val isEnded get() = status == "ended"
    val isCaller get() = mySlot == 1
    val needsRtc get() = mode != TalkMode.Text && (isConnecting || isActive)
}

@Serializable
data class MatchMessage(
    val id: String,
    val mine: Boolean,
    val body: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class FeedbackResult(val mutual: Boolean, @SerialName("peer_id") val peerId: String? = null)

@Serializable
data class RtcCredentials(
    @SerialName("app_id") val appId: String,
    @SerialName("channel_name") val channelName: String,
    val uid: Int,
    val token: String,
    val role: String,
    val mode: String = "voice",
    @SerialName("expires_at_epoch_seconds") val expiresAtEpochSeconds: Long,
) {
    val canPublish get() = role == "publisher"
}

// ---------------------------------------------------------------------------------------------
// Rooms
// ---------------------------------------------------------------------------------------------

@Serializable
enum class RoomRole { @SerialName("owner") Owner, @SerialName("moderator") Moderator, @SerialName("speaker") Speaker, @SerialName("listener") Listener;
    val canModerate get() = this == Owner || this == Moderator
    val canSpeak get() = this != Listener
}

@Serializable
enum class RoomVisibility { @SerialName("public") Public, @SerialName("invite") Invite, @SerialName("password") Password }

@Serializable
data class RoomSummary(
    val id: String,
    val title: String,
    val description: String = "",
    val topic: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("language_code") val languageCode: String,
    @SerialName("cover_path") val coverPath: String? = null,
    val visibility: RoomVisibility,
    @SerialName("max_participants") val maxParticipants: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("participant_count") val participantCount: Int = 0,
    @SerialName("speaker_count") val speakerCount: Int = 0,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("is_member") val isMember: Boolean = false,
    val owner: UserSummary? = null,
) {
    val isFull get() = participantCount >= maxParticipants
}

@Serializable
data class RoomMember(
    @SerialName("user_id") val userId: String,
    val role: RoomRole,
    @SerialName("rtc_uid") val rtcUid: Int,
    @SerialName("self_muted") val selfMuted: Boolean = true,
    @SerialName("muted_by_moderator") val mutedByModerator: Boolean = false,
    @SerialName("hand_raised_at") val handRaisedAt: String? = null,
    @SerialName("joined_at") val joinedAt: String,
    @SerialName("display_name") val displayName: String,
    val username: String = "",
    @SerialName("avatar_path") val avatarPath: String? = null,
)

@Serializable
data class SpeakerRequest(
    val id: String,
    @SerialName("user_id") val userId: String,
    val kind: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class MyInvite(val id: String, @SerialName("created_at") val createdAt: String)

@Serializable
data class RoomState(
    val id: String,
    val title: String,
    val description: String = "",
    val topic: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("language_code") val languageCode: String,
    @SerialName("cover_path") val coverPath: String? = null,
    val visibility: RoomVisibility,
    val status: String,
    @SerialName("close_reason") val closeReason: String? = null,
    @SerialName("max_participants") val maxParticipants: Int,
    @SerialName("max_speakers") val maxSpeakers: Int,
    @SerialName("hand_raise_required") val handRaiseRequired: Boolean,
    @SerialName("text_chat_enabled") val textChatEnabled: Boolean,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("my_role") val myRole: RoomRole? = null,
    val members: List<RoomMember> = emptyList(),
    @SerialName("pending_requests") val pendingRequests: List<SpeakerRequest> = emptyList(),
    @SerialName("my_invite") val myInvite: MyInvite? = null,
) {
    val isOpen get() = status == "open"
    val speakers get() = members.filter { it.role.canSpeak }
    val listeners get() = members.filter { !it.role.canSpeak }
}

@Serializable
data class RoomMessage(
    val id: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("user_id") val userId: String? = null,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class RoomReaction(
    val id: Long,
    @SerialName("room_id") val roomId: String,
    @SerialName("user_id") val userId: String? = null,
    val kind: String,
    @SerialName("gift_code") val giftCode: String? = null,
    @SerialName("target_user_id") val targetUserId: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class RoomEvent(
    val id: String,
    val title: String,
    val description: String = "",
    val topic: String? = null,
    @SerialName("language_code") val languageCode: String,
    @SerialName("starts_at") val startsAt: String,
    val status: String,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("is_mine") val isMine: Boolean = false,
    val subscribed: Boolean = false,
    @SerialName("subscriber_count") val subscriberCount: Int = 0,
    val host: UserSummary? = null,
)

@Serializable
data class RoomInvite(val id: String, val code: String? = null, @SerialName("room_id") val roomId: String)

// ---------------------------------------------------------------------------------------------
// Messaging
// ---------------------------------------------------------------------------------------------

@Serializable
enum class MessageKind { @SerialName("text") Text, @SerialName("image") Image, @SerialName("voice") Voice, @SerialName("system") System }

@Serializable
data class LastMessage(
    val id: String,
    val kind: MessageKind,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("created_at") val createdAt: String,
    val body: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class ConversationItem(
    val id: String,
    val kind: String,
    val title: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    @SerialName("sort_at") val sortAt: String? = null,
    val peer: UserSummary? = null,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("last_message") val lastMessage: LastMessage? = null,
    @SerialName("unread_count") val unreadCount: Int = 0,
) {
    val isGroup get() = kind == "group"
}

@Serializable
data class Message(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String? = null,
    val kind: MessageKind,
    val body: String? = null,
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("media_mime") val mediaMime: String? = null,
    @SerialName("media_duration_ms") val mediaDurationMs: Int? = null,
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("deleted_for_all_at") val deletedForAllAt: String? = null,
) {
    val isDeleted get() = deletedForAllAt != null
}

@Serializable
data class ConversationMember(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String,
    val role: String = "member",
    @SerialName("joined_at") val joinedAt: String,
    @SerialName("left_at") val leftAt: String? = null,
    @SerialName("typing_until") val typingUntil: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("shared_read_at") val sharedReadAt: String? = null,
)

@Serializable
data class MessageReaction(
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String,
    @SerialName("created_at") val createdAt: String,
)

// ---------------------------------------------------------------------------------------------
// Feed
// ---------------------------------------------------------------------------------------------

@Serializable
enum class PostKind { @SerialName("text") Text, @SerialName("photo") Photo, @SerialName("voice") Voice, @SerialName("question") Question, @SerialName("status") Status }

@Serializable
enum class PostVisibility { @SerialName("public") Public, @SerialName("friends") Friends }

@Serializable
data class PostMedia(val path: String, val width: Int? = null, val height: Int? = null)

@Serializable
data class Post(
    val id: String,
    val kind: PostKind,
    val body: String = "",
    @SerialName("audio_path") val audioPath: String? = null,
    @SerialName("audio_duration_ms") val audioDurationMs: Int? = null,
    val interests: List<String> = emptyList(),
    val visibility: PostVisibility = PostVisibility.Public,
    val moderation: String = "visible",
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("is_mine") val isMine: Boolean = false,
    val liked: Boolean = false,
    val saved: Boolean = false,
    val media: List<PostMedia> = emptyList(),
    val author: UserSummary,
)

@Serializable
data class LikeResult(val liked: Boolean, @SerialName("like_count") val likeCount: Int)

@Serializable
data class Comment(
    val id: String,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("created_at") val createdAt: String,
    val body: String? = null,
    val deleted: Boolean = false,
    @SerialName("is_mine") val isMine: Boolean = false,
    val author: UserSummary,
)

@Serializable
data class StoryItem(
    val id: String,
    @SerialName("media_path") val mediaPath: String,
    val caption: String = "",
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class StoryGroup(
    val author: UserSummary,
    @SerialName("is_mine") val isMine: Boolean = false,
    val items: List<StoryItem> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------------------------

@Serializable
data class NotificationItem(
    val id: String,
    @SerialName("user_id") val userId: String,
    val kind: String,
    @SerialName("actor_id") val actorId: String? = null,
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("entity_id") val entityId: String? = null,
    val data: JsonObject = JsonObject(emptyMap()),
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class NotificationPreferences(
    @SerialName("user_id") val userId: String,
    @SerialName("friend_requests") val friendRequests: Boolean = true,
    val messages: Boolean = true,
    val calls: Boolean = true,
    @SerialName("room_invites") val roomInvites: Boolean = true,
    val events: Boolean = true,
    val comments: Boolean = true,
    val purchases: Boolean = true,
    @SerialName("show_previews") val showPreviews: Boolean = false,
)

// ---------------------------------------------------------------------------------------------
// Wallet
// ---------------------------------------------------------------------------------------------

@Serializable
data class SubscriptionInfo(
    @SerialName("product_id") val productId: String,
    val status: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("auto_renewing") val autoRenewing: Boolean = false,
)

@Serializable
data class Entitlements(
    @SerialName("is_premium") val isPremium: Boolean = false,
    val subscription: SubscriptionInfo? = null,
    val balance: Long = 0,
    @SerialName("gifts_enabled") val giftsEnabled: Boolean = false,
    @SerialName("premium_enabled") val premiumEnabled: Boolean = false,
    @SerialName("coin_products") val coinProducts: Map<String, Int> = emptyMap(),
    @SerialName("premium_products") val premiumProducts: List<String> = emptyList(),
)

@Serializable
data class WalletTx(
    val id: Long,
    val amount: Long,
    val kind: String,
    @SerialName("balance_after") val balanceAfter: Long,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class GiftItem(
    val id: String,
    val code: String,
    @SerialName("name_tr") val nameTr: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("icon_key") val iconKey: String,
    @SerialName("price_coins") val priceCoins: Int,
    val sort: Int = 0,
)

@Serializable
data class GiftResult(@SerialName("gift_transaction_id") val giftTransactionId: String, val balance: Long)

// ---------------------------------------------------------------------------------------------
// Moderation, bootstrap and staff
// ---------------------------------------------------------------------------------------------

@Serializable
enum class ReportTarget {
    @SerialName("user") User, @SerialName("message") Message, @SerialName("post") Post, @SerialName("comment") Comment,
    @SerialName("story") Story, @SerialName("room") Room, @SerialName("room_message") RoomMessage, @SerialName("call") Call
}

@Serializable
enum class ReportReason(val wire: String) {
    @SerialName("harassment") Harassment("harassment"),
    @SerialName("hate") Hate("hate"),
    @SerialName("sexual_content") SexualContent("sexual_content"),
    @SerialName("minor_safety") MinorSafety("minor_safety"),
    @SerialName("violence") Violence("violence"),
    @SerialName("self_harm") SelfHarm("self_harm"),
    @SerialName("spam") Spam("spam"),
    @SerialName("scam") Scam("scam"),
    @SerialName("impersonation") Impersonation("impersonation"),
    @SerialName("underage") Underage("underage"),
    @SerialName("other") Other("other"),
}

@Serializable
data class MyReport(
    val id: String,
    @SerialName("target_type") val targetType: String,
    val reason: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("resolved_at") val resolvedAt: String? = null,
)

@Serializable
data class AppealInfo(val id: String, val status: String, @SerialName("decision_note") val decisionNote: String? = null)

@Serializable
data class Restriction(
    val id: String,
    val kind: String,
    val reason: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    val appeal: AppealInfo? = null,
)

@Serializable
data class Maintenance(
    val enabled: Boolean = false,
    @SerialName("message_tr") val messageTr: String = "",
    @SerialName("message_en") val messageEn: String = "",
)

@Serializable
data class Announcement(
    val active: Boolean = false,
    @SerialName("title_tr") val titleTr: String = "",
    @SerialName("body_tr") val bodyTr: String = "",
    @SerialName("title_en") val titleEn: String = "",
    @SerialName("body_en") val bodyEn: String = "",
)

@Serializable
data class Bootstrap(
    @SerialName("min_version_code") val minVersionCode: Int = 1,
    @SerialName("update_required") val updateRequired: Boolean = false,
    val maintenance: Maintenance = Maintenance(),
    val announcement: Announcement = Announcement(),
    val flags: Map<String, Boolean> = emptyMap(),
    val settings: Map<String, JsonElement> = emptyMap(),
    @SerialName("profile_complete") val profileComplete: Boolean = false,
    @SerialName("is_staff") val isStaff: Boolean = false,
    @SerialName("staff_role") val staffRole: String? = null,
    val suspended: Boolean = false,
    val restrictions: List<Restriction> = emptyList(),
    @SerialName("deletion_pending") val deletionPending: Boolean = false,
    @SerialName("server_now") val serverNow: String? = null,
) {
    fun flag(key: String) = flags[key] == true
}

@Serializable
data class AdminDashboard(
    @SerialName("users_total") val usersTotal: Long = 0,
    @SerialName("users_new_7d") val usersNew7d: Long = 0,
    @SerialName("active_24h") val active24h: Long = 0,
    @SerialName("active_calls") val activeCalls: Long = 0,
    @SerialName("queue_waiting") val queueWaiting: Long = 0,
    @SerialName("open_rooms") val openRooms: Long = 0,
    @SerialName("open_reports") val openReports: Long = 0,
    @SerialName("open_appeals") val openAppeals: Long = 0,
    @SerialName("restricted_accounts") val restrictedAccounts: Long = 0,
    @SerialName("pending_content") val pendingContent: Long = 0,
    @SerialName("purchases_30d") val purchases30d: Long = 0,
    @SerialName("active_subscriptions") val activeSubscriptions: Long = 0,
    @SerialName("push_failed_24h") val pushFailed24h: Long = 0,
    @SerialName("push_pending") val pushPending: Long = 0,
)

@Serializable
data class AdminReport(
    val id: String,
    @SerialName("target_type") val targetType: ReportTarget,
    @SerialName("target_id") val targetId: String,
    val reason: String,
    val details: String = "",
    val context: JsonObject = JsonObject(emptyMap()),
    @SerialName("evidence_path") val evidencePath: String? = null,
    val status: String,
    val priority: Int = 0,
    @SerialName("created_at") val createdAt: String,
    val resolution: String? = null,
    val reporter: UserSummary? = null,
    @SerialName("target_user") val targetUser: UserSummary? = null,
    @SerialName("target_report_count") val targetReportCount: Int = 0,
    @SerialName("target_active_restrictions") val targetActiveRestrictions: Int = 0,
)

@Serializable
data class AdminAppealRestriction(val id: String, val kind: String, val reason: String, @SerialName("ends_at") val endsAt: String? = null)

@Serializable
data class AdminAppeal(
    val id: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    val status: String,
    val user: UserSummary? = null,
    val restriction: AdminAppealRestriction? = null,
)

@Serializable
data class AdminRoom(
    val id: String,
    val title: String,
    val visibility: RoomVisibility,
    @SerialName("created_at") val createdAt: String,
    val owner: UserSummary? = null,
    @SerialName("participant_count") val participantCount: Int = 0,
    @SerialName("open_reports") val openReports: Int = 0,
)

@Serializable
data class FeatureFlag(val key: String, val enabled: Boolean, val description: String = "")

@Serializable
data class AppSetting(val key: String, val value: JsonElement, val description: String = "")

@Serializable
data class AuditLogEntry(
    val id: Long,
    @SerialName("actor_id") val actorId: String? = null,
    val action: String,
    @SerialName("target_type") val targetType: String,
    @SerialName("target_id") val targetId: String? = null,
    val reason: String? = null,
    val result: String = "ok",
    @SerialName("created_at") val createdAt: String,
)
