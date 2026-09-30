package com.sitandtalk.feature.feed

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sitandtalk.core.data.FeedTab
import com.sitandtalk.core.designsystem.Avatar
import com.sitandtalk.core.designsystem.Buckets
import com.sitandtalk.core.designsystem.ConfirmDialog
import com.sitandtalk.core.designsystem.EmptyState
import com.sitandtalk.core.designsystem.ErrorState
import com.sitandtalk.core.designsystem.LoadStateContent
import com.sitandtalk.core.designsystem.LoadingState
import com.sitandtalk.core.designsystem.LocalMediaResolver
import com.sitandtalk.core.designsystem.RemoteImage
import com.sitandtalk.core.designsystem.ReportDialog
import com.sitandtalk.core.designsystem.StPrimaryButton
import com.sitandtalk.core.designsystem.StSecondaryButton
import com.sitandtalk.core.designsystem.StTextButton
import com.sitandtalk.core.designsystem.StTextField
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.StTopBar
import com.sitandtalk.core.designsystem.ToggleChip
import com.sitandtalk.core.designsystem.errorMessage
import com.sitandtalk.core.designsystem.formatDuration
import com.sitandtalk.core.designsystem.interestLabel
import com.sitandtalk.core.model.Comment
import com.sitandtalk.core.model.Interest
import com.sitandtalk.core.model.LoadState
import com.sitandtalk.core.model.Post
import com.sitandtalk.core.model.PostKind
import com.sitandtalk.core.model.PostVisibility
import com.sitandtalk.core.model.ServerTime
import com.sitandtalk.core.model.StoryGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun relativeTime(iso: String?): String {
    val millis = ServerTime.parse(iso)?.toEpochMilli() ?: return ""
    return DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

@Composable
private fun Messages(error: com.sitandtalk.core.model.AppException?, info: String?, snackbar: SnackbarHostState, consume: () -> Unit) {
    val errorText = error?.let { errorMessage(it) }
    val reported = stringResource(com.sitandtalk.core.designsystem.R.string.ds_report_sent)
    LaunchedEffect(errorText, info) {
        val msg = errorText ?: if (info == "reported") reported else null
        if (msg != null) {
            snackbar.showSnackbar(msg)
            consume()
        }
    }
}

@Composable
fun FeedScreen(
    onOpenPost: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onCompose: () -> Unit,
    onCreateStory: () -> Unit,
    onOpenStory: (Int) -> Unit,
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    Messages(state.error, state.info, snackbar, viewModel::consume)
    StoriesHolder.groups = state.stories

    Scaffold(
        topBar = { StTopBar(stringResource(R.string.feed_title)) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onCompose, icon = { Icon(Icons.Rounded.Edit, null) }, text = { Text(stringResource(R.string.feed_new_post)) })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val tabs = listOf(FeedTab.New to R.string.feed_tab_new, FeedTab.Friends to R.string.feed_tab_friends, FeedTab.Interests to R.string.feed_tab_interests,
                FeedTab.Discover to R.string.feed_tab_discover, FeedTab.Saved to R.string.feed_tab_saved)
            PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == state.tab }.coerceAtLeast(0), edgePadding = 12.dp) {
                tabs.forEach { (tab, label) -> Tab(selected = state.tab == tab, onClick = { viewModel.setTab(tab) }, text = { Text(stringResource(label)) }) }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
                item { StoriesRow(state.stories, onCreateStory, onOpenStory) }
                when (val posts = state.posts) {
                    LoadState.Loading -> item { Box(Modifier.fillMaxWidth().height(240.dp)) { LoadingState() } }
                    is LoadState.Failure -> item { Box(Modifier.fillMaxWidth().height(360.dp)) { ErrorState(posts.error, viewModel::refresh) } }
                    is LoadState.Success -> {
                        if (posts.data.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().height(360.dp)) {
                                    EmptyState(
                                        title = stringResource(if (state.tab == FeedTab.Saved) R.string.feed_empty_saved else R.string.feed_empty),
                                        message = if (state.tab == FeedTab.Saved) null else stringResource(R.string.feed_empty_body),
                                        icon = Icons.Rounded.Explore,
                                        actionLabel = if (state.tab == FeedTab.Saved) null else stringResource(R.string.feed_new_post),
                                        onAction = if (state.tab == FeedTab.Saved) null else onCompose,
                                    )
                                }
                            }
                        }
                        items(posts.data, key = { it.id }) { post ->
                            PostCard(post, state.interests, viewModel, onOpen = { onOpenPost(post.id) }, onAuthor = { onOpenProfile(post.author.id) })
                            HorizontalDivider()
                        }
                        if (state.canLoadMore) item { StTextButton(stringResource(R.string.feed_load_more), viewModel::loadMore, Modifier.padding(16.dp)) }
                    }
                }
            }
        }
    }
}

