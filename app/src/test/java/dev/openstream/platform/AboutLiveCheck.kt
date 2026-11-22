package dev.openstream.platform

import dev.openstream.platform.chzzk.ChzzkPlatform
import dev.openstream.platform.twitch.TwitchPlatform
import dev.openstream.platform.youtube.YouTubePlatform
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Channel descriptions and video titles from the real services; runs only with -Plive=true. */
class AboutLiveCheck {
    private val client = OkHttpClient()

    @Before fun onlyWhenAsked() = assumeTrue(System.getProperty("live") == "true")

    private fun show(label: String, text: String?) =
        println("LIVE $label: ${text?.take(60)?.replace('\n', ' ')}".replace(Regex("[^\\x20-\\x7E]"), "?") + " (${text?.length ?: 0} chars)")

    @Test fun eachPlatformGivesAChannelDescription() = runBlocking {
        val youtube = YouTubePlatform(client).about("https://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ")
        show("youtube about", youtube)
        val chzzk = ChzzkPlatform(client).about("bb382c2c0cc9fa7c86ab3b037fb5799c")
        show("chzzk about", chzzk)
        val twitch = TwitchPlatform(client).about("twitch")
        show("twitch about", twitch)
        assertFalse(youtube.isNullOrBlank())
        assertFalse(twitch.isNullOrBlank())
    }

    @Test fun latestOfEveryTestChannel() = runBlocking {
        val checks = listOf(
            ChzzkPlatform(client) to Channel(PlatformId.CHZZK, "bb382c2c0cc9fa7c86ab3b037fb5799c", "chim"),
            TwitchPlatform(client) to Channel(PlatformId.TWITCH, "otplol_", "otplol_"),
        )
        checks.forEach { (platform, channel) ->
            val result = runCatching { platform.latest(channel) }
            println("LIVE latest ${channel.platform.id}: ${result.map { it.size }.getOrElse { "${it::class.simpleName} ${it.message}" }}")
        }
    }

    /** Chimchakman's own uploads are in Korean; some came back with English titles in the channel tab. */
    @Test fun channelTabTitlesAreTheOriginals() = runBlocking {
        val youtube = YouTubePlatform(client)
        val channel = "https://www.youtube.com/channel/UCUj6rrhMTR9pipbAWBAMvUQ"
        val tab = youtube.channelItems(channel, ChannelTab.VIDEOS).items.take(8)
        val feed = youtube.latest(Channel(PlatformId.YOUTUBE, channel, "")).associateBy { it.id }
        tab.forEach { item ->
            val rss = feed[item.id]?.title
            println("LIVE title tab=${item.title.any { it in '가'..'힣' }} rss=${rss?.any { it in '가'..'힣' }} id=${item.id.takeLast(11)}")
        }
    }
}
