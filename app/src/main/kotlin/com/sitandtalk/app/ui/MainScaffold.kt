package com.sitandtalk.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sitandtalk.app.AppLink
import com.sitandtalk.app.AppStateViewModel
import com.sitandtalk.app.R
import com.sitandtalk.core.designsystem.StColors
import com.sitandtalk.core.designsystem.StTheme
import com.sitandtalk.core.designsystem.interestLabel
import com.sitandtalk.feature.call.CallScreen
import com.sitandtalk.feature.chat.ChatScreen
import com.sitandtalk.feature.chat.ConversationsScreen
import com.sitandtalk.feature.chat.NewGroupScreen
import com.sitandtalk.feature.feed.ComposePostScreen
import com.sitandtalk.feature.feed.CreateStoryScreen
import com.sitandtalk.feature.feed.FeedScreen
import com.sitandtalk.feature.feed.PostDetailScreen
import com.sitandtalk.feature.feed.StoryViewerScreen
import com.sitandtalk.feature.feed.UserPostsScreen
import com.sitandtalk.feature.friends.FriendsScreen
import com.sitandtalk.feature.matching.TalkScreen
import com.sitandtalk.feature.moderation.AdminScreen
import com.sitandtalk.feature.moderation.SafetyScreen
import com.sitandtalk.feature.notifications.NotificationsScreen
import com.sitandtalk.feature.profile.EditProfileScreen
import com.sitandtalk.feature.profile.MyProfileScreen
import com.sitandtalk.feature.profile.UserProfileScreen
import com.sitandtalk.feature.rooms.CreateRoomScreen
import com.sitandtalk.feature.rooms.RoomJoinRequest
import com.sitandtalk.feature.rooms.RoomScreen
import com.sitandtalk.feature.rooms.RoomsScreen
import com.sitandtalk.feature.settings.LegalLink
import com.sitandtalk.feature.settings.SettingsScreen
import com.sitandtalk.feature.wallet.WalletScreen

private object Routes {
    const val FEED = "feed"
    const val ROOMS = "rooms"
    const val TALK = "talk"
    const val CHATS = "chats"
    const val ME = "me"
    const val CALL = "call"
    const val ROOM = "room"
    const val NOTIFICATIONS = "notifications"
    const val CREATE_ROOM = "rooms/create?plan={plan}&eventId={eventId}&eventTitle={eventTitle}"
    const val CHAT = "chat/{conversationId}"
    const val NEW_GROUP = "chats/new-group"
    const val FRIENDS = "friends?tab={tab}"
    const val POST = "post/{postId}"
    const val COMPOSE = "compose"
    const val STORY_CREATE = "story/create"
    const val STORY_VIEW = "story/{index}"
    const val USER = "user/{userId}"
    const val USER_POSTS = "user/{userId}/posts?title={title}"
    const val EDIT_PROFILE = "profile/edit"
    const val WALLET = "wallet"
    const val SETTINGS = "settings"
    const val SAFETY = "safety"
    const val ADMIN = "admin"

    fun chat(id: String) = "chat/$id"
    fun post(id: String) = "post/$id"
    fun user(id: String) = "user/$id"
    fun userPosts(id: String, title: String) = "user/$id/posts?title=${Uri.encode(title)}"
    fun friends(tab: String? = null) = if (tab == null) "friends" else "friends?tab=$tab"
    fun story(index: Int) = "story/$index"
    fun createRoom(plan: Boolean = false, eventId: String? = null, eventTitle: String? = null) = buildString {
        append("rooms/create?plan=").append(plan)
        if (eventId != null) append("&eventId=").append(eventId)
        if (eventTitle != null) append("&eventTitle=").append(Uri.encode(eventTitle))
    }
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.FEED, R.string.tab_discover, Icons.Rounded.Explore),
    Tab(Routes.ROOMS, R.string.tab_rooms, Icons.Rounded.GraphicEq),
    Tab(Routes.TALK, R.string.tab_talk, Icons.Rounded.RecordVoiceOver),
    Tab(Routes.CHATS, R.string.tab_messages, Icons.Rounded.ChatBubble),
    Tab(Routes.ME, R.string.tab_profile, Icons.Rounded.Person),
)

