package dev.openstream

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.openstream.settings.AppSettings
import dev.openstream.settings.SettingsScreen
import dev.openstream.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = (application as App).settings
        setContent {
            val mode by settings.themeMode.collectAsState()
            AppTheme(mode) { Root(settings) }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    FEED("구독", Icons.Outlined.Subscriptions),
    SEARCH("검색", Icons.Outlined.Search),
    SETTINGS("설정", Icons.Outlined.Settings),
}

@Composable
private fun Root(settings: AppSettings) {
    var tab by rememberSaveable { mutableStateOf(Tab.FEED) }
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
            Tab.SEARCH -> Empty("검색은 다음 단계에서 들어옵니다", modifier)
            Tab.SETTINGS -> SettingsScreen(settings, modifier)
        }
    }
}

@Composable
private fun Empty(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
