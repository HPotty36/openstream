package dev.openstream.share

import android.content.Context
import android.content.Intent
import dev.openstream.platform.Channel
import dev.openstream.platform.ItemKind
import dev.openstream.platform.PlatformId
import dev.openstream.platform.StreamItem
import dev.openstream.sponsor.youtubeVideoId

/**
 * The address to share for [item]: the platform's own page, which opens anywhere and which Discord and its
 * embed bots recognise. [atMs] starts a replay partway in; a live broadcast has no time to start from.
 */
fun shareLink(item: StreamItem, atMs: Long? = null): String? {
    val seconds = atMs?.takeIf { item.kind != ItemKind.LIVE }?.div(1000)?.takeIf { it > 0 }
    return when (item.platform) {
        PlatformId.YOUTUBE -> youtubeVideoId(item.id)?.let { "https://youtu.be/$it" + seconds.param("t") { "$it" } }
        PlatformId.CHZZK -> when {
            item.id.startsWith("live:") -> "https://chzzk.naver.com/live/${item.id.removePrefix("live:")}"
            item.id.startsWith("video:") ->
                "https://chzzk.naver.com/video/${item.id.removePrefix("video:")}" + seconds.param("currentTime") { "$it" }
            else -> null
        }
        PlatformId.TWITCH -> when {
            item.id.startsWith("live:") -> "https://www.twitch.tv/${item.id.removePrefix("live:")}"
            item.id.startsWith("video:") ->
                "https://www.twitch.tv/videos/${item.id.removePrefix("video:")}" + seconds.param("t", ::twitchTime)
            else -> null
        }
    }
}

/** Whether a link to [item] can start partway in: a video or replay, not a live broadcast. */
fun sharesFromTime(item: StreamItem): Boolean = shareLink(item, 1000) != shareLink(item)

fun shareLink(channel: Channel): String? = if (channel.id.isEmpty()) null else when (channel.platform) {
    PlatformId.YOUTUBE -> channel.id.takeIf { it.startsWith("https://www.youtube.com/") }
    PlatformId.CHZZK -> "https://chzzk.naver.com/${channel.id}"
    PlatformId.TWITCH -> "https://www.twitch.tv/${channel.id}"
}

private fun Long?.param(name: String, value: (Long) -> String): String = if (this == null) "" else "?$name=${value(this)}"

/** "1h2m3s", the way Twitch writes a start time. */
private fun twitchTime(seconds: Long): String = buildString {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    if (hours > 0) append("${hours}h")
    if (hours > 0 || minutes > 0) append("${minutes}m")
    append("${seconds % 60}s")
}

/** The system share sheet with [link]. [title] shows in the sheet; only the link is sent, so a chat can embed it. */
fun Context.share(link: String, title: String?) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
    title?.takeIf { it.isNotBlank() }?.let { send.putExtra(Intent.EXTRA_TITLE, it) }
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
