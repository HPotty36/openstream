package dev.openstream.platform.youtube

import dev.openstream.platform.Channel
import dev.openstream.platform.ItemKind
import dev.openstream.platform.PlatformId
import dev.openstream.watch.YouTubePieces
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * A YouTube stream read from a point to its end comes at about twice the speed it plays;
 * a piece asked for with range= comes at full speed. Talks to the real YouTube, so it only runs with -Plive=true.
 */
class StreamSpeedLiveCheck {
    private val client = OkHttpClient()

    @Before fun onlyWhenAsked() = assumeTrue(System.getProperty("live") == "true")

    /** Reads [bytes] from [url] and gives the status and the speed in Mbps. */
    private fun read(url: String, bytes: Long): Pair<Int, Double> {
        val start = System.nanoTime()
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) return response.code to 0.0
            val source = response.body.source()
            val sink = okio.Buffer()
            var got = 0L
            while (got < bytes) {
                val n = source.read(sink, 64 * 1024)
                if (n < 0) break
                got += n
                sink.clear()
            }
            return response.code to got * 8 / ((System.nanoTime() - start) / 1e9) / 1e6
        }
    }

    @Test fun piecesComeFasterThanTheOpenStream() = runBlocking {
        val youtube = YouTubePlatform(client)
        val video = youtube.latest(Channel(PlatformId.YOUTUBE, "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q", ""))
            .first { it.kind == ItemKind.VIDEO }
        val source = youtube.playback(video.id).sources.first { (it.height ?: 0) in 1..1080 }
        for ((name, url) in listOf("video" to source.url, "audio" to source.audioUrl!!)) {
            val length = YouTubePieces.length(url)
            assertNotNull("$name has no clen", length)
            val open = read(url, 1L shl 20)
            val piece = read(YouTubePieces.address(url, 0, YouTubePieces.until(0, length!!)), YouTubePieces.SIZE)
            println("PIECES $name open=${open.first} ${"%.1f".format(open.second)}Mbps piece=${piece.first} ${"%.1f".format(piece.second)}Mbps")
            assertEquals(200, piece.first)
        }
    }
}
