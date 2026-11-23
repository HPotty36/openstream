package dev.openstream.platform.youtube

import dev.openstream.platform.ChannelTab
import dev.openstream.platform.Comment
import dev.openstream.platform.Page
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Talks to the real YouTube, so it only runs with -Plive=true. */
class CommentImagesLiveCheck {
    private val client = OkHttpClient()

    @Before fun onlyWhenAsked() = assumeTrue(System.getProperty("live") == "true")

    // Busy recent videos have a GIF or two in their first few pages of comments.
    @Test fun gifsComeWithTheirComments() = runBlocking {
        val youtube = YouTubePlatform(client)
        val videos = youtube.channelItems("https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA", ChannelTab.VIDEOS).items.take(3)
        var found: Comment? = null
        var seen = 0
        for (video in videos) {
            var page: Page<Comment>? = youtube.comments(video.id)
            var pages = 0
            while (page != null && pages < 3 && found == null) {
                seen += page.items.size
                found = page.items.firstOrNull { it.image != null }
                page = page.next?.let { youtube.comments(video.id, it) }
                pages++
            }
            if (found != null) break
        }
        println("LIVE gif: in $seen comments, ${found?.image} with '${found?.text?.take(40)}'".replace(Regex("[^\\x20-\\x7E]"), "?"))
        val image = found?.image
        assertTrue(image != null)
        client.newCall(Request.Builder().url(image!!.url).build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("image/webp", it.header("Content-Type"))
        }
    }
}
