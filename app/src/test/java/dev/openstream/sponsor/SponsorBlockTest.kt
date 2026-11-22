package dev.openstream.sponsor

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class SponsorBlockTest {
    @Test fun findsTheVideoIdInEveryKindOfAddress() {
        assertEquals("dQw4w9WgXcQ", youtubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3"))
        assertEquals("dQw4w9WgXcQ", youtubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youtubeVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertNull(youtubeVideoId("https://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ"))
    }

    @Test fun keepsOnlyTheAskedVideosSegmentsInOrder() {
        val json = """[
            {"videoID":"other111111","segments":[{"category":"sponsor","segment":[1.0,2.0]}]},
            {"videoID":"wanted11111","segments":[{"category":"sponsor","segment":[60.5,90.0]},{"category":"sponsor","segment":[5,12.25]},{"category":"sponsor","segment":[7,7]}]}]"""
        assertEquals(listOf(SkipSegment(5000, 12250), SkipSegment(60500, 90000)), parseSegments(json, "wanted11111"))
        assertEquals(emptyList<SkipSegment>(), parseSegments(json, "missing1111"))
        assertEquals(emptyList<SkipSegment>(), parseSegments("Not Found", "wanted11111"))
    }

    /** Talks to the real SponsorBlock, so it only runs with -Plive=true. */
    @Test fun realServerAnswers() = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        // Looks through a channel known for sponsor reads until a video with marked segments turns up.
        val client = OkHttpClient()
        val youtube = dev.openstream.platform.youtube.YouTubePlatform(client)
        val videos = youtube.search("Linus Tech Tips").items
            .filterIsInstance<dev.openstream.platform.SearchResult.OfItem>().map { it.item }.take(8)
        val found = videos.firstNotNullOfOrNull { video ->
            SponsorBlock(client).segments(youtubeVideoId(video.id)!!).takeIf { it.isNotEmpty() }?.let { video to it }
        }
        println("LIVE sponsorblock: checked ${videos.size} videos, found ${found?.second} in ${found?.first?.id}")
        org.junit.Assert.assertTrue(found != null)
    }
}
