package dev.openstream.watch

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.openstream.platform.Channel
import dev.openstream.platform.Chapter
import dev.openstream.platform.ItemKind
import dev.openstream.platform.PlatformId
import dev.openstream.platform.StreamItem
import dev.openstream.platform.platformOfUrl
import java.net.URI
import java.net.URLDecoder

/** Where a link in a description or a comment leads: something to watch here, a channel here, or a web page. */
sealed interface LinkTarget {
    /** [itemId] as the platform takes it; [startMs] from the link's start time (YouTube and Twitch t=, CHZZK currentTime=). */
    data class Video(val platform: PlatformId, val itemId: String, val startMs: Long?) : LinkTarget
    data class Channel(val platform: PlatformId, val channelId: String) : LinkTarget
    data class Web(val url: String) : LinkTarget
}

private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]+")
private val CHZZK_ID = Regex("[0-9a-f]{32}")
private val TWITCH_LOGIN = Regex("[A-Za-z0-9_]{3,25}")
// Twitch pages that sit where a channel name would.
private val TWITCH_PAGES = setOf(
    "directory", "videos", "settings", "search", "downloads", "p", "jobs", "turbo", "prime", "subscriptions",
    "inventory", "wallet", "friends", "messages", "drops", "store", "legal", "login", "signup", "logout", "popout", "u",
)

/** "90", "90s", "1m30s", "1h2m3s" as milliseconds. */
private fun youtubeTime(value: String): Long? {
    val match = Regex("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s?)?").matchEntire(value) ?: return null
    if (value.isEmpty()) return null
    val (h, m, s) = match.destructured
    return ((h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)) * 1000
}

private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').mapNotNull { pair ->
    val key = pair.substringBefore('=').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    key to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
}.toMap()

