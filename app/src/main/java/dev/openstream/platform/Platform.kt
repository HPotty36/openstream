package dev.openstream.platform

import java.net.URI
import kotlinx.coroutines.flow.Flow

enum class PlatformId(val id: String, val label: String) {
    YOUTUBE("youtube", "YouTube"),
    CHZZK("chzzk", "치지직"),
    TWITCH("twitch", "Twitch"),
}

/** Which platform a pasted or shared link belongs to, or null when it is none of ours. */
fun platformOfUrl(url: String): PlatformId? {
    val host = runCatching { URI(url.trim()).host }.getOrNull()
        ?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
    return when {
        host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") -> PlatformId.YOUTUBE
        host == "chzzk.naver.com" -> PlatformId.CHZZK
        host == "twitch.tv" || host.endsWith(".twitch.tv") -> PlatformId.TWITCH
        else -> null
    }
}

/** [id] is whatever the platform needs to find the channel again; for YouTube it is the channel's address. */
data class Channel(
    val platform: PlatformId,
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
)

enum class ItemKind { VIDEO, SHORT, LIVE }

/** One thing to watch: an upload, a short, a live broadcast or its replay. */
data class StreamItem(
    val platform: PlatformId,
    val id: String,
    val kind: ItemKind,
    val title: String,
    val channel: Channel,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
    /** Epoch milliseconds; null when the platform only says something like "3 days ago". */
    val publishedAt: Long? = null,
    /** The platform's own wording of the date, such as "3일 전". */
    val publishedText: String? = null,
    val ageRestricted: Boolean = false,
)

/** [next] goes back into the same call for the following page; null on the last one. */
data class Page<T>(val items: List<T>, val next: String? = null)

sealed interface SearchResult {
    data class OfChannel(val channel: Channel) : SearchResult
    data class OfItem(val item: StreamItem) : SearchResult
}

enum class ChannelTab { VIDEOS, SHORTS, LIVE }

enum class SourceType { PROGRESSIVE, HLS, DASH }

/** A playable source. [audioUrl] is set when video and sound arrive as two streams. */
data class Source(
    val url: String,
    val type: SourceType,
    val height: Int? = null,
    val fps: Int? = null,
    val audioUrl: String? = null,
)

/** [sources] are ordered best first. */
data class Playback(val item: StreamItem, val description: String, val sources: List<Source>)

data class Comment(
    val id: String,
    val author: String,
    val text: String,
    val authorAvatarUrl: String? = null,
    val publishedAt: Long? = null,
    val publishedText: String? = null,
    val likeCount: Int? = null,
    val pinned: Boolean = false,
    val replyCount: Int = 0,
    /** Goes into [StreamingPlatform.replies]; null when there are none to load. */
    val repliesPage: String? = null,
)

/**
 * One way to save an item: an MP4 picture stream with an AAC sound stream, which every phone can
 * put together and play. [videoUrl] is null for sound only.
 */
data class DownloadOption(val label: String, val videoUrl: String?, val audioUrl: String)

data class ChatMessage(val id: String, val author: String, val text: String)

/**
 * What every platform has to answer. The screens and the translator only know this interface,
 * so a new platform is one more implementation and nothing else. No call here needs an account.
 */
interface StreamingPlatform {
    val id: PlatformId

    /** Shorts are left out of search results; they show on a channel's own page and in the feed. */
    suspend fun search(query: String, page: String? = null): Page<SearchResult>

    suspend fun channel(channelId: String): Channel

    suspend fun channelItems(channelId: String, tab: ChannelTab, page: String? = null): Page<StreamItem>

    /** The channel's newest uploads in one cheap request; this is what the subscription feed is built from. */
    suspend fun latest(channel: Channel): List<StreamItem>

    suspend fun playback(itemId: String): Playback

    /** What can be saved to a file, best first; the last entry is sound only. Empty when nothing can. */
    suspend fun downloads(itemId: String): List<DownloadOption>

    /** Comments under an upload. Platforms without them return an empty page. */
    suspend fun comments(itemId: String, page: String? = null): Page<Comment>

    suspend fun replies(itemId: String, page: String): Page<Comment>

    /** Read-only chat of a live broadcast; the flow ends when the broadcast does. */
    fun liveChat(itemId: String): Flow<ChatMessage>
}
