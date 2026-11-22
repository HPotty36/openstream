package dev.openstream.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openstream.platform.Channel
import dev.openstream.platform.ChannelGoneException
import dev.openstream.translate.TextKind
import dev.openstream.translate.autoTranslated
import dev.openstream.ui.ChannelAvatar
import dev.openstream.ui.errorText
import kotlinx.coroutines.launch

/** A channel the last refresh could not read. [gone]: the platform says the channel itself no longer exists. */
data class RefreshFailure(val channel: Channel, val reason: String, val gone: Boolean)

fun refreshFailure(channel: Channel, e: Throwable): RefreshFailure =
    if (e is ChannelGoneException) RefreshFailure(channel, e.message ?: "없는 채널입니다.", gone = true)
    else RefreshFailure(channel, errorText(e), gone = false)

/** The channels behind "채널 N개를 불러오지 못함", why each failed, and a way to drop the ones that are gone. */
@Composable
fun RefreshFailuresScreen(refresher: FeedRefresher, onChannel: (Channel) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val failures = refresher.failures
    val gone = failures.filter { it.gone }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로") }
            Text("불러오지 못한 채널", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${failures.size}개", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (gone.size > 1) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("삭제되거나 해지된 채널 ${gone.size}개", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { scope.launch { gone.forEach { refresher.unsubscribeFailed(it.channel) } } }) {
                    Text("모두 구독 취소")
                }
            }
        }
        if (failures.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("불러오지 못한 채널이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(failures, key = { "${it.channel.platform.id}|${it.channel.id}" }) { failure ->
                FailureRow(
                    failure,
                    // A channel that is gone has no page left to open.
                    onClick = if (failure.gone) null else ({ onChannel(failure.channel) }),
                    onUnsubscribe = { scope.launch { refresher.unsubscribeFailed(failure.channel) } },
                )
            }
        }
    }
}

@Composable
private fun FailureRow(failure: RefreshFailure, onClick: (() -> Unit)?, onUnsubscribe: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelAvatar(failure.channel, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                autoTranslated(failure.channel.name, TextKind.NAME),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                failure.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onUnsubscribe) { Text("구독 취소") }
    }
}
