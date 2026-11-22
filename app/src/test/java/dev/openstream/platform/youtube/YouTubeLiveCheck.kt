package dev.openstream.platform.youtube

import dev.openstream.platform.ChannelTab
import dev.openstream.platform.SearchResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Talks to the real YouTube, so it only runs with -Plive=true. */
class YouTubeLiveCheck {
    private val client = OkHttpClient()

    @Before fun onlyWhenAsked() = assumeTrue(System.getProperty("live") == "true")

    private fun status(url: String): Int = client.newCall(
        Request.Builder().url(url).header("Range", "bytes=0-2047").header("User-Agent", USER_AGENT).build(),
    ).execute().use { it.code }

    @Test fun searchChannelPlaybackComments() = runBlocking {
        val youtube = YouTubePlatform(client)

        val results = youtube.search("NASA Artemis")
        val item = results.items.filterIsInstance<SearchResult.OfItem>().first().item
        println("LIVE search: ${results.items.size} results, next=${results.next != null}, first='${item.title}' ${item.publishedText}")
        assertTrue(youtube.search("NASA Artemis", results.next).items.isNotEmpty())

        val playback = youtube.playback(item.id)
        println("LIVE playback: " + playback.sources.joinToString { "${it.type}/${it.height}p${if (it.audioUrl != null) "+audio" else ""}" })
        val source = playback.sources.first { (it.height ?: 0) in 1..1080 }
        println("LIVE video status=${status(source.url)} audio status=${source.audioUrl?.let(::status)}")

        val comments = youtube.comments(item.id)
        println("LIVE comments: ${comments.items.size}, next=${comments.next != null}, first='${comments.items.firstOrNull()?.text?.take(60)}'")
        comments.items.firstOrNull { it.repliesPage != null }?.let {
            println("LIVE replies: ${youtube.replies(item.id, it.repliesPage!!).items.size} of ${it.replyCount}")
        }

        val zoo = youtube.comments("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        println("LIVE zoo comments: ${zoo.items.size}, first='${zoo.items.firstOrNull()?.text?.take(60)}' likes=${zoo.items.firstOrNull()?.likeCount}")
        assertTrue(zoo.items.isNotEmpty())

        val channel = youtube.channel(item.channel.id)
        val videos = youtube.channelItems(channel.id, ChannelTab.VIDEOS)
        val shorts = youtube.channelItems(channel.id, ChannelTab.SHORTS)
        val live = youtube.channelItems(channel.id, ChannelTab.LIVE)
        println("LIVE channel '${channel.name}': videos=${videos.items.size} shorts=${shorts.items.size} live=${live.items.size}")
        assertTrue(videos.items.isNotEmpty())

        val latest = youtube.latest(channel)
        println("LIVE latest: ${latest.size}, shorts=${latest.count { it.kind == dev.openstream.platform.ItemKind.SHORT }}, first='${latest.firstOrNull()?.title}' at ${latest.firstOrNull()?.publishedAt}")
        assertTrue(latest.isNotEmpty() && latest.all { it.publishedAt != null && it.thumbnailUrl != null })
    }
}
