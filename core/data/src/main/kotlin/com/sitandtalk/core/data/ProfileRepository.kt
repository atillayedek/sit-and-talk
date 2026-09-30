package com.sitandtalk.core.data

import android.net.Uri
import com.sitandtalk.core.model.Interest
import com.sitandtalk.core.model.PrivacySettings
import com.sitandtalk.core.model.ProfileCard
import com.sitandtalk.core.model.UserSummary
import com.sitandtalk.core.network.Api
import com.sitandtalk.core.network.apiCall
import com.sitandtalk.core.network.putNullable
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class OnboardingInput(
    val username: String,
    val displayName: String,
    val birthDate: LocalDate,
    val interests: List<String>,
    val languages: List<String>,
)

object LegalVersions {
    const val TERMS = "2026-09-30"
    const val PRIVACY = "2026-09-30"
    const val COMMUNITY = "2026-09-30"
}

@Singleton
class ProfileRepository @Inject constructor(
    private val api: Api,
    private val auth: AuthRepository,
    private val media: MediaRepository,
) {
    private val _me = MutableStateFlow<ProfileCard?>(null)
    /** The signed-in user's own profile card, refreshed from the server. */
    val me: StateFlow<ProfileCard?> = _me.asStateFlow()

    private var interestCache: List<Interest>? = null

    suspend fun interests(): List<Interest> = interestCache ?: apiCall {
        api.client.from("interests").select(Columns.list("slug", "name_tr", "name_en", "category", "sort")) {
            order("sort", Order.ASCENDING)
        }.decodeList<Interest>()
    }.also { interestCache = it }

    suspend fun completeOnboarding(input: OnboardingInput) {
        api.rpcUnit("complete_onboarding") {
            put("p_username", input.username.trim().lowercase())
            put("p_display_name", input.displayName.trim())
            put("p_birth_date", input.birthDate.toString())
            put("p_interests", JsonArray(input.interests.map { JsonPrimitive(it) }))
            put("p_languages", JsonArray(input.languages.map { JsonPrimitive(it) }))
            put("p_terms_version", LegalVersions.TERMS)
            put("p_privacy_version", LegalVersions.PRIVACY)
            put("p_community_version", LegalVersions.COMMUNITY)
        }
        refreshMe()
    }

    suspend fun refreshMe(): ProfileCard {
        val card = profile(auth.requireUserId())
        _me.value = card
        return card
    }

    suspend fun profile(userId: String): ProfileCard = api.rpc("get_profile") { put("p_user", userId) }

    suspend fun updateBasics(displayName: String, bio: String, countryCode: String?) {
        val uid = auth.requireUserId()
        apiCall {
            api.client.from("profiles").update(buildJsonObject {
                put("display_name", displayName.trim())
                put("bio", bio.trim())
                putNullable("country_code", countryCode?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{2}")) })
            }) {
                filter { eq("id", uid) }
            }
        }
        refreshMe()
    }

    suspend fun changeUsername(username: String) {
        api.rpcUnit("change_username") { put("p_username", username.trim().lowercase()) }
        refreshMe()
    }

    suspend fun setInterests(slugs: List<String>, privateSlugs: List<String>) {
        api.rpcUnit("set_interests") {
            put("p_interests", JsonArray(slugs.map { JsonPrimitive(it) }))
            put("p_private", JsonArray(privateSlugs.map { JsonPrimitive(it) }))
        }
        refreshMe()
    }

    suspend fun setLanguages(codes: List<String>) {
        api.rpcUnit("set_languages") { put("p_languages", JsonArray(codes.map { JsonPrimitive(it) })) }
        refreshMe()
    }

    suspend fun privateInterestSlugs(): List<String> {
        val uid = auth.requireUserId()
        return apiCall {
            api.client.from("user_interests").select(Columns.list("slug", "is_public")) {
                filter { eq("user_id", uid) }
            }.decodeList<InterestVisibility>().filter { !it.isPublic }.map { it.slug }
        }
    }

    suspend fun uploadAvatar(uri: Uri) {
        val uid = auth.requireUserId()
        val path = media.uploadImage(com.sitandtalk.core.data.StorageBuckets.AVATARS, uid, uri, maxDimension = 640)
        api.rpcUnit("set_avatar") { put("p_path", path) }
        refreshMe()
    }

    suspend fun removeAvatar() {
        api.rpcUnit("set_avatar") { put("p_path", kotlinx.serialization.json.JsonNull) }
        refreshMe()
    }

    suspend fun privacy(): PrivacySettings {
        val uid = auth.requireUserId()
        return apiCall {
            api.client.from("user_private").select(
                Columns.list(
                    "message_policy", "call_policy", "online_visibility", "last_seen_visibility", "read_receipts",
                    "media_from_non_friends", "share_avatar_in_random", "profile_discoverable", "onboarding_completed_at",
                ),
            ) { filter { eq("user_id", uid) } }.decodeSingle<PrivacySettings>()
        }
    }

    suspend fun updatePrivacy(settings: PrivacySettings) {
        val uid = auth.requireUserId()
        apiCall {
            api.client.from("user_private").update(buildJsonObject {
                put("message_policy", settings.messagePolicy.name.lowercase())
                put("call_policy", settings.callPolicy.name.lowercase())
                put("online_visibility", settings.onlineVisibility.name.lowercase())
                put("last_seen_visibility", settings.lastSeenVisibility.name.lowercase())
                put("read_receipts", settings.readReceipts)
                put("media_from_non_friends", settings.mediaFromNonFriends)
                put("share_avatar_in_random", settings.shareAvatarInRandom)
                put("profile_discoverable", settings.profileDiscoverable)
            }) { filter { eq("user_id", uid) } }
        }
    }

    suspend fun searchUsers(query: String): List<UserSummary> = api.rpc("search_users") {
        put("p_query", query)
        put("p_limit", 30)
    }

    suspend fun touchPresence() {
        runCatching { api.rpcUnit("touch_presence") }
    }

    fun clear() {
        _me.value = null
    }
}

@kotlinx.serialization.Serializable
private data class InterestVisibility(
    val slug: String,
    @kotlinx.serialization.SerialName("is_public") val isPublic: Boolean = true,
)

object StorageBuckets {
    const val AVATARS = "avatars"
    const val POST_MEDIA = "post-media"
    const val STORIES = "stories"
    const val CHAT_MEDIA = "chat-media"
    const val REPORT_EVIDENCE = "report-evidence"
}