/** Holds the last loaded stories for the full-screen viewer (same process, same data). */
object StoriesHolder {
    var groups: List<StoryGroup> = emptyList()
}

@Composable
private fun StoriesRow(groups: List<StoryGroup>, onCreate: () -> Unit, onOpen: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable(onClick = onCreate)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(60.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null) }
                }
                Text(stringResource(R.string.feed_add_story), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        items(groups.size) { index ->
            val g = groups[index]
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable { onOpen(index) }) {
                Avatar(g.author.avatarPath, g.author.displayName, size = 60.dp, modifier = Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape))
                Text(g.author.displayName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PostCard(post: Post, interests: List<Interest>, vm: PostActionsViewModel, onOpen: () -> Unit, onAuthor: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = onAuthor), verticalAlignment = Alignment.CenterVertically) {
                Avatar(post.author.avatarPath, post.author.displayName, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(post.author.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            relativeTime(post.createdAt),
                            if (post.visibility == PostVisibility.Friends) stringResource(R.string.feed_friends_only) else null,
                            when (post.moderation) {
                                "pending" -> stringResource(R.string.feed_pending)
                                "removed" -> stringResource(R.string.feed_removed)
                                else -> null
                            },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (post.isMine) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.feed_delete)) }, onClick = { menu = false; confirmDelete = true })
                    } else {
                        DropdownMenuItem(text = { Text(stringResource(R.string.feed_hide)) }, onClick = { menu = false; vm.hide(post, false) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.feed_not_interested)) }, onClick = { menu = false; vm.hide(post, true) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.feed_report)) }, onClick = { menu = false; report = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.feed_block_author)) }, onClick = { menu = false; vm.blockAuthor(post) })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (post.kind == PostKind.Question || post.kind == PostKind.Status) {
            Text(stringResource(if (post.kind == PostKind.Question) R.string.feed_question else R.string.feed_status),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (post.body.isNotBlank()) Text(post.body, style = MaterialTheme.typography.bodyLarge)
        if (post.media.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            post.media.forEach { m ->
                RemoteImage(Buckets.POST_MEDIA, m.path, null, Modifier.fillMaxWidth().aspectRatio(
                    if (m.width != null && m.height != null && m.height!! > 0) (m.width!!.toFloat() / m.height!!).coerceIn(0.6f, 1.8f) else 1.2f,
                ).padding(vertical = 2.dp), ContentScale.Crop)
            }
        }
        val audioPath = post.audioPath
        if (post.kind == PostKind.Voice && audioPath != null) AudioClip(audioPath, post.audioDurationMs ?: 0)
        if (post.interests.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                post.interests.forEach { Text("#" + (interestLabel(interests, it) ?: it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.toggleLike(post) }) {
                Icon(if (post.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(if (post.liked) R.string.feed_unlike else R.string.feed_like),
                    tint = if (post.liked) StTheme.extra.coral else MaterialTheme.colorScheme.onSurface)
            }
            Text(post.likeCount.toString())
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = onOpen) { Icon(Icons.Rounded.ChatBubbleOutline, stringResource(R.string.feed_comment)) }
            Text(post.commentCount.toString())
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                val link = "sitandtalk://post/${post.id}"
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.feed_share_text, link)), null))
            }) { Icon(Icons.Rounded.Share, stringResource(R.string.feed_share)) }
            IconButton(onClick = { vm.toggleSave(post) }) {
                Icon(if (post.saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, stringResource(if (post.saved) R.string.feed_unsave else R.string.feed_save))
            }
        }
    }
    if (report) ReportDialog(onSubmit = { r, d -> vm.report(post, r, d); report = false }, onDismiss = { report = false })
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.feed_delete), stringResource(R.string.feed_delete_confirm), stringResource(R.string.feed_delete),
            onConfirm = { vm.delete(post); confirmDelete = false }, onDismiss = { confirmDelete = false }, destructive = true)
    }
}

