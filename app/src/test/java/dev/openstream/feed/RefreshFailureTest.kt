package dev.openstream.feed

import dev.openstream.platform.Channel
import dev.openstream.platform.ChannelGoneException
import dev.openstream.platform.PlatformException
import dev.openstream.platform.PlatformId
import dev.openstream.platform.youtube.goneReason
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException

class RefreshFailureTest {
    private val channel = Channel(PlatformId.YOUTUBE, "https://www.youtube.com/channel/UCx", "x")

    @Test fun aChannelThatIsGoneSaysWhyAndCanBeCleared() {
        val failure = refreshFailure(channel, ChannelGoneException("YouTube 서비스 약관을 위반하여 계정이 해지되었습니다."))
        assertEquals("YouTube 서비스 약관을 위반하여 계정이 해지되었습니다.", failure.reason)
        assertTrue(failure.gone)
    }

    @Test fun aPassingProblemIsNotTakenForAGoneChannel() {
        val offline = refreshFailure(channel, IOException("timeout"))
        assertEquals("네트워크에 연결할 수 없습니다.", offline.reason)
        assertFalse(offline.gone)
        val busy = refreshFailure(channel, PlatformException("치지직이 응답하지 않습니다."))
        assertEquals("치지직이 응답하지 않습니다.", busy.reason)
        assertFalse(busy.gone)
    }

    @Test fun youTubesOwnWordsComeOutOfItsError() {
        assertEquals(
            "제3자의 상표권 침해 신고로 인해 계정이 해지되었습니다.",
            goneReason(ContentNotAvailableException("Got error: \"제3자의 상표권 침해 신고로 인해 계정이 해지되었습니다.\"")),
        )
        assertEquals("YouTube에서 볼 수 없는 채널입니다.", goneReason(ContentNotAvailableException("This channel does not exist.")))
    }

    @Test fun aVideoBlockedHereSaysSo() {
        val blocked = ContentNotAvailableException("Got error UNPLAYABLE: \"업로더가 동영상을 해당 국가에서 볼 수 있도록 설정하지 않았습니다.\"")
        assertEquals("업로더가 동영상을 해당 국가에서 볼 수 있도록 설정하지 않았습니다.", dev.openstream.ui.errorText(blocked))
        assertEquals("볼 수 없는 영상입니다.", dev.openstream.ui.errorText(ContentNotAvailableException("Unknown")))
    }
}
