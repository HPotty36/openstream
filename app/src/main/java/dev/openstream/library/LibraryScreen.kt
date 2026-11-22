package dev.openstream.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.openstream.platform.Channel
import dev.openstream.platform.StreamItem
import dev.openstream.ui.ChannelRow
import dev.openstream.ui.VideoCard
import dev.openstream.ui.VideoRow
import dev.openstream.ui.windowLayout
import kotlinx.coroutines.launch

private enum class Section(val label: String) { CHANNELS("구독 채널"), LATER("나중에 볼 영상"), HISTORY("시청 기록"), DOWNLOADS("다운로드") }

/** What is kept on the device in place of an account: the subscriptions, the saved videos and the history. */
@Composable
fun LibraryScreen(library: Library, downloads: dev.openstream.download.Downloads, onChannel: (Channel) -> Unit, onItem: (StreamItem) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(Section.CHANNELS) }
    val changes by library.changes.collectAsState()

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section.entries.forEach {
                FilterChip(selected = section == it, onClick = { section = it }, label = { Text(it.label) })
            }
            if (section == Section.HISTORY) {
                TextButton(onClick = { scope.launch { library.clear(ItemList.HISTORY) } }) { Text("기록 지우기") }
            }
        }
        if (section == Section.DOWNLOADS) {
            if (downloads.tasks.isEmpty()) {
                Message("받은 영상은 휴대폰의 Download/OpenStream 폴더에 저장됩니다.")
            } else {
                LazyColumn {
                    items(downloads.tasks) { task ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            val status = task.error ?: if (task.finished) "Download/OpenStream 폴더에 저장됨" else "${(task.progress * 100).toInt()}%"
                            Text(
                                "${task.label} · $status",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (task.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!task.finished && task.error == null) {
                                LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
        } else if (section == Section.CHANNELS) {
            val channels by produceState<List<Channel>?>(null, changes) { value = library.subscriptions() }
            val list = channels
            when {
                list == null -> {}
                list.isEmpty() -> Message("구독한 채널이 없습니다.")
                else -> LazyColumn { items(list) { channel -> ChannelRow(channel) { onChannel(channel) } } }
            }
        } else {
            val which = if (section == Section.LATER) ItemList.LATER else ItemList.HISTORY
            val videos by produceState<List<StreamItem>?>(null, changes, which) { value = library.items(which) }
            val list = videos
            when {
                list == null -> {}
                list.isEmpty() -> Message(if (which == ItemList.LATER) "저장한 영상이 없습니다." else "시청 기록이 없습니다.")
                windowLayout().tablet -> LazyVerticalGrid(GridCells.Adaptive(260.dp), contentPadding = PaddingValues(8.dp)) {
                    gridItems(list) { item -> VideoCard(item, onClick = { onItem(item) }) }
                }
                else -> LazyColumn { items(list) { item -> VideoRow(item, onClick = { onItem(item) }) } }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
