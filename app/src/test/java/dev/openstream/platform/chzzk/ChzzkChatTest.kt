package dev.openstream.platform.chzzk

import dev.openstream.platform.ChatEmote
import org.junit.Assert.assertEquals
import org.junit.Test

class ChzzkChatTest {
    // As the chat server sent them: slashes escaped in one, a query on the address in another.
    private val extras = """{"chatType":"STREAMING","osType":"PC","emojis":{"d_62":"https:\/\/ssl.pstatic.net\/static\/nng\/glive\/icon\/b_22.gif",""" +
        """"tabi8":"https://nng-phinf.pstatic.net/glive/subscription/emoji/a6c4/1/tabi8_17.png?type=f60_60"}}"""

    @Test fun emojisBecomePictures() {
        val (text, emotes) = chzzkChatText("ㅋㅋ{:d_62:} 좋다 {:tabi8:}{:tabi8:}", extras)
        assertEquals("ㅋㅋd_62 좋다 tabi8tabi8", text)
        assertEquals(
            listOf(
                ChatEmote(2..5, "https://ssl.pstatic.net/static/nng/glive/icon/b_22.gif"),
                ChatEmote(10..14, "https://nng-phinf.pstatic.net/glive/subscription/emoji/a6c4/1/tabi8_17.png?type=f60_60"),
                ChatEmote(15..19, "https://nng-phinf.pstatic.net/glive/subscription/emoji/a6c4/1/tabi8_17.png?type=f60_60"),
            ),
            emotes,
        )
    }

    @Test fun anEmojiWithoutAPictureStaysAsWritten() {
        assertEquals("{:unknown:} 안녕" to emptyList<ChatEmote>(), chzzkChatText("{:unknown:} 안녕", extras))
        assertEquals("안녕" to emptyList<ChatEmote>(), chzzkChatText(" 안녕 ", null))
        assertEquals("{:d_62:}" to emptyList<ChatEmote>(), chzzkChatText("{:d_62:}", "not json"))
    }
}
