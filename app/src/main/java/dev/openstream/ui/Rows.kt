package dev.openstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalContext
import dev.openstream.share.share
import dev.openstream.share.shareLink
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
import androidx.compose.material3.ColorScheme
import android.content.Context
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
import dev.openstream.platform.PlatformId
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.openstream.platform.StreamItem
import dev.openstream.translate.TextKind
import dev.openstream.translate.autoTranslated

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

// The platforms' own colors, so a badge reads at a glance; the letter is there for when the color does not.
private fun PlatformId.badge(): Triple<String, Color, Color> = when (this) {
    PlatformId.YOUTUBE -> Triple("Y", Color(0xFFFF0033), Color.White)
    PlatformId.CHZZK -> Triple("C", Color(0xFF00FFA3), Color.Black)
    PlatformId.TWITCH -> Triple("T", Color(0xFF9146FF), Color.White)
}

/**
 * A channel's picture with a small mark of its platform in the corner. A streamer's YouTube and
 * CHZZK channels often share a name and a picture; the mark is what tells them apart.
 * A [live] channel gets a ring around the picture; the box then grows by 8dp, and the mark sits on the ring.
 */
@Composable
fun ChannelAvatar(channel: Channel, size: Dp, modifier: Modifier = Modifier, live: Boolean = false) {
    val badge = (size * 0.36f).coerceAtLeast(16.dp)
    val (letter, fill, ink) = channel.platform.badge()
    val outer = if (live) size + 8.dp else size
    Box(modifier.size(outer)) {
        if (live) Box(Modifier.matchParentSize().border(2.dp, MaterialTheme.colorScheme.primary, CircleShape))
        Avatar(channel.avatarUrl, size, Modifier.align(Alignment.Center))
        // Until the picture is found, the first letter of the name stands in for it.
        if (channel.avatarUrl == null) {
            Text(
                channel.name.take(1).uppercase(),
                style = if (size >= 48.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // Drawn last, so it lies on top of the ring rather than under it.
        Box(
            Modifier.align(Alignment.BottomEnd).offset(x = if (live) 0.dp else 2.dp, y = if (live) 0.dp else 2.dp).size(badge)
                // A ring in the background color, so the mark stands off the picture under it.
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .padding(2.dp).clip(CircleShape).background(fill),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                letter,
                color = ink,
                fontSize = (badge.value * 0.5f).sp,
                fontWeight = FontWeight.Bold,
                lineHeight = (badge.value * 0.5f).sp,
                style = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
        }
    }
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

/**
 * The line under a title: platform (in lists that mix platforms), channel, age.
 * When the line is too long the channel's name gives way; the age is always whole.
 */
@Composable
fun MetaLine(item: StreamItem, showChannel: Boolean, showPlatform: Boolean, modifier: Modifier = Modifier) {
    val name = autoTranslated(item.channel.name, TextKind.NAME)
    val lead = listOfNotNull(
        item.platform.label.takeIf { showPlatform },
        name.takeIf { showChannel && it.isNotEmpty() },
    ).joinToString(" · ")
    val age = item.published()
    if (lead.isEmpty() && age == null) return
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier) {
        if (lead.isNotEmpty()) {
            Text(lead, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        if (age != null) {
            Text(if (lead.isEmpty()) age else " · $age", style = style, color = color, maxLines = 1, softWrap = false)
        }
    }
}

/** The frame every thumbnail sits in: a thin edge keeps dark pictures from melting into the black background. */
fun Modifier.thumbnailFrame(colors: ColorScheme): Modifier =
    aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
        .background(colors.surfaceContainerHighest)
        .border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp))

@Composable
fun Thumbnail(item: StreamItem, modifier: Modifier = Modifier) {
    Box(modifier.thumbnailFrame(MaterialTheme.colorScheme)) {
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

/** A tap opens it; a long press shares [link], the platform's own page for it. */
fun Modifier.openOrShare(context: Context, link: String?, title: String?, onClick: () -> Unit): Modifier =
    if (link == null) clickable(onClick = onClick)
    else combinedClickable(onLongClickLabel = "공유", onLongClick = { context.share(link, title) }, onClick = onClick)

@Composable
fun VideoRow(item: StreamItem, onClick: () -> Unit, showChannel: Boolean = true, showPlatform: Boolean = false) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().openOrShare(context, shareLink(item), item.title, onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Thumbnail(item, Modifier.width(152.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(autoTranslated(item.title), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetaLine(item, showChannel, showPlatform, Modifier.padding(top = 4.dp))
        }
    }
}

/** [note] goes under the name; by default the platform, which a list grouped by platform does not need. */
@Composable
fun ChannelRow(channel: Channel, note: String? = channel.platform.label, live: Boolean = false, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().openOrShare(context, shareLink(channel), channel.name, onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelAvatar(channel, 48.dp, live = live)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                autoTranslated(channel.name, TextKind.NAME),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val below = if (live) "방송 중" else note
            if (below != null) {
                Text(
                    below,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
