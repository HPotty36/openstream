package dev.openstream.channel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import dev.openstream.ui.VideoCard
import dev.openstream.ui.windowLayout
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openstream.platform.Channel
import dev.openstream.platform.ChannelTab
import dev.openstream.platform.StreamItem
import dev.openstream.platform.StreamingPlatform
import dev.openstream.ui.Avatar
import dev.openstream.ui.Paged
import dev.openstream.ui.PagedFooter
import dev.openstream.ui.VideoRow

private val TABS = listOf(
    ChannelTab.VIDEOS to "동영상",
    ChannelTab.SHORTS to "쇼츠",
    ChannelTab.LIVE to "라이브",
)

@Composable
fun ChannelScreen(platform: StreamingPlatform, channel: Channel, onBack: () -> Unit, onItem: (StreamItem) -> Unit) {
    val scope = rememberCoroutineScope()
    val lists = remember(channel.id) {
        TABS.associate { (tab, _) -> tab to Paged(scope) { platform.channelItems(channel.id, tab, it) } }
    }
    var selected by remember { mutableStateOf(ChannelTab.VIDEOS) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로") }
            Avatar(channel.avatarUrl, 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PrimaryTabRow(selectedTabIndex = TABS.indexOfFirst { it.first == selected }) {
            TABS.forEach { (tab, label) ->
                Tab(selected = selected == tab, onClick = { selected = tab }, text = { Text(label) })
            }
        }
        val list = lists.getValue(selected)
        if (windowLayout().tablet) {
            LazyVerticalGrid(GridCells.Adaptive(260.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                gridItems(list.items) { item -> VideoCard(item, onClick = { onItem(item) }, showChannel = false) }
                item(span = { GridItemSpan(maxLineSpan) }) { PagedFooter(list, empty = "올라온 영상이 없습니다") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list.items) { item -> VideoRow(item, onClick = { onItem(item) }, showChannel = false) }
                item { PagedFooter(list, empty = "올라온 영상이 없습니다") }
            }
        }
    }
}
