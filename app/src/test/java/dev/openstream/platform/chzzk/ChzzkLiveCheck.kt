package dev.openstream.platform.chzzk

import dev.openstream.platform.ChannelTab
import dev.openstream.platform.ItemKind
import dev.openstream.platform.SearchResult
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Talks to the real CHZZK, so it only runs with -Plive=true. */
class ChzzkLiveCheck {
    private val client = OkHttpClient()

    @Before fun onlyWhenAsked() = assumeTrue(System.getProperty("live") == "true")

    private fun status(url: String): Int = client.newCall(Request.Builder().url(url).header("Range", "bytes=0-2047").build()).execute().use { it.code }

    @Test fun searchChannelPlaybackChat() = runBlocking {
        val chzzk = ChzzkPlatform(client)

        val results = chzzk.search("롤")
        val channels = results.items.filterIsInstance<SearchResult.OfChannel>()
        val items = results.items.filterIsInstance<SearchResult.OfItem>().map { it.item }
        println("LIVE chzzk search: channels=${channels.size} lives=${items.count { it.kind == ItemKind.LIVE }} videos=${items.count { it.kind == ItemKind.VIDEO }} next=${results.next}")
        assertTrue(items.isNotEmpty())
        if (results.next != null) assertTrue(chzzk.search("롤", results.next).items.isNotEmpty())

        val video = items.first { it.kind == ItemKind.VIDEO }
        val replay = chzzk.playback(video.id)
        println("LIVE chzzk replay '${replay.item.title}' ${replay.item.durationSeconds}s by '${replay.item.channel.name}': playlist status=${status(replay.sources.first().url)}")
        val saves = chzzk.downloads(video.id)
        println("LIVE chzzk downloads: ${saves.map { it.label }} first status=${saves.firstOrNull()?.fileUrl?.let(::status)}")

        val live = items.firstOrNull { it.kind == ItemKind.LIVE && !it.ageRestricted }
        if (live != null) {
            val playback = chzzk.playback(live.id)
            println("LIVE chzzk live '${playback.item.title}': playlist status=${status(playback.sources.first().url)}")
            val chat = withTimeoutOrNull(25_000) { chzzk.liveChat(live.id).take(3).toList() }
            println("LIVE chzzk chat: ${chat?.map { "${it.author}: ${it.text.take(20)}" }}")

            val channel = chzzk.channel(live.channel.id)
            val latest = chzzk.latest(channel)
            val videos = chzzk.channelItems(channel.id, ChannelTab.VIDEOS)
            println("LIVE chzzk channel '${channel.name}': latest=${latest.size} (live first=${latest.firstOrNull()?.kind}) videos=${videos.items.size} next=${videos.next} liveTab=${chzzk.channelItems(channel.id, ChannelTab.LIVE).items.size}")
        } else {
            println("LIVE chzzk: no live result to check")
        }

        // The busiest broadcast, where chat is certain to move.
        val popular = client.newCall(Request.Builder().url("https://api.chzzk.naver.com/service/v1/lives?size=1&sortType=POPULAR").header("User-Agent", dev.openstream.platform.youtube.USER_AGENT).build())
            .execute().use { it.body.string() }
        val busiest = Regex("\"channelId\":\"([0-9a-f]+)\"").find(popular)!!.groupValues[1]
        val chat = runCatching { withTimeoutOrNull(25_000) { chzzk.liveChat("live:$busiest").take(3).toList() } }
        println("LIVE chzzk busy chat: ${chat.map { list -> list?.map { "${it.author}: ${it.text.take(20)}" } }}")
        assertTrue(chat.getOrNull()?.size == 3)
    }
}
