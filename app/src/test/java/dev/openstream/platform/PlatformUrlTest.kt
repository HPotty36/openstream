package dev.openstream.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformUrlTest {
    @Test fun recognisesEachPlatform() {
        assertEquals(PlatformId.YOUTUBE, platformOfUrl("https://www.youtube.com/watch?v=abc"))
        assertEquals(PlatformId.YOUTUBE, platformOfUrl("https://youtu.be/abc"))
        assertEquals(PlatformId.YOUTUBE, platformOfUrl("https://m.youtube.com/@name"))
        assertEquals(PlatformId.CHZZK, platformOfUrl("https://chzzk.naver.com/live/0123abcd"))
        assertEquals(PlatformId.CHZZK, platformOfUrl("https://m.chzzk.naver.com/0123abcd"))
        assertEquals(PlatformId.TWITCH, platformOfUrl(" https://www.twitch.tv/name "))
    }

    @Test fun rejectsEverythingElse() {
        assertNull(platformOfUrl("https://notyoutube.com/watch"))
        assertNull(platformOfUrl("https://naver.com"))
        assertNull(platformOfUrl("not a link"))
    }
}
