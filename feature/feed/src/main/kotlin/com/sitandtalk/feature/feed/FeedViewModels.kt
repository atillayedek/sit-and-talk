package com.sitandtalk.feature.feed

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitandtalk.core.data.AudioRecorder
import com.sitandtalk.core.data.FeedRepository
import com.sitandtalk.core.data.FeedTab
import com.sitandtalk.core.data.FriendsRepository
import com.sitandtalk.core.data.ModerationRepository
import com.sitandtalk.core.data.NewPost
import com.sitandtalk.core.data.ProfileRepository
import com.sitandtalk.core.model.AppException
import com.sitandtalk.core.model.Comment
import com.sitandtalk.core.model.Interest
import com.sitandtalk.core.model.LoadState
import com.sitandtalk.core.model.Post
import com.sitandtalk.core.model.PostKind
import com.sitandtalk.core.model.PostVisibility
import com.sitandtalk.core.model.ReportReason
import com.sitandtalk.core.model.ReportTarget
import com.sitandtalk.core.model.StoryGroup
import com.sitandtalk.core.network.toAppException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class FeedState(
    val tab: FeedTab = FeedTab.New,
    val posts: LoadState<List<Post>> = LoadState.Loading,
    val stories: List<StoryGroup> = emptyList(),
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
    val error: AppException? = null,
    val info: String? = null,
    val interests: List<Interest> = emptyList(),
)

/** Post actions shared by the feed, post detail and profile screens. */
abstract class PostActionsViewModel(
    protected val feed: FeedRepository,
    private val moderation: ModerationRepository,
    private val friends: FriendsRepository,
) : ViewModel() {
    abstract fun updatePost(postId: String, transform: (Post) -> Post)
    abstract fun removePost(postId: String)
    abstract fun onError(error: AppException)
    abstract fun onInfo(info: String)

    fun toggleLike(post: Post) = act {
        val liked = !post.liked
        updatePost(post.id) { it.copy(liked = liked, likeCount = (it.likeCount + if (liked) 1 else -1).coerceAtLeast(0)) }
        try {
            val result = feed.setLiked(post.id, liked)
            updatePost(post.id) { it.copy(liked = result.liked, likeCount = result.likeCount) }
        } catch (e: Exception) {
            updatePost(post.id) { it.copy(liked = post.liked, likeCount = post.likeCount) }
            throw e
        }
    }

    fun toggleSave(post: Post) = act {
        feed.setSaved(post.id, !post.saved)
        updatePost(post.id) { it.copy(saved = !post.saved) }
    }

    fun hide(post: Post, notInterested: Boolean) = act {
        feed.hide(post.id, notInterested)
        removePost(post.id)
    }

    fun delete(post: Post) = act {
        feed.delete(post.id)
        removePost(post.id)
    }

    fun report(post: Post, reason: ReportReason, details: String) = act {
        moderation.report(ReportTarget.Post, post.id, reason, details)
        onInfo("reported")
    }

    fun blockAuthor(post: Post) = act {
        friends.block(post.author.id)
        removePost(post.id)
    }

    protected fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                onError(e.toAppException())
            }
        }
    }
}

