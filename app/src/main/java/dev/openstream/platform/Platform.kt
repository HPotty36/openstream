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
    val ageRestricted: Boolean = false,
)

/** [next] goes back into the same call for the following page; null on the last one. */
data class Page<T>(val items: List<T>, val next: String? = null)

sealed interface SearchResult {
    data class OfChannel(val channel: Channel) : SearchResult
    data class OfItem(val item: StreamItem) : SearchResult
}

enum class ChannelTab { VIDEOS, SHORTS, LIVE }

/** A playable source. [audioUrl] is set when video and sound arrive as two streams. */
data class Source(
    val url: String,
    val height: Int? = null,
    val isHls: Boolean = false,
    val audioUrl: String? = null,
)

data class Playback(val item: StreamItem, val description: String, val sources: List<Source>)

data class Comment(
    val id: String,
    val author: String,
    val text: String,
    val authorAvatarUrl: String? = null,
    val likeCount: Long? = null,
    val replyCount: Int = 0,
    /** Goes into [StreamingPlatform.replies]; null when there are none to load. */
    val repliesPage: String? = null,
)

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

    suspend fun playback(itemId: String): Playback

    /** Comments under an upload. Platforms without them return an empty page. */
    suspend fun comments(itemId: String, page: String? = null): Page<Comment>

    suspend fun replies(itemId: String, page: String): Page<Comment>

    /** Read-only chat of a live broadcast; the flow ends when the broadcast does. */
    fun liveChat(itemId: String): Flow<ChatMessage>
}
