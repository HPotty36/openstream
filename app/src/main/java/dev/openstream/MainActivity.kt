package dev.openstream

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.lifecycle.lifecycleScope
import dev.openstream.download.SavedFile
import dev.openstream.settings.LeaveMode
import dev.openstream.watch.LocalScreen
import kotlinx.coroutines.flow.combine
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.VideoLibrary
import dev.openstream.channel.ChannelScreen
import dev.openstream.feed.FeedScreen
import dev.openstream.library.ItemList
import dev.openstream.library.LibraryScreen
import kotlinx.coroutines.launch
import dev.openstream.platform.Channel
import dev.openstream.platform.StreamItem
import dev.openstream.search.SearchScreen
import dev.openstream.search.SearchState
import dev.openstream.settings.SettingsScreen
import dev.openstream.ui.AppTheme
import dev.openstream.ui.windowLayout
import dev.openstream.watch.WatchScreen

private val PLAYER_KEYS = setOf(
    KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_F,
    KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        setContent {
            val mode by app.settings.themeMode.collectAsState()
            AppTheme(mode) { Root(app) }
        }
        // From Android 12 the system itself shrinks the app into the small window when it is left,
        // if told beforehand that it may; so the permission follows the playing state.
        lifecycleScope.launch {
            combine(app.playing, app.settings.leaveMode) { playing, mode -> playing && mode == LeaveMode.PIP }
                .collect { setPictureInPictureParams(pipParams(it)) }
        }
    }

    private fun pipParams(auto: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).apply {
            if (Build.VERSION.SDK_INT >= 31) {
                setAutoEnterEnabled(auto)
                setSeamlessResizeEnabled(true)
            }
        }.build()

    // Before Android 12 the app has to shrink itself at the moment it is left.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val app = application as App
        if (Build.VERSION.SDK_INT < 31 && app.playing.value && app.settings.leaveMode.value == LeaveMode.PIP) {
            runCatching { enterPictureInPictureMode(pipParams(false)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        (application as App).inPip.value = isInPictureInPictureMode
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val app = application as App
        val plain = !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
        if (app.watching && plain && event.keyCode in PLAYER_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN) app.playerKeys.tryEmit(event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    FEED("구독", Icons.Outlined.Subscriptions),
    SEARCH("검색", Icons.Outlined.Search),
    LIBRARY("보관함", Icons.Outlined.VideoLibrary),
    SETTINGS("설정", Icons.Outlined.Settings),
}

private sealed interface Screen {
    data class OfChannel(val channel: Channel) : Screen
    data class Watch(val item: StreamItem) : Screen
    data class Local(val file: SavedFile) : Screen
}

@Composable
private fun Root(app: App) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.FEED) }
    val search = remember { SearchState(app.platforms, scope) }
    // Screens opened on top of the tabs. Each stays alive under the next one, so going back
    // returns to the same list at the same place.
    val stack = remember { mutableStateListOf<Screen>() }
    BackHandler(stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    SideEffect { app.watching = stack.lastOrNull().let { it is Screen.Watch || it is Screen.Local } }
    val pip by app.inPip.collectAsState()
    val leaveMode by app.settings.leaveMode.collectAsState()
    // Leaving the search field focused under a video would send the shortcut keys into it.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(stack.size) { if (stack.isNotEmpty()) focus.clearFocus(force = true) }

    val tabContent: @Composable (Modifier) -> Unit = { modifier ->
        when (tab) {
            Tab.FEED -> FeedScreen(app.library, app.feed, onItem = { stack.add(Screen.Watch(it)) }, modifier = modifier)
            Tab.SEARCH -> SearchScreen(
                search,
                onChannel = { stack.add(Screen.OfChannel(it)) },
                onItem = { stack.add(Screen.Watch(it)) },
                modifier = modifier,
            )
            Tab.LIBRARY -> LibraryScreen(
                app.library, app.downloads,
                onChannel = { stack.add(Screen.OfChannel(it)) },
                onItem = { stack.add(Screen.Watch(it)) },
                onLocal = { stack.add(Screen.Local(it)) },
                modifier = modifier,
            )
            Tab.SETTINGS -> SettingsScreen(app.settings, app.library, onImported = app.feed::refresh, modifier = modifier)
        }
    }

    Surface(Modifier.fillMaxSize()) {
        if (windowLayout().tablet) {
            // Tablet, desktop window or external monitor: the tabs go to the side.
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    Tab.entries.forEach {
                        NavigationRailItem(
                            selected = tab == it,
                            onClick = { tab = it },
                            icon = { Icon(it.icon, contentDescription = null) },
                            label = { Text(it.label) },
                        )
                    }
                }
                tabContent(Modifier.weight(1f).fillMaxHeight().statusBarsPadding())
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach {
                            NavigationBarItem(
                                selected = tab == it,
                                onClick = { tab = it },
                                icon = { Icon(it.icon, contentDescription = null) },
                                label = { Text(it.label) },
                            )
                        }
                    }
                },
            ) { padding -> tabContent(Modifier.padding(padding).fillMaxSize()) }
        }
        stack.forEachIndexed { index, screen ->
            key(index, screen) {
                Surface(Modifier.fillMaxSize()) {
                    when (screen) {
                        is Screen.OfChannel -> ChannelScreen(
                            app.platform(screen.channel.platform) ?: app.youtube, screen.channel, app.library,
                            onSubscribed = app.feed::add,
                            onBack = { stack.removeAt(stack.lastIndex) },
                            onItem = { stack.add(Screen.Watch(it)) },
                        )
                        is Screen.Watch -> WatchScreen(
                            app.platform(screen.item.platform) ?: app.youtube, screen.item, app.settings, app.translator,
                            active = index == stack.lastIndex,
                            pip = pip,
                            keys = app.playerKeys,
                            onPlaying = { app.playing.value = it },
                            library = app.library,
                            onProgress = { item, position ->
                                app.scope.launch { app.library.put(ItemList.HISTORY, listOf(item), position) }
                            },
                            onDownload = { item, option ->
                                app.downloads.start(item, option)
                                android.widget.Toast.makeText(app, "다운로드를 시작했습니다. 보관함에서 진행 상황을 볼 수 있습니다.", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            skipSegments = { item ->
                                val videoId = dev.openstream.sponsor.youtubeVideoId(item.id)
                                    ?.takeIf { item.platform == dev.openstream.platform.PlatformId.YOUTUBE && app.settings.sponsorBlock.value }
                                if (videoId == null) emptyList() else app.sponsorBlock.segments(videoId)
                            },
                            onChannel = { stack.add(Screen.OfChannel(it)) },
                        )
                        is Screen.Local -> LocalScreen(
                            screen.file.uri, screen.file.title,
                            active = index == stack.lastIndex,
                            pip = pip,
                            leaveMode = leaveMode,
                            keys = app.playerKeys,
                            onPlaying = { app.playing.value = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