@HiltViewModel
class FeedViewModel @Inject constructor(
    feed: FeedRepository,
    moderation: ModerationRepository,
    friends: FriendsRepository,
    private val profiles: ProfileRepository,
) : PostActionsViewModel(feed, moderation, friends) {
    private val _state = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { runCatching { profiles.interests() }.onSuccess { list -> _state.update { it.copy(interests = list) } } }
    }

    fun setTab(tab: FeedTab) {
        _state.update { it.copy(tab = tab) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(posts = LoadState.Loading) }
            runCatching { feed.stories() }.onSuccess { s -> _state.update { it.copy(stories = s) } }
            try {
                val list = feed.feed(_state.value.tab)
                _state.update { it.copy(posts = LoadState.Success(list), canLoadMore = list.size >= 20) }
            } catch (e: Exception) {
                _state.update { it.copy(posts = LoadState.Failure(e.toAppException())) }
            }
        }
    }

    fun loadMore() {
        val current = (_state.value.posts as? LoadState.Success)?.data ?: return
        if (_state.value.loadingMore || current.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            try {
                val more = feed.feed(_state.value.tab, before = current.last().createdAt)
                _state.update { it.copy(posts = LoadState.Success((current + more).distinctBy { p -> p.id }), canLoadMore = more.size >= 20) }
            } catch (e: Exception) {
                onError(e.toAppException())
            } finally {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    override fun updatePost(postId: String, transform: (Post) -> Post) = _state.update { s ->
        val list = (s.posts as? LoadState.Success)?.data ?: return@update s
        s.copy(posts = LoadState.Success(list.map { if (it.id == postId) transform(it) else it }))
    }

    override fun removePost(postId: String) = _state.update { s ->
        val list = (s.posts as? LoadState.Success)?.data ?: return@update s
        s.copy(posts = LoadState.Success(list.filterNot { it.id == postId }))
    }

    override fun onError(error: AppException) = _state.update { it.copy(error = error) }
    override fun onInfo(info: String) = _state.update { it.copy(info = info) }
    fun consume() = _state.update { it.copy(error = null, info = null) }
}

// ---------------------------------------------------------------------------------------------

data class PostDetailState(
    val post: LoadState<Post> = LoadState.Loading,
    val comments: List<Comment> = emptyList(),
    val replyTo: Comment? = null,
    val sending: Boolean = false,
    val error: AppException? = null,
    val info: String? = null,
    val deleted: Boolean = false,
)

@HiltViewModel
class PostDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    feed: FeedRepository,
    private val moderationRepo: ModerationRepository,
    friends: FriendsRepository,
) : PostActionsViewModel(feed, moderationRepo, friends) {
    private val postId: String = checkNotNull(savedState["postId"])
    private val _state = MutableStateFlow(PostDetailState())
    val state: StateFlow<PostDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            try {
                val post = feed.post(postId)
                _state.update { it.copy(post = LoadState.Success(post), comments = feed.comments(postId)) }
            } catch (e: Exception) {
                _state.update { it.copy(post = LoadState.Failure(e.toAppException())) }
            }
        }
    }

    fun setReply(c: Comment?) = _state.update { it.copy(replyTo = c) }

    fun send(text: String) {
        if (text.isBlank() || _state.value.sending) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            try {
                feed.addComment(postId, text, _state.value.replyTo?.id)
                _state.update { it.copy(replyTo = null, comments = feed.comments(postId)) }
                updatePost(postId) { it.copy(commentCount = it.commentCount + 1) }
            } catch (e: Exception) {
                onError(e.toAppException())
            } finally {
                _state.update { it.copy(sending = false) }
            }
        }
    }

    fun deleteComment(c: Comment) = act {
        feed.deleteComment(c.id)
        _state.update { it.copy(comments = feed.comments(postId)) }
    }

    fun reportComment(c: Comment, reason: ReportReason, details: String) = act {
        moderationRepo.report(ReportTarget.Comment, c.id, reason, details)
        onInfo("reported")
    }

    override fun updatePost(postId: String, transform: (Post) -> Post) = _state.update { s ->
        val p = (s.post as? LoadState.Success)?.data ?: return@update s
        s.copy(post = LoadState.Success(transform(p)))
    }

    override fun removePost(postId: String) = _state.update { it.copy(deleted = true) }
    override fun onError(error: AppException) = _state.update { it.copy(error = error) }
    override fun onInfo(info: String) = _state.update { it.copy(info = info) }
    fun consume() = _state.update { it.copy(error = null, info = null) }
}

// ---------------------------------------------------------------------------------------------

