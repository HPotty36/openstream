package dev.openstream.watch

import dev.openstream.platform.Chapter
import dev.openstream.platform.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Test

class LinksTest {
    @Test fun youTubeLinksOpenInTheApp() {
        assertEquals(LinkTarget.Video(PlatformId.YOUTUBE, "https://www.youtube.com/watch?v=abc123DEF_-", null), linkTarget("https://www.youtube.com/watch?v=abc123DEF_-&list=x"))
        assertEquals(LinkTarget.Video(PlatformId.YOUTUBE, "https://www.youtube.com/watch?v=abc", 90_000), linkTarget("https://youtu.be/abc?t=90"))
        assertEquals(LinkTarget.Video(PlatformId.YOUTUBE, "https://www.youtube.com/watch?v=abc", 3_723_000), linkTarget("https://m.youtube.com/watch?v=abc&t=1h2m3s"))
        assertEquals(LinkTarget.Video(PlatformId.YOUTUBE, "https://www.youtube.com/watch?v=abc", null), linkTarget("https://youtube.com/shorts/abc"))
        assertEquals(LinkTarget.Video(PlatformId.YOUTUBE, "https://www.youtube.com/watch?v=abc", null), linkTarget("https://www.youtube.com/live/abc?si=z"))
        assertEquals(LinkTarget.Channel(PlatformId.YOUTUBE, "https://www.youtube.com/@LinusTechTips"), linkTarget("https://www.youtube.com/@LinusTechTips/videos"))
        assertEquals(LinkTarget.Channel(PlatformId.YOUTUBE, "https://www.youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw"), linkTarget("https://youtube.com/channel/UCXuqSBlHAE6Xw-yeJA0Tunw"))
        assertEquals(LinkTarget.Web("https://www.youtube.com/playlist?list=PL1"), linkTarget("https://www.youtube.com/playlist?list=PL1"))
    }

    @Test fun chzzkAndTwitchLinksOpenInTheApp() {
        assertEquals(LinkTarget.Video(PlatformId.CHZZK, "live:bb382c2c0cc9fa7c86ab3b037fb5799c", null), linkTarget("https://chzzk.naver.com/live/bb382c2c0cc9fa7c86ab3b037fb5799c"))
        assertEquals(LinkTarget.Video(PlatformId.CHZZK, "video:1234567", null), linkTarget("https://chzzk.naver.com/video/1234567"))
        assertEquals(LinkTarget.Channel(PlatformId.CHZZK, "bb382c2c0cc9fa7c86ab3b037fb5799c"), linkTarget("https://chzzk.naver.com/bb382c2c0cc9fa7c86ab3b037fb5799c"))
        assertEquals(LinkTarget.Video(PlatformId.TWITCH, "video:2345", null), linkTarget("https://www.twitch.tv/videos/2345"))
        assertEquals(LinkTarget.Channel(PlatformId.TWITCH, "otplol_"), linkTarget("https://twitch.tv/otplol_"))
        assertEquals(LinkTarget.Web("https://www.twitch.tv/directory"), linkTarget("https://www.twitch.tv/directory"))
    }

    @Test fun everythingElseGoesToTheBrowser() {
        assertEquals(LinkTarget.Web("https://lmg.gg/f6PMg"), linkTarget("https://lmg.gg/f6PMg"))
        assertEquals(LinkTarget.Web("https://www.piavpn.com/ltt"), linkTarget("www.piavpn.com/ltt"))
    }

    @Test fun findsAddressesInText() {
        val text = "Check out (https://lmg.gg/f6PMg). Also www.piavpn.com/ltt, and https://geni.us/UBxA4!"
        assertEquals(listOf("https://lmg.gg/f6PMg", "www.piavpn.com/ltt", "https://geni.us/UBxA4"), links(text).map { text.substring(it) })
    }

    @Test fun aTimeInsideAnAddressIsNotATimestamp() {
        val text = "https://example.com/at/12:34 and 1:05"
        assertEquals(listOf(65_000L), stampsOutsideLinks(text, null).map { it.ms })
    }

    @Test fun chaptersComeFromADescriptionThatListsThemFromZero() {
        val description = """
            CHAPTERS
            ---------
            0:00 - Intro
            1:16 - AMD giveaway!
            (1:55) got Milk?
            33:40 Final thoughts
        """.trimIndent()
        assertEquals(
            listOf(Chapter(0, "Intro"), Chapter(76_000, "AMD giveaway!"), Chapter(115_000, "got Milk?"), Chapter(2_020_000, "Final thoughts")),
            chaptersFrom(description, 2_068_000),
        )
    }

    @Test fun aFewStrayTimesAreNoChapters() {
        assertEquals(emptyList<Chapter>(), chaptersFrom("1:16 AMD\n1:55 milk\n2:20 fridge", null))
        assertEquals(emptyList<Chapter>(), chaptersFrom("0:00 intro\n5:00 end", null))
        assertEquals(emptyList<Chapter>(), chaptersFrom("0:00 a\n5:00 b\n3:00 c", null))
    }

    @Test fun theChapterPlayingIsTheLastThatStarted() {
        val chapters = listOf(Chapter(0, "a"), Chapter(60_000, "b"), Chapter(120_000, "c"))
        assertEquals(0, chapterAt(chapters, 59_999))
        assertEquals(1, chapterAt(chapters, 60_000))
        assertEquals(2, chapterAt(chapters, 500_000))
    }
}
