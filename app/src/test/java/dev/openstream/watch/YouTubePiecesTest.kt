package dev.openstream.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePiecesTest {
    private val stream = "https://rr3---sn-ab5l6nrz.googlevideo.com/videoplayback?expire=1&itag=299&clen=238925308&dur=2025.0&n=abc"

    @Test fun readsTheLengthOnlyFromYouTubeStreamsThatCarryIt() {
        assertEquals(238925308L, YouTubePieces.length(stream))
        assertNull(YouTubePieces.length("https://rr3---sn-ab5l6nrz.googlevideo.com/videoplayback?itag=299&dur=2025.0"))
        assertNull(YouTubePieces.length("https://example.com/video.mp4?clen=100"))
        assertNull(YouTubePieces.length("https://googlevideo.com.example.net/v?clen=100"))
    }

    @Test fun asksForEachPieceInTheAddress() {
        assertEquals("$stream&range=0-4194303", YouTubePieces.address(stream, 0, 4194304))
        assertEquals("https://x.googlevideo.com/v?range=10-19", YouTubePieces.address("https://x.googlevideo.com/v", 10, 20))
    }

    @Test fun piecesStopAtTheEnd() {
        assertEquals(4L shl 20, YouTubePieces.until(0, 100L shl 20))
        assertEquals(1000L, YouTubePieces.until(900, 1000))
        assertEquals(30L, YouTubePieces.until(10, 30, size = 64))
    }
}