data class ComposeState(
    val kind: PostKind = PostKind.Text,
    val body: String = "",
    val images: List<Uri> = emptyList(),
    val audio: File? = null,
    val audioDurationMs: Int = 0,
    val recording: Boolean = false,
    val interests: Set<String> = emptySet(),
    val catalog: List<Interest> = emptyList(),
    val visibility: PostVisibility = PostVisibility.Public,
    val submitting: Boolean = false,
    val error: AppException? = null,
    val done: Boolean = false,
    // Story mode
    val storyImage: Uri? = null,
) {
    val canSubmit get() = !submitting && when (kind) {
        PostKind.Photo -> images.isNotEmpty()
        PostKind.Voice -> audio != null
        else -> body.isNotBlank()
    }
}

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val feed: FeedRepository,
    private val profiles: ProfileRepository,
    private val recorder: AudioRecorder,
) : ViewModel() {
    private val _state = MutableStateFlow(ComposeState())
    val state: StateFlow<ComposeState> = _state.asStateFlow()
    private val clientId = UUID.randomUUID().toString()

    init {
        viewModelScope.launch { runCatching { profiles.interests() }.onSuccess { list -> _state.update { it.copy(catalog = list) } } }
    }

    fun setKind(kind: PostKind) = _state.update { it.copy(kind = kind) }
    fun setBody(v: String) = _state.update { it.copy(body = v.take(2000)) }
    fun setImages(list: List<Uri>) = _state.update { it.copy(images = list.take(4)) }
    fun setStoryImage(uri: Uri?) = _state.update { it.copy(storyImage = uri) }
    fun setVisibility(v: PostVisibility) = _state.update { it.copy(visibility = v) }
    fun toggleInterest(slug: String) = _state.update {
        it.copy(interests = if (slug in it.interests) it.interests - slug else if (it.interests.size < 5) it.interests + slug else it.interests)
    }

    fun startRecording() {
        if (recorder.start(maxDurationMs = 60_000)) _state.update { it.copy(recording = true) }
        else _state.update { it.copy(error = AppException("upload_failed")) }
    }

    fun stopRecording() {
        val clip = recorder.stop(keep = true, minDurationMs = 1_000)
        _state.update { it.copy(recording = false, audio = clip?.first, audioDurationMs = clip?.second ?: 0) }
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true) }
            try {
                feed.create(
                    NewPost(
                        kind = s.kind,
                        body = s.body,
                        images = if (s.kind == PostKind.Photo) s.images else emptyList(),
                        audio = if (s.kind == PostKind.Voice) s.audio else null,
                        audioDurationMs = if (s.kind == PostKind.Voice) s.audioDurationMs else null,
                        interests = s.interests.toList(),
                        visibility = s.visibility,
                    ),
                    clientId,
                )
                s.audio?.delete()
                _state.update { it.copy(done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }

    fun submitStory() {
        val s = _state.value
        val image = s.storyImage ?: return
        if (s.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true) }
            try {
                feed.createStory(image, s.body, s.visibility)
                _state.update { it.copy(done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toAppException()) }
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        recorder.release()
        super.onCleared()
    }
}

// ---------------------------------------------------------------------------------------------

data class UserPostsState(
    val posts: LoadState<List<Post>> = LoadState.Loading,
    val error: AppException? = null,
    val info: String? = null,
)

@HiltViewModel
class UserPostsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    feed: FeedRepository,
    moderation: ModerationRepository,
    friends: FriendsRepository,
) : PostActionsViewModel(feed, moderation, friends) {
    private val userId: String = checkNotNull(savedState["userId"])
    private val _state = MutableStateFlow(UserPostsState())
    val state: StateFlow<UserPostsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(posts = LoadState.Loading) }
            _state.update {
                it.copy(posts = try {
                    LoadState.Success(feed.userPosts(userId))
                } catch (e: Exception) {
                    LoadState.Failure(e.toAppException())
                })
            }
        }
    }

    override fun updatePost(postId: String, transform: (Post) -> Post) = _state.update { s ->
        val list = (s.posts as? LoadState.Success)?.data ?: return@update s
        s.copy(posts = LoadState.Success(list.map { if (it.id == postId) transform(it) else it }))
    }

    override fun removePost(postId: String) = _state.update { s ->
        val list = (s.posts as? LoadState.Success)?.data ?: return@update s
        s.copy(posts = LoadState.Success(list.filterNot { it.id == postId }))
    }

    override fun onError(error: AppException) = _state.update { it.copy(error = error) }
    override fun onInfo(info: String) = _state.update { it.copy(info = info) }
    fun consume() = _state.update { it.copy(error = null, info = null) }
}
