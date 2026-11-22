package dev.openstream.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.openstream.platform.Channel
import dev.openstream.platform.ItemKind
import dev.openstream.platform.PlatformId
import dev.openstream.translate.LocalAutoTranslate
import dev.openstream.translate.TextKind
import dev.openstream.ui.ChannelRow

/** Channels that have a live broadcast in the feed right now, as platform and channel id. */
suspend fun Library.liveChannels(): Set<Pair<PlatformId, String>> =
    items(ItemList.FEED).filter { it.kind == ItemKind.LIVE }.map { it.channel.platform to it.channel.id }.toSet()

/**
 * Subscribed channels in one block per platform, live ones first in each, then by name.
 * [query] keeps only names that contain it, in the original or the translated spelling.
 */
@Composable
fun SubscriptionList(
    channels: List<Channel>,
    live: Set<Pair<PlatformId, String>>,
    onChannel: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    query: String = "",
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
) {
    val auto = LocalAutoTranslate.current
    val wanted = query.trim()
    fun matches(channel: Channel) = wanted.isEmpty() ||
        channel.name.contains(wanted, ignoreCase = true) ||
        auto?.translationOf(channel.name, TextKind.NAME)?.contains(wanted, ignoreCase = true) == true
    val groups = PlatformId.entries.map { platform ->
        platform to channels.filter { it.platform == platform && matches(it) }
            .sortedWith(compareByDescending<Channel> { (it.platform to it.id) in live }.thenBy { it.name.lowercase() })
    }.filter { it.second.isNotEmpty() }

    if (groups.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                if (wanted.isEmpty()) "구독한 채널이 없습니다." else "'$wanted'에 맞는 채널이 없습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier, contentPadding = contentPadding) {
        groups.forEach { (platform, list) ->
            item(key = "head-${platform.id}") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                    Text(platform.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(6.dp))
                    Text("${list.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(list, key = { "${it.platform.id}|${it.id}" }) { channel ->
                ChannelRow(channel, note = null, live = (channel.platform to channel.id) in live) { onChannel(channel) }
            }
        }
    }
}

/** Every subscribed channel on one page, opened from the 전체 button next to the home screen's channel row. */
@Composable
fun SubscriptionsScreen(library: Library, onChannel: (Channel) -> Unit, onBack: () -> Unit) {
    val changes by library.changes.collectAsState()
    val channels by produceState<List<Channel>?>(null, changes) { value = library.subscriptions() }
    val live by produceState(emptySet<Pair<PlatformId, String>>(), changes) { value = library.liveChannels() }
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로") }
            Text("구독 채널", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            channels?.let { Text("${it.size}개", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            placeholder = { Text("채널 이름") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "지우기") }
            },
        )
        val list = channels ?: return@Column
        SubscriptionList(list, live, onChannel, Modifier.fillMaxSize(), query)
    }
}