private fun NavHostController.openTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun MainScaffold(vm: AppStateViewModel) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val unread by vm.unreadNotifications.collectAsStateWithLifecycle()
    val catalog by vm.interestCatalog.collectAsStateWithLifecycle()
    val pendingLink by vm.pendingLink.collectAsStateWithLifecycle()
    val openCall by vm.openCall.collectAsStateWithLifecycle()
    val activeCall by vm.activeCallState.collectAsStateWithLifecycle()
    val activeRoom by vm.activeRoomState.collectAsStateWithLifecycle()
    var joinRequest by remember { mutableStateOf<RoomJoinRequest?>(null) }
    val config = vm.config

    val interestText: @Composable (String) -> String = { slug -> interestLabel(catalog, slug) ?: slug }

    // A new call session (match accepted, call placed or answered, restored) always opens the call screen.
    val sessionId = activeCall?.call?.sessionId
    LaunchedEffect(sessionId) {
        if (sessionId != null && nav.currentDestination?.route != Routes.CALL) nav.navigate(Routes.CALL) { launchSingleTop = true }
    }
    LaunchedEffect(openCall) {
        if (openCall) {
            vm.consumeOpenCall()
            if (nav.currentDestination?.route != Routes.CALL) nav.navigate(Routes.CALL) { launchSingleTop = true }
        }
    }
    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        vm.consumeLink()
        when (link) {
            is AppLink.Room -> {
                nav.openTab(Routes.ROOMS)
                joinRequest = RoomJoinRequest(link.roomId, link.inviteCode)
            }
            is AppLink.Post -> nav.navigate(Routes.post(link.postId))
            is AppLink.Chat -> nav.navigate(Routes.chat(link.conversationId))
            is AppLink.Profile -> nav.navigate(Routes.user(link.userId))
            AppLink.FriendRequests -> nav.navigate(Routes.friends("requests"))
            AppLink.Events -> nav.openTab(Routes.ROOMS)
            AppLink.Notifications -> nav.navigate(Routes.NOTIFICATIONS) { launchSingleTop = true }
            AppLink.ActiveRoom -> if (activeRoom != null) nav.navigate(Routes.ROOM) { launchSingleTop = true }
            AppLink.ActiveCall, is AppLink.AuthCallback -> Unit
        }
    }

    val showTabs = tabs.any { it.route == route }
    val ongoing: Pair<String, () -> Unit>? = when {
        activeCall != null && activeCall?.call?.isEnded == false && route != Routes.CALL ->
            stringResource(R.string.ongoing_call) to { nav.navigate(Routes.CALL) { launchSingleTop = true } }
        activeRoom != null && activeRoom?.removed == false && route != Routes.ROOM ->
            stringResource(R.string.ongoing_room, activeRoom?.room?.title.orEmpty()) to { nav.navigate(Routes.ROOM) { launchSingleTop = true } }
        else -> null
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = route == tab.route
                        val center = tab.route == Routes.TALK
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.openTab(tab.route) },
                            icon = {
                                if (center) {
                                    Box(
                                        Modifier.size(44.dp).background(StTheme.extra.talkGradient, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(tab.icon, contentDescription = null, tint = StColors.White) }
                                } else {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            ongoing?.let { (text, open) -> OngoingBar(text, open) }
            // The ongoing bar already covers the status bar; screens below must not pad for it again.
            val hostModifier = if (ongoing != null) Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars) else Modifier.weight(1f)
            NavHost(nav, startDestination = Routes.TALK, modifier = hostModifier) {
                composable(Routes.TALK) {
                    TalkScreen(
                        onOpenNotifications = { nav.navigate(Routes.NOTIFICATIONS) },
                        unreadNotifications = unread,
                        interestLabel = interestText,
                    )
                }
                composable(Routes.FEED) {
                    FeedScreen(
                        onOpenPost = { nav.navigate(Routes.post(it)) },
                        onOpenProfile = { nav.navigate(Routes.user(it)) },
                        onCompose = { nav.navigate(Routes.COMPOSE) },
                        onCreateStory = { nav.navigate(Routes.STORY_CREATE) },
                        onOpenStory = { nav.navigate(Routes.story(it)) },
                    )
                }
                composable(Routes.ROOMS) {
                    RoomsScreen(
                        onOpenRoom = { nav.navigate(Routes.ROOM) { launchSingleTop = true } },
                        onCreateRoom = { nav.navigate(Routes.createRoom()) },
                        onPlanEvent = { nav.navigate(Routes.createRoom(plan = true)) },
                        onStartEventRoom = { nav.navigate(Routes.createRoom(eventId = it.id, eventTitle = it.title)) },
                        joinRequest = joinRequest,
                        onJoinRequestHandled = { joinRequest = null },
                    )
                }
                composable(Routes.CHATS) {
                    ConversationsScreen(
                        onOpenChat = { nav.navigate(Routes.chat(it)) },
                        onOpenFriends = { nav.navigate(Routes.friends()) },
                        onNewGroup = { nav.navigate(Routes.NEW_GROUP) },
                    )
                }
                composable(Routes.ME) {
                    MyProfileScreen(
                        onEdit = { nav.navigate(Routes.EDIT_PROFILE) },
                        onFriends = { nav.navigate(Routes.friends()) },
                        onPosts = { id, title -> nav.navigate(Routes.userPosts(id, title)) },
                        onWallet = { nav.navigate(Routes.WALLET) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                        onAdmin = { nav.navigate(Routes.ADMIN) },
                        onSafety = { nav.navigate(Routes.SAFETY) },
                    )
                }
                composable(Routes.CALL) {
                    CallScreen(
                        onClose = { if (!nav.popBackStack()) nav.openTab(Routes.TALK) },
                        onNewCall = { nav.openTab(Routes.TALK) },
                        onOpenChat = { nav.navigate(Routes.chat(it)) { popUpTo(Routes.CALL) { inclusive = true } } },
                        interestLabel = interestText,
                    )
                }
                composable(Routes.ROOM) {
                    RoomScreen(
                        onBack = { if (!nav.popBackStack()) nav.openTab(Routes.ROOMS) },
                        onOpenProfile = { nav.navigate(Routes.user(it)) },
                    )
                }
                composable(Routes.NOTIFICATIONS) {
                    NotificationsScreen(
                        onBack = { nav.popBackStack() },
                        onOpen = { n ->
                            val target = n.entityId
                            when (n.kind) {
                                "friend_request" -> nav.navigate(Routes.friends("requests"))
                                "friend_accepted", "mutual_match" -> target?.let { nav.navigate(Routes.user(it)) }
                                "comment", "reply" -> target?.let { nav.navigate(Routes.post(it)) }
                                "room_invite" -> target?.let {
                                    nav.openTab(Routes.ROOMS)
                                    joinRequest = RoomJoinRequest(it, null)
                                }
                                "event_reminder" -> nav.openTab(Routes.ROOMS)
                                "incoming_call" -> vm.handleUri(AppLink.uriFor(AppLink.ActiveCall), null)
                                else -> Unit
                            }
                        },
                    )
                }
                composable(
                    Routes.CREATE_ROOM,
                    arguments = listOf(
                        navArgument("plan") { type = NavType.BoolType; defaultValue = false },
                        navArgument("eventId") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("eventTitle") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { e ->
                    val args = e.arguments
                    CreateRoomScreen(
                        onBack = { nav.popBackStack() },
                        onCreated = { nav.navigate(Routes.ROOM) { popUpTo(Routes.ROOMS); launchSingleTop = true } },
                        planEvent = args?.getBoolean("plan") ?: false,
                        eventId = args?.getString("eventId"),
                        eventTitle = args?.getString("eventTitle"),
                    )
                }
                composable(Routes.CHAT) {
                    ChatScreen(onBack = { nav.popBackStack() }, onOpenProfile = { nav.navigate(Routes.user(it)) })
                }
                composable(Routes.NEW_GROUP) {
                    NewGroupScreen(
                        onBack = { nav.popBackStack() },
                        onCreated = { nav.navigate(Routes.chat(it)) { popUpTo(Routes.NEW_GROUP) { inclusive = true } } },
                    )
                }
                composable(
                    Routes.FRIENDS,
                    arguments = listOf(navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) {
                    FriendsScreen(
                        onBack = { nav.popBackStack() },
                        onOpenProfile = { nav.navigate(Routes.user(it)) },
                        onOpenChat = { nav.navigate(Routes.chat(it)) },
                        onCallStarted = { nav.navigate(Routes.CALL) { launchSingleTop = true } },
                    )
                }
                composable(Routes.POST) {
                    PostDetailScreen(onBack = { nav.popBackStack() }, onOpenProfile = { nav.navigate(Routes.user(it)) })
                }
                composable(Routes.COMPOSE) {
                    ComposePostScreen(onBack = { nav.popBackStack() }, onPosted = { nav.popBackStack() })
                }
                composable(Routes.STORY_CREATE) {
                    CreateStoryScreen(onBack = { nav.popBackStack() }, onPosted = { nav.popBackStack() })
                }
                composable(Routes.STORY_VIEW, arguments = listOf(navArgument("index") { type = NavType.IntType })) { e ->
                    StoryViewerScreen(startIndex = e.arguments?.getInt("index") ?: 0, onClose = { nav.popBackStack() })
                }
                composable(Routes.USER) {
                    UserProfileScreen(
                        onBack = { nav.popBackStack() },
                        onOpenChat = { nav.navigate(Routes.chat(it)) },
                        onCallStarted = { nav.navigate(Routes.CALL) { launchSingleTop = true } },
                        onPosts = { id, title -> nav.navigate(Routes.userPosts(id, title)) },
                    )
                }
                composable(
                    Routes.USER_POSTS,
                    arguments = listOf(navArgument("title") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { e ->
                    UserPostsScreen(
                        title = e.arguments?.getString("title").orEmpty(),
                        onBack = { nav.popBackStack() },
                        onOpenPost = { nav.navigate(Routes.post(it)) },
                    )
                }
                composable(Routes.EDIT_PROFILE) { EditProfileScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.WALLET) { WalletScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.SAFETY) { SafetyScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.ADMIN) { AdminScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { nav.popBackStack() },
                        onBlocked = { nav.navigate(Routes.friends("blocked")) },
                        onOpenLegal = { link ->
                            openUrl(context, when (link) {
                                LegalLink.Terms -> config.termsUrl
                                LegalLink.Privacy -> config.privacyUrl
                                LegalLink.Community -> config.communityUrl
                                LegalLink.Support -> config.supportUrl
                            })
                        },
                        // Signing out flips the app state to the sign-in screen; nothing to navigate here.
                        onSignedOut = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun OngoingBar(text: String, onOpen: () -> Unit) {
    Surface(
        color = StColors.Success,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen),
    ) {
        Row(
            Modifier.statusBarsPadding().heightIn(min = 48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Call, contentDescription = null, tint = StColors.Ink)
            Spacer(Modifier.width(10.dp))
            Text(text, color = StColors.Ink, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.ongoing_return), color = StColors.Ink, style = MaterialTheme.typography.labelLarge)
        }
    }
}
