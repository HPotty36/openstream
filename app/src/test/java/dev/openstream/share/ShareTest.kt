package dev.openstream.share

import dev.openstream.platform.Channel
import dev.openstream.platform.ItemKind
import dev.openstream.platform.PlatformId
import dev.openstream.platform.StreamItem
import dev.openstream.watch.LinkTarget
import dev.openstream.watch.linkTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTest {
    private fun item(platform: PlatformId, id: String, kind: ItemKind = ItemKind.VIDEO) =
        StreamItem(platform = platform, id = id, kind = kind, title = "", channel = Channel(platform, "", ""))

    private val youtube = item(PlatformId.YOUTUBE, "https://www.youtube.com/watch?v=q6fgVVmdpgY")
    private val chzzkVideo = item(PlatformId.CHZZK, "video:16295513")
    private val chzzkLive = item(PlatformId.CHZZK, "live:a7e175625fdea5a7d98428302b7aa57f", ItemKind.LIVE)
    private val twitchVideo = item(PlatformId.TWITCH, "video:2600000000")
    private val twitchLive = item(PlatformId.TWITCH, "live:zilioner", ItemKind.LIVE)

    @Test fun eachPlatformsOwnAddress() {
        assertEquals("https://youtu.be/q6fgVVmdpgY", shareLink(youtube))
        assertEquals("https://youtu.be/q6fgVVmdpgY?t=754", shareLink(youtube, 754_900))
        assertEquals("https://youtu.be/abcdefghijk", shareLink(item(PlatformId.YOUTUBE, "https://www.youtube.com/watch?v=abcdefghijk", ItemKind.SHORT)))
        assertEquals("https://chzzk.naver.com/video/16295513?currentTime=754", shareLink(chzzkVideo, 754_000))
        assertEquals("https://chzzk.naver.com/live/a7e175625fdea5a7d98428302b7aa57f", shareLink(chzzkLive, 754_000))
        assertEquals("https://www.twitch.tv/videos/2600000000?t=1h2m3s", shareLink(twitchVideo, 3_723_000))
        assertEquals("https://www.twitch.tv/videos/2600000000?t=5m0s", shareLink(twitchVideo, 300_000))
        assertEquals("https://www.twitch.tv/videos/2600000000?t=45s", shareLink(twitchVideo, 45_000))
        assertEquals("https://www.twitch.tv/zilioner", shareLink(twitchLive))
    }

    @Test fun theFirstSecondIsTheStart() {
        assertEquals("https://youtu.be/q6fgVVmdpgY", shareLink(youtube, 400))
        assertTrue(sharesFromTime(youtube) && sharesFromTime(chzzkVideo) && sharesFromTime(twitchVideo))
        assertFalse(sharesFromTime(chzzkLive) || sharesFromTime(twitchLive))
    }

    @Test fun channels() {
        assertEquals("https://www.youtube.com/@MrBeast", shareLink(Channel(PlatformId.YOUTUBE, "https://www.youtube.com/@MrBeast", "")))
        assertEquals("https://chzzk.naver.com/a7e175625fdea5a7d98428302b7aa57f", shareLink(Channel(PlatformId.CHZZK, "a7e175625fdea5a7d98428302b7aa57f", "")))
        assertEquals("https://www.twitch.tv/zilioner", shareLink(Channel(PlatformId.TWITCH, "zilioner", "")))
        assertNull(shareLink(Channel(PlatformId.TWITCH, "", "")))
    }

    // What the app shares, it also opens: the same video, from the same second.
    @Test fun sharedLinksComeBackIn() {
        for ((item, at) in listOf(youtube to 754_000L, chzzkVideo to 754_000L, twitchVideo to 3_723_000L, chzzkLive to null)) {
            val target = linkTarget(shareLink(item, at)!!) as LinkTarget.Video
            assertEquals(item.id, target.itemId)
            assertEquals(at, target.startMs)
        }
        assertEquals(LinkTarget.Channel(PlatformId.TWITCH, "zilioner"), linkTarget(shareLink(twitchLive)!!))
    }
}
