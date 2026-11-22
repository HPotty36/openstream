package dev.openstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.composed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.openstream.platform.Channel
import dev.openstream.platform.ItemKind
import dev.openstream.platform.StreamItem

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun relativeTime(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val minutes = (nowMs - thenMs).coerceAtLeast(0) / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "방금 전"
        hours < 1 -> "${minutes}분 전"
        days < 1 -> "${hours}시간 전"
        days < 7 -> "${days}일 전"
        days < 30 -> "${days / 7}주 전"
        days < 365 -> "${days / 30}개월 전"
        else -> "${days / 365}년 전"
    }
}

fun StreamItem.published(): String? = publishedAt?.let { relativeTime(it) } ?: publishedText

@Composable
fun Avatar(url: String?, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/** The one chip of the app: the chosen one is filled light, so it reads at a glance on black. */
@Composable
fun SelectChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.onSurface,
            selectedLabelColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/** The line under a title: platform (in lists that mix platforms), channel, age. */
fun StreamItem.meta(showChannel: Boolean, showPlatform: Boolean): String = listOfNotNull(
    platform.label.takeIf { showPlatform },
    channel.name.takeIf { showChannel && it.isNotEmpty() },
    published(),
).joinToString(" · ")

/** The frame every thumbnail sits in: a thin edge keeps dark pictures from melting into the black background. */
fun Modifier.thumbnailFrame(): Modifier = composed {
    aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
}

@Composable
fun Thumbnail(item: StreamItem, modifier: Modifier = Modifier) {
    Box(modifier.thumbnailFrame()) {
        AsyncImage(item.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        val badge = when {
            item.kind == ItemKind.LIVE -> "LIVE"
            item.durationSeconds != null -> formatDuration(item.durationSeconds)
            else -> null
        }
        if (badge != null) {
            Text(
                badge,
                // Black on the light red of the live badge; white there is too faint to read.
                color = if (item.kind == ItemKind.LIVE) Color.Black else Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(
                        if (item.kind == ItemKind.LIVE) MaterialTheme.colorScheme.error.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.75f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
fun VideoRow(item: StreamItem, onClick: () -> Unit, showChannel: Boolean = true, showPlatform: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Thumbnail(item, Modifier.width(152.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = item.meta(showChannel, showPlatform)
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
fun ChannelRow(channel: Channel, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(channel.avatarUrl, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(channel.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("채널", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
