package dev.openstream.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import dev.openstream.ui.SelectChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.openstream.library.ItemList
import dev.openstream.library.Library
import dev.openstream.platform.Channel
import dev.openstream.platform.PlatformId
import dev.openstream.platform.StreamItem
import dev.openstream.platform.StreamingPlatform
import dev.openstream.settings.AppSettings
import dev.openstream.ui.VideoCard
import dev.openstream.ui.VideoRow
import dev.openstream.ui.windowLayout
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val STALE_AFTER_MS = 30 * 60_000L

/** Fills the feed: asks every subscribed channel for its newest uploads, a few channels at a time. */
class FeedRefresher(
    private val library: Library,
    private val platform: (PlatformId) -> StreamingPlatform?,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    var refreshing by mutableStateOf(false); private set
    /** How many channels could not be read in the last refresh. */
    var failed by mutableIntStateOf(0); private set

    fun refresh() {
        scope.launch { refreshNow() }
    }

    /** Returns what was not in the feed before; empty on the very first refresh, when everything would be new. */
    suspend fun refreshNow(): List<StreamItem> {
        if (refreshing) return emptyList()
        refreshing = true
        run {
            try {
                val known = library.feedKeys()
                val failures = AtomicInteger()
                val gate = Semaphore(8)
                val items = coroutineScope {
                    library.subscriptions().map { channel ->
                        async {
                            gate.withPermit {
                                runCatching { platform(channel.platform)?.latest(channel).orEmpty() }
                                    .getOrElse { failures.incrementAndGet(); emptyList() }
                            }
                        }
                    }.awaitAll().flatten()
                }
                library.refreshFeed(items)
                failed = failures.get()
                val first = settings.lastFeedRefresh == 0L
                settings.lastFeedRefresh = System.currentTimeMillis()
                return if (first) emptyList() else items.filter { "${it.platform.id}|${it.id}" !in known }
            } finally {
                refreshing = false
            }
        }
    }

    fun refreshIfStale() {
        if (System.currentTimeMillis() - settings.lastFeedRefresh > STALE_AFTER_MS) refresh()
    }

    /** Right after subscribing, so the channel's videos are in the feed without a full refresh. */
    fun add(channel: Channel) {
        scope.launch {
            runCatching { platform(channel.platform)?.latest(channel).orEmpty() }.onSuccess { library.put(ItemList.FEED, it) }
        }
    }
}

/** The home screen: what the subscribed channels uploaded, newest first, and nothing else. */
@Composable
fun FeedScreen(library: Library, refresher: FeedRefresher, onItem: (StreamItem) -> Unit, modifier: Modifier = Modifier) {
    var shorts by rememberSaveable { mutableStateOf(false) }
    val changes by library.changes.collectAsState()
    val subscribed by produceState<Boolean?>(null, changes) { value = library.subscriptions().isNotEmpty() }
    val items by produceState<List<StreamItem>?>(null, changes, shorts) { value = library.items(ItemList.FEED, shorts = shorts) }
    LaunchedEffect(subscribed) { if (subscribed == true) refresher.refreshIfStale() }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectChip(selected = !shorts, onClick = { shorts = false }, label = { Text("동영상") })
            SelectChip(selected = shorts, onClick = { shorts = true }, label = { Text("쇼츠") })
            Spacer(Modifier.weight(1f))
            if (refresher.failed > 0 && !refresher.refreshing) {
                Text(
                    "채널 ${refresher.failed}개를 불러오지 못함",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (refresher.refreshing) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(onClick = refresher::refresh, enabled = subscribed == true) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "새로고침")
                }
            }
        }
        val list = items
        when {
            subscribed == false -> Message("구독한 채널이 없습니다.\n검색에서 채널을 구독하거나, 설정에서 구독 목록을 가져오세요.")
            list == null -> {}
            list.isEmpty() -> Message(if (refresher.refreshing) "새 영상을 불러오는 중입니다." else if (shorts) "새 쇼츠가 없습니다." else "새 영상이 없습니다.")
            windowLayout().tablet -> LazyVerticalGrid(GridCells.Adaptive(260.dp), contentPadding = PaddingValues(8.dp)) {
                gridItems(list) { item -> VideoCard(item, onClick = { onItem(item) }, showPlatform = true) }
            }
            else -> LazyColumn { items(list) { item -> VideoRow(item, onClick = { onItem(item) }, showPlatform = true) } }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
