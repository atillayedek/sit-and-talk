package com.sitandtalk.core.data

import android.net.Uri
import com.sitandtalk.core.model.Comment
import com.sitandtalk.core.model.LikeResult
import com.sitandtalk.core.model.Post
import com.sitandtalk.core.model.PostKind
import com.sitandtalk.core.model.PostVisibility
import com.sitandtalk.core.model.StoryGroup
import com.sitandtalk.core.network.Api
import com.sitandtalk.core.network.putNullable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class FeedTab(val wire: String) { New("new"), Friends("friends"), Interests("interests"), Discover("discover"), Saved("saved") }

data class NewPost(
    val kind: PostKind,
    val body: String,
    val images: List<Uri> = emptyList(),
    val audio: File? = null,
    val audioDurationMs: Int? = null,
    val interests: List<String> = emptyList(),
    val visibility: PostVisibility = PostVisibility.Public,
)

@Singleton
class FeedRepository @Inject constructor(
    private val api: Api,
    private val auth: AuthRepository,
    private val media: MediaRepository,
) {
    suspend fun feed(tab: FeedTab, before: String? = null): List<Post> = api.rpc("get_feed") {
        put("p_tab", tab.wire)
        putNullable("p_before", before)
        put("p_limit", 20)
    }

    suspend fun userPosts(userId: String, before: String? = null): List<Post> = api.rpc("get_feed") {
        put("p_tab", "user")
        putNullable("p_before", before)
        put("p_limit", 20)
        put("p_user", userId)
    }

    suspend fun post(postId: String): Post = api.rpc("get_post") { put("p_post", postId) }

    /** Uploads media first; the post row is created only after every upload succeeded. */
    suspend fun create(input: NewPost, clientId: String = UUID.randomUUID().toString()): Post {
        val uid = auth.requireUserId()
        val uploaded = input.images.map { uri -> media.uploadImage(StorageBuckets.POST_MEDIA, uid, uri) }
        val audioPath = input.audio?.let { media.uploadAudio(StorageBuckets.POST_MEDIA, uid, it) }
        return api.rpc("create_post") {
            put("p_id", clientId)
            put("p_kind", input.kind.name.lowercase())
            put("p_body", input.body)
            put("p_media", JsonArray(uploaded.map { path -> buildJsonObject { put("path", path) } }))
            putNullable("p_audio_path", audioPath)
            if (input.audioDurationMs != null) put("p_audio_duration_ms", input.audioDurationMs) else putNullable("p_audio_duration_ms", null)
            put("p_interests", JsonArray(input.interests.map { JsonPrimitive(it) }))
            put("p_visibility", input.visibility.name.lowercase())
        }
    }

    suspend fun delete(postId: String) = api.rpcUnit("delete_post") { put("p_post", postId) }

    suspend fun setLiked(postId: String, liked: Boolean): LikeResult = api.rpc("set_post_like") {
        put("p_post", postId)
        put("p_liked", liked)
    }

    suspend fun setSaved(postId: String, saved: Boolean) = api.rpcUnit("set_post_saved") {
        put("p_post", postId)
        put("p_saved", saved)
    }

    suspend fun hide(postId: String, notInterested: Boolean) = api.rpcUnit("hide_post") {
        put("p_post", postId)
        put("p_reason", if (notInterested) "not_interested" else "hidden")
    }

    suspend fun comments(postId: String): List<Comment> = api.rpc("list_comments") { put("p_post", postId) }

    suspend fun addComment(postId: String, body: String, parentId: String?): String = api.rpc("add_comment") {
        put("p_post", postId)
        put("p_body", body.trim())
        putNullable("p_parent", parentId)
    }

    suspend fun deleteComment(commentId: String) = api.rpcUnit("delete_comment") { put("p_comment", commentId) }

    suspend fun stories(): List<StoryGroup> = api.rpc("list_stories")

    suspend fun createStory(image: Uri, caption: String, visibility: PostVisibility): String {
        val path = media.uploadImage(StorageBuckets.STORIES, auth.requireUserId(), image)
        return api.rpc("create_story") {
            put("p_media_path", path)
            put("p_caption", caption)
            put("p_visibility", visibility.name.lowercase())
        }
    }

    suspend fun deleteStory(storyId: String) = api.rpcUnit("delete_story") { put("p_story", storyId) }
}
