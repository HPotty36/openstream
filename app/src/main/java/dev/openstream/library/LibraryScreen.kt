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
import dev.openstream.ui.SelectChip
import dev.openstream.ui.thumbnailFrame
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.foundation.clickable
import dev.openstream.download.SavedFile
import dev.openstream.ui.formatDuration
import dev.openstream.platform.Channel
import dev.openstream.platform.StreamItem
import dev.openstream.ui.ChannelRow
import dev.openstream.ui.VideoCard
import dev.openstream.ui.VideoRow
import dev.openstream.ui.windowLayout
import kotlinx.coroutines.launch

private enum class Section(val label: String) { CHANNELS("채널"), LATER("나중에 보기"), HISTORY("기록"), DOWNLOADS("다운로드") }

/** What is kept on the device in place of an account: the subscriptions, the saved videos and the history. */
@Composable
fun LibraryScreen(
    library: Library,
    downloads: dev.openstream.download.Downloads,
    onChannel: (Channel) -> Unit,
    onItem: (StreamItem) -> Unit,
    onLocal: (SavedFile) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                SelectChip(selected = section == it, onClick = { section = it }, label = { Text(it.label) })
            }
            if (section == Section.HISTORY) {
                TextButton(onClick = { scope.launch { library.clear(ItemList.HISTORY) } }) { Text("기록 지우기") }
            }
        }
        if (section == Section.DOWNLOADS) {
            val saved by produceState<List<SavedFile>?>(null, downloads.version) { value = downloads.saved() }
            // A finished download is in the list of files, so only the unfinished ones are shown as tasks.
            val running = downloads.tasks.filter { !it.finished }
            if (running.isEmpty() && saved?.isEmpty() == true) {
                Message("받은 영상이 없습니다.")
            } else {
                LazyColumn {
                    items(running) { task ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text(
                                "${task.label} · ${task.error ?: "${(task.progress * 100).toInt()}%"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (task.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (task.error == null) {
                                LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            }
                        }
                    }
                    items(saved.orEmpty()) { file ->
                        // The same row as every other list of videos, with the picture taken from the file.
                        Row(Modifier.fillMaxWidth().clickable { onLocal(file) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                            val still by produceState<android.graphics.Bitmap?>(null, file.uri) { value = downloads.thumbnail(file) }
                            Box(Modifier.width(152.dp).thumbnailFrame(), contentAlignment = Alignment.Center) {
                                val picture = still
                                if (picture != null) {
                                    Image(picture.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                } else if (file.audioOnly) {
                                    Text("소리만", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val details = listOfNotNull(
                                    file.durationMs?.let { formatDuration(it / 1000) },
                                    "%.0fMB".format(file.bytes / 1_048_576.0),
                                ).joinToString(" · ")
                                Text(
                                    details,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            IconButton(onClick = { scope.launch { downloads.delete(file) } }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    gridItems(list) { item -> VideoCard(item, onClick = { onItem(item) }, showPlatform = true) }
                }
                else -> LazyColumn { items(list) { item -> VideoRow(item, onClick = { onItem(item) }, showPlatform = true) } }
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
