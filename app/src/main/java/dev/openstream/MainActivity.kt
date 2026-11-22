package dev.openstream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.openstream.channel.ChannelScreen
import dev.openstream.platform.Channel
import dev.openstream.platform.StreamItem
import dev.openstream.search.SearchScreen
import dev.openstream.search.SearchState
import dev.openstream.settings.SettingsScreen
import dev.openstream.ui.AppTheme
import dev.openstream.watch.WatchScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        setContent {
            val mode by app.settings.themeMode.collectAsState()
            AppTheme(mode) { Root(app) }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    FEED("구독", Icons.Outlined.Subscriptions),
    SEARCH("검색", Icons.Outlined.Search),
    SETTINGS("설정", Icons.Outlined.Settings),
}

private sealed interface Screen {
    data class OfChannel(val channel: Channel) : Screen
    data class Watch(val item: StreamItem) : Screen
}

@Composable
private fun Root(app: App) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.FEED) }
    val search = remember { SearchState(app.youtube, scope) }
    // Screens opened on top of the tabs. Each stays alive under the next one, so going back
    // returns to the same list at the same place.
    val stack = remember { mutableStateListOf<Screen>() }
    BackHandler(stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    Box(Modifier.fillMaxSize()) {
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
        ) { padding ->
            val modifier = Modifier.padding(padding).fillMaxSize()
            when (tab) {
                Tab.FEED -> Empty("구독한 채널이 없습니다", modifier)
                Tab.SEARCH -> SearchScreen(
                    search,
                    onChannel = { stack.add(Screen.OfChannel(it)) },
                    onItem = { stack.add(Screen.Watch(it)) },
                    modifier = modifier,
                )
                Tab.SETTINGS -> SettingsScreen(app.settings, modifier)
            }
        }
        stack.forEachIndexed { index, screen ->
            key(index, screen) {
                Surface(Modifier.fillMaxSize()) {
                    when (screen) {
                        is Screen.OfChannel -> ChannelScreen(
                            app.youtube, screen.channel,
                            onBack = { stack.removeAt(stack.lastIndex) },
                            onItem = { stack.add(Screen.Watch(it)) },
                        )
                        is Screen.Watch -> WatchScreen(
                            app.youtube, screen.item, app.settings, app.translator,
                            active = index == stack.lastIndex,
                            onChannel = { stack.add(Screen.OfChannel(it)) },
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
