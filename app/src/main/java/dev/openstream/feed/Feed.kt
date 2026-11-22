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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.unit.sp
import dev.openstream.ui.relativeTime
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.openstream.library.rememberImporter
import dev.openstream.platform.ItemKind
import dev.openstream.ui.ChannelAvatar
import androidx.compose.material3.TextButton
import dev.openstream.translate.TextKind
import dev.openstream.translate.autoTranslated
import dev.openstream.ui.Logo
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
    /** When the feed was last refreshed, for the screen to show; 0 when it never was. */
    var lastRefresh by mutableStateOf(settings.lastFeedRefresh); private set

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
                lastRefresh = settings.lastFeedRefresh
                return if (first) emptyList() else items.filter { "${it.platform.id}|${it.id}" !in known }
            } finally {
                refreshing = false
            }
        }
    }

    fun refreshIfStale() {
        if (System.currentTimeMillis() - settings.lastFeedRefresh > STALE_AFTER_MS) refresh()
    }

    // Channels whose picture was already asked for in this run, found or not, so a failing one is not asked again and again.
    private val avatarAsked = mutableSetOf<String>()

    /** Looks up the pictures of subscribed channels that have none, as after an import. */
    fun fillAvatars() {
        scope.launch {
            val missing = library.subscriptions().filter { it.avatarUrl == null && avatarAsked.add("${it.platform.id}|${it.id}") }
            val gate = Semaphore(4)
            coroutineScope {
                missing.forEach { channel ->
                    launch {
                        gate.withPermit {
                            runCatching { platform(channel.platform)?.channel(channel.id)?.avatarUrl }.getOrNull()
                                ?.let { library.setAvatar(channel, it) }
                        }
                    }
                }
            }
        }
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
fun FeedScreen(
    library: Library,
    refresher: FeedRefresher,
    onItem: (StreamItem) -> Unit,
    onChannel: (Channel) -> Unit,
    onAllChannels: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shorts by rememberSaveable { mutableStateOf(false) }
    val changes by library.changes.collectAsState()
    val channels by produceState<List<Channel>?>(null, changes) { value = library.subscriptions() }
    val items by produceState<List<StreamItem>?>(null, changes, shorts) { value = library.items(ItemList.FEED, shorts = shorts) }
    val subscribed = channels?.isNotEmpty()
    LaunchedEffect(subscribed) { if (subscribed == true) refresher.refreshIfStale() }
    LaunchedEffect(channels) { if (channels.orEmpty().any { it.avatarUrl == null }) refresher.fillAvatars() }

    Column(modifier) {
        // Before the first subscription the welcome below carries the logo itself; two would be decoration.
        if (subscribed != false) Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(28.dp)
            Spacer(Modifier.width(10.dp))
            Text("OpenStream", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (!refresher.refreshing) {
                val status = when {
                    refresher.failed > 0 -> "채널 ${refresher.failed}개를 불러오지 못함"
                    refresher.lastRefresh > 0 -> "${relativeTime(refresher.lastRefresh)} 갱신"
                    else -> null
                }
                if (status != null) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (refresher.refreshing) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else if (subscribed == true) {
                IconButton(onClick = refresher::refresh) { Icon(Icons.Outlined.Refresh, contentDescription = "새로고침") }
            }
        }

        val list = items
        val all = channels
        if (subscribed == false) {
            Welcome(library, onImported = refresher::refresh, onSearch = onSearch)
        } else if (list != null && all != null) {
            // Channels that are on air now come first and carry the accent ring.
            val live = remember(list) { list.filter { it.kind == ItemKind.LIVE }.map { it.channel.platform to it.channel.id }.toSet() }
            val strip = remember(all, live) { all.sortedByDescending { (it.platform to it.id) in live } }
            val head: @Composable () -> Unit = {
                Column {
                    Row(verticalAlignment = Alignment.Top) {
                        // 10 + the cell's own 6 puts the first picture on the 16dp line the logo and the thumbnails start at.
                        LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(start = 10.dp, end = 4.dp)) {
                            items(strip) { channel -> StripChannel(channel, (channel.platform to channel.id) in live) { onChannel(channel) } }
                        }
                        // Stays put while the row scrolls, level with the pictures.
                        TextButton(onClick = onAllChannels, modifier = Modifier.padding(top = 20.dp, end = 4.dp)) { Text("전체") }
                    }
                    Row(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SelectChip(selected = !shorts, onClick = { shorts = false }, label = { Text("동영상") })
                        SelectChip(selected = shorts, onClick = { shorts = true }, label = { Text("쇼츠") })
                    }
                    if (list.isEmpty()) {
                        Text(
                            if (refresher.refreshing) "새 영상을 불러오는 중입니다." else if (shorts) "새 쇼츠가 없습니다." else "새 영상이 없습니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 96.dp),
                        )
                    }
                }
            }
            if (windowLayout().tablet) {
                LazyVerticalGrid(GridCells.Adaptive(260.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }) { head() }
                    gridItems(list) { item -> VideoCard(item, onClick = { onItem(item) }, showPlatform = true) }
                }
            } else {
                LazyColumn {
                    item { head() }
                    items(list) { item -> VideoRow(item, onClick = { onItem(item) }, showPlatform = true) }
                }
            }
        }
    }
}

/** One subscribed channel in the row above the feed. */
@Composable
private fun StripChannel(channel: Channel, live: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            ChannelAvatar(channel, 56.dp, live = live)
        }
        val name = autoTranslated(channel.name, TextKind.NAME)
        // Two lines, so most names fit whole; a live channel says so first. A name of one long word
        // gets one line and an ellipsis, since a second line would only hold its last letter or two.
        Text(
            if (live) "방송 중\n$name" else name,
            style = MaterialTheme.typography.labelMedium.copy(lineHeight = 15.sp),
            color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (live || name.trim().contains(' ')) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp),
        )
    }
}

/** The home screen before the first subscription: what the app is, and the two ways to fill it. */
@Composable
private fun Welcome(library: Library, onImported: () -> Unit, onSearch: () -> Unit) {
    val importer = rememberImporter(library, onImported)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // One lockup: the mark with the name under it.
        Logo(72.dp)
        Spacer(Modifier.height(12.dp))
        Text("OpenStream", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(28.dp))
        Text(
            "구독한 채널의 새 영상이 여기에 나옵니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onSearch, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth()) {
            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("채널 검색")
        }
        Spacer(Modifier.height(4.dp))
        // Filled, not outlined: a thin outline on black reads as a disabled button.
        FilledTonalButton(
            onClick = importer::pick,
            enabled = !importer.busy,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
        ) {
            Text(if (importer.busy) "가져오는 중" else "YouTube 구독 가져오기")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            importer.message ?: "Google 테이크아웃에서 받은 zip 파일을 고르세요.",
            style = MaterialTheme.typography.bodySmall,
            color = if (importer.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