/** What [raw] points at. Links to the three platforms stay in the app; the rest go to the browser. */
fun linkTarget(raw: String): LinkTarget {
    val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
    val uri = runCatching { URI(url) }.getOrNull() ?: return LinkTarget.Web(url)
    val path = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
    val first = path.firstOrNull()
    return when (platformOfUrl(url)) {
        PlatformId.YOUTUBE -> {
            val params = query(uri.rawQuery)
            val id = when {
                uri.host.orEmpty().lowercase().endsWith("youtu.be") -> first
                first == "watch" -> params["v"]
                first in setOf("shorts", "live", "embed", "v") -> path.getOrNull(1)
                else -> null
            }
            when {
                id != null && YOUTUBE_ID.matches(id) -> LinkTarget.Video(
                    PlatformId.YOUTUBE,
                    "https://www.youtube.com/watch?v=$id",
                    params["t"]?.let(::youtubeTime) ?: params["start"]?.toLongOrNull()?.times(1000),
                )
                first?.startsWith("@") == true -> LinkTarget.Channel(PlatformId.YOUTUBE, "https://www.youtube.com/$first")
                first in setOf("channel", "c", "user") && path.size >= 2 ->
                    LinkTarget.Channel(PlatformId.YOUTUBE, "https://www.youtube.com/$first/${path[1]}")
                else -> LinkTarget.Web(url)
            }
        }
        PlatformId.CHZZK -> when {
            first == "live" && path.getOrNull(1)?.let(CHZZK_ID::matches) == true -> LinkTarget.Video(PlatformId.CHZZK, "live:${path[1]}", null)
            // CHZZK's own chapter links write the start as seconds, sometimes with a fraction.
            first == "video" && path.getOrNull(1)?.all(Char::isDigit) == true -> LinkTarget.Video(
                PlatformId.CHZZK,
                "video:${path[1]}",
                query(uri.rawQuery)["currentTime"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
            )
            path.size == 1 && CHZZK_ID.matches(path[0]) -> LinkTarget.Channel(PlatformId.CHZZK, path[0])
            else -> LinkTarget.Web(url)
        }
        PlatformId.TWITCH -> when {
            first == "videos" && path.getOrNull(1)?.all(Char::isDigit) == true ->
                LinkTarget.Video(PlatformId.TWITCH, "video:${path[1]}", query(uri.rawQuery)["t"]?.let(::youtubeTime))
            path.size == 1 && TWITCH_LOGIN.matches(path[0]) && path[0].lowercase() !in TWITCH_PAGES ->
                LinkTarget.Channel(PlatformId.TWITCH, path[0].lowercase())
            else -> LinkTarget.Web(url)
        }
        null -> LinkTarget.Web(url)
    }
}

/** Something to open for a link to a video; it fills in its title and channel once it loads. */
fun LinkTarget.Video.toItem(): StreamItem = StreamItem(
    platform = platform,
    id = itemId,
    kind = if (itemId.startsWith("live:")) ItemKind.LIVE else ItemKind.VIDEO,
    title = "",
    channel = Channel(platform, "", ""),
)

// ASCII only, so a Korean word written right after an address is not taken into it.
private val LINK = Regex("(?:https?://|www\\.)[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+", RegexOption.IGNORE_CASE)

/** Where the web addresses in [text] are, without the punctuation that ends a sentence around them. */
fun links(text: String): List<IntRange> = LINK.findAll(text).map { match ->
    var end = match.range.last
    while (end > match.range.first) {
        val c = text[end]
        val part = text.substring(match.range.first, end + 1)
        val unbalanced = (c == ')' && part.count { it == '(' } < part.count { it == ')' }) ||
            (c == ']' && part.count { it == '[' } < part.count { it == ']' })
        if (c in ".,;:!?'\"" || unbalanced) end-- else break
    }
    match.range.first..end
}.toList()

/** The times in [text] that are not part of a web address. */
fun stampsOutsideLinks(text: String, durationMs: Long?): List<Stamp> {
    val addresses = links(text)
    return timestamps(text, durationMs).filter { stamp -> addresses.none { it.first <= stamp.range.last && stamp.range.first <= it.last } }
}

/**
 * The chapters a description lists, one per line with its time: "0:00 Intro", "(1:55) got milk", "Outro - 33:40".
 * As on YouTube, only a list that starts at 0:00, has three or more and goes forward counts.
 */
fun chaptersFrom(description: String, durationMs: Long?): List<Chapter> {
    val chapters = description.lines().mapNotNull { line ->
        val stamp = timestamps(line, durationMs).firstOrNull() ?: return@mapNotNull null
        val title = line.removeRange(stamp.range)
            .replace(Regex("[(\\[]\\s*[)\\]]"), "")
            .trimStart(' ', '-', '–', '—', ':', '|', '·', '•', '(', ')', '[', ']', '\t')
            .trimEnd(' ', '-', '–', '—', ':', '|', '·', '•', '\t')
        if (title.isEmpty()) null else Chapter(stamp.ms, title)
    }
    val valid = chapters.size >= 3 && chapters.first().startMs == 0L && chapters.zipWithNext().all { (a, b) -> b.startMs > a.startMs }
    return if (valid) chapters else emptyList()
}

/** The chapter playing at [positionMs]: the last one that has started. */
fun chapterAt(chapters: List<Chapter>, positionMs: Long): Int = chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

/** "8:50", "1:02:03". */
fun clockTime(ms: Long): String {
    val total = ms / 1000
    val (h, m, s) = Triple(total / 3600, total % 3600 / 60, total % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * [text] with its times jumping through [onSeek] and its web addresses opening through [onLink].
 * Times are bold, addresses underlined, both in the accent, so either stands out in a translation already in the accent.
 */
@Composable
fun linkedText(text: String, durationMs: Long?, onSeek: ((Long) -> Unit)?, onLink: ((String) -> Unit)?): AnnotatedString {
    val seek by rememberUpdatedState(onSeek)
    val open by rememberUpdatedState(onLink)
    val color = MaterialTheme.colorScheme.primary
    val seekable = onSeek != null
    val linkable = onLink != null
    return remember(text, durationMs, color, seekable, linkable) {
        if (!seekable && !linkable) return@remember AnnotatedString(text)
        buildAnnotatedString {
            append(text)
            if (linkable) links(text).forEach { range ->
                val address = text.substring(range)
                addLink(
                    LinkAnnotation.Clickable("u$address", TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))) {
                        open?.invoke(address)
                    },
                    range.first,
                    range.last + 1,
                )
            }
            if (seekable) stampsOutsideLinks(text, durationMs).forEach { stamp ->
                addLink(
                    LinkAnnotation.Clickable("t${stamp.ms}", TextLinkStyles(SpanStyle(color = color, fontWeight = FontWeight.SemiBold))) {
                        seek?.invoke(stamp.ms)
                    },
                    stamp.range.first,
                    stamp.range.last + 1,
                )
            }
        }
    }
}