@Composable
private fun AudioClip(path: String, durationMs: Int) {
    val resolver = LocalMediaResolver.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(path) { onDispose { player?.release() } }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
            IconButton(onClick = {
                val p = player
                when {
                    p != null && playing -> { p.pause(); playing = false }
                    p != null -> { p.start(); playing = true }
                    else -> scope.launch {
                        val url = resolver.signedUrl(Buckets.POST_MEDIA, path) ?: return@launch
                        val mp = MediaPlayer()
                        runCatching {
                            mp.setDataSource(url)
                            mp.setOnPreparedListener { it.start(); playing = true }
                            mp.setOnCompletionListener { playing = false }
                            mp.prepareAsync()
                            player = mp
                        }.onFailure { mp.release() }
                    }
                }
            }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null) }
            Text(stringResource(R.string.post_voice) + " · " + formatDuration(durationMs / 1000L))
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun PostDetailScreen(onBack: () -> Unit, onOpenProfile: (String) -> Unit, viewModel: PostDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    Messages(state.error, state.info, snackbar, viewModel::consume)
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    var draft by rememberSaveable { mutableStateOf("") }
    var reportComment by remember { mutableStateOf<Comment?>(null) }
    Scaffold(topBar = { StTopBar(stringResource(R.string.post_title), onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LoadStateContent(state.post, viewModel::load, Modifier.weight(1f)) { post ->
                LazyColumn(Modifier.weight(1f)) {
                    item { PostCard(post, emptyList(), viewModel, onOpen = {}, onAuthor = { onOpenProfile(post.author.id) }) }
                    item { HorizontalDivider() }
                    if (state.comments.isEmpty()) item { Text(stringResource(R.string.post_comments_empty), Modifier.padding(16.dp), color = StTheme.extra.textSecondary) }
                    items(state.comments, key = { it.id }) { c ->
                        CommentRow(c, onReply = { viewModel.setReply(c) }, onDelete = { viewModel.deleteComment(c) }, onReport = { reportComment = c },
                            onAuthor = { onOpenProfile(c.author.id) })
                    }
                }
            }
            state.replyTo?.let { r ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.post_replying) + ": " + r.author.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { viewModel.setReply(null) }) { Icon(Icons.Rounded.Close, null) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it.take(1000) }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.post_comment_hint)) },
                    shape = RoundedCornerShape(20.dp), maxLines = 4)
                IconButton(onClick = { viewModel.send(draft); draft = "" }, enabled = draft.isNotBlank() && !state.sending) {
                    Icon(Icons.AutoMirrored.Rounded.Send, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    reportComment?.let { c -> ReportDialog(onSubmit = { r, d -> viewModel.reportComment(c, r, d); reportComment = null }, onDismiss = { reportComment = null }) }
}

@Composable
private fun CommentRow(c: Comment, onReply: () -> Unit, onDelete: () -> Unit, onReport: () -> Unit, onAuthor: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = if (c.parentId != null) 48.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Avatar(c.author.avatarPath, c.author.displayName, size = 32.dp, modifier = Modifier.clickable(onClick = onAuthor))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.author.displayName + " · " + relativeTime(c.createdAt), style = MaterialTheme.typography.labelMedium)
            Text(if (c.deleted) stringResource(R.string.post_comment_deleted) else c.body.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            Row {
                if (!c.deleted) StTextButton(stringResource(R.string.post_reply), onReply)
                if (c.isMine && !c.deleted) StTextButton(stringResource(R.string.feed_delete), onDelete)
                if (!c.isMine && !c.deleted) StTextButton(stringResource(R.string.feed_report), onReport)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposePostScreen(onBack: () -> Unit, onPosted: () -> Unit, viewModel: ComposeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.let { errorMessage(it) }
    LaunchedEffect(errorText) { if (errorText != null) { snackbar.showSnackbar(errorText); viewModel.consumeError() } }
    LaunchedEffect(state.done) { if (state.done) onPosted() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris: List<Uri> -> viewModel.setImages(uris) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) viewModel.startRecording() }

    Scaffold(topBar = { StTopBar(stringResource(R.string.compose_title), onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.compose_kind), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PostKind.Text to R.string.compose_text, PostKind.Photo to R.string.compose_photo, PostKind.Voice to R.string.compose_voice,
                    PostKind.Question to R.string.feed_question, PostKind.Status to R.string.feed_status).forEach { (k, label) ->
                    ToggleChip(stringResource(label), state.kind == k, { viewModel.setKind(k) })
                }
            }
            Spacer(Modifier.height(8.dp))
            StTextField(state.body, viewModel::setBody, stringResource(R.string.compose_body), singleLine = false, minLines = 4, maxLength = 2000)
            when (state.kind) {
                PostKind.Photo -> {
                    StSecondaryButton(stringResource(R.string.compose_add_photos), { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    if (state.images.isNotEmpty()) Text(stringResource(R.string.compose_photos_selected, state.images.size))
                }
                PostKind.Voice -> {
                    if (state.recording) {
                        Text(stringResource(R.string.compose_recording), color = MaterialTheme.colorScheme.error)
                        StPrimaryButton(stringResource(R.string.compose_stop), viewModel::stopRecording, icon = Icons.Rounded.Stop)
                    } else {
                        StSecondaryButton(stringResource(R.string.compose_record), { mic.launch(android.Manifest.permission.RECORD_AUDIO) }, icon = Icons.Rounded.Mic)
                        if (state.audio != null) Text(stringResource(R.string.compose_recorded, formatDuration(state.audioDurationMs / 1000L)))
                    }
                }
                else -> Unit
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.compose_topics), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.catalog.forEach { i -> ToggleChip(interestLabel(i), i.slug in state.interests, { viewModel.toggleInterest(i.slug) }) }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.compose_visibility), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip(stringResource(R.string.compose_public), state.visibility == PostVisibility.Public, { viewModel.setVisibility(PostVisibility.Public) })
                ToggleChip(stringResource(R.string.compose_friends), state.visibility == PostVisibility.Friends, { viewModel.setVisibility(PostVisibility.Friends) })
            }
            Spacer(Modifier.height(16.dp))
            StPrimaryButton(stringResource(if (state.submitting) R.string.compose_uploading else R.string.compose_submit), viewModel::submit,
                enabled = state.canSubmit, loading = state.submitting, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun CreateStoryScreen(onBack: () -> Unit, onPosted: () -> Unit, viewModel: ComposeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorText = state.error?.let { errorMessage(it) }
    LaunchedEffect(errorText) { if (errorText != null) { snackbar.showSnackbar(errorText); viewModel.consumeError() } }
    LaunchedEffect(state.done) { if (state.done) onPosted() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? -> viewModel.setStoryImage(uri) }
    Scaffold(topBar = { StTopBar(stringResource(R.string.story_title), onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
            StSecondaryButton(stringResource(R.string.story_pick), { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            state.storyImage?.let {
                coil3.compose.AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.75f).padding(vertical = 12.dp))
            }
            StTextField(state.body, viewModel::setBody, stringResource(R.string.story_caption), maxLength = 200)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                ToggleChip(stringResource(R.string.compose_friends), state.visibility == PostVisibility.Friends, { viewModel.setVisibility(PostVisibility.Friends) })
                ToggleChip(stringResource(R.string.compose_public), state.visibility == PostVisibility.Public, { viewModel.setVisibility(PostVisibility.Public) })
            }
            Text(stringResource(R.string.story_expires), style = MaterialTheme.typography.bodySmall, color = StTheme.extra.textSecondary)
            Spacer(Modifier.height(12.dp))
            StPrimaryButton(stringResource(R.string.story_share), viewModel::submitStory, enabled = state.storyImage != null, loading = state.submitting,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun StoryViewerScreen(startIndex: Int, onClose: () -> Unit, onDeleted: () -> Unit, onReport: (String) -> Unit, onDelete: (String) -> Unit) {
    val groups = StoriesHolder.groups
    var groupIndex by rememberSaveable { mutableIntStateOf(startIndex) }
    var itemIndex by rememberSaveable { mutableIntStateOf(0) }
    val group = groups.getOrNull(groupIndex)
    if (group == null || group.items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val item = group.items[itemIndex.coerceIn(0, group.items.lastIndex)]
    val advance = {
        if (itemIndex < group.items.lastIndex) itemIndex++
        else if (groupIndex < groups.lastIndex) { groupIndex++; itemIndex = 0 }
        else onClose()
    }
    LaunchedEffect(groupIndex, itemIndex) {
        delay(6_000)
        advance()
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { advance() }) {
        RemoteImage(Buckets.STORIES, item.mediaPath, item.caption.ifBlank { null }, Modifier.fillMaxSize(), ContentScale.Fit)
        Row(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Avatar(group.author.avatarPath, group.author.displayName, size = 32.dp)
            Spacer(Modifier.width(8.dp))
            Text(group.author.displayName + " · " + relativeTime(item.createdAt), color = Color.White, modifier = Modifier.weight(1f))
            if (group.isMine) {
                StTextButton(stringResource(R.string.story_delete), { onDelete(item.id); onDeleted() })
            } else {
                StTextButton(stringResource(R.string.feed_report), { onReport(item.id) })
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.story_close), tint = Color.White) }
        }
        if (item.caption.isNotBlank()) {
            Surface(color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
                Text(item.caption, color = Color.White, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
fun UserPostsScreen(title: String, onBack: () -> Unit, onOpenPost: (String) -> Unit, viewModel: UserPostsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    Messages(state.error, state.info, snackbar, viewModel::consume)
    Scaffold(topBar = { StTopBar(title, onBack = onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LoadStateContent(state.posts, viewModel::refresh, Modifier.padding(padding)) { posts ->
            if (posts.isEmpty()) {
                EmptyState(stringResource(R.string.feed_empty), Modifier.padding(padding))
            } else {
                LazyColumn(Modifier.padding(padding)) {
                    items(posts, key = { it.id }) { p ->
                        PostCard(p, emptyList(), viewModel, onOpen = { onOpenPost(p.id) }, onAuthor = {})
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
