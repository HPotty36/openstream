package dev.openstream.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTextTest {
    @Test fun translationSeesOnlyTheWords() {
        val mixed = ChatMessage("1", "a", "Kappa this is great Kappa", emotes = listOf(ChatEmote(0..4, "u"), ChatEmote(20..24, "u")))
        assertEquals("this is great", mixed.words)
        val emotesOnly = ChatMessage("2", "a", "tabi8tabi8", emotes = listOf(ChatEmote(0..4, "u"), ChatEmote(5..9, "u")))
        assertEquals("", emotesOnly.words)
        assertEquals("plain text", ChatMessage("3", "a", "plain text").words)
    }

    @Test fun trimmingMovesTheMarks() {
        val (text, marks) = chatText("  hi Kappa ", emotes = listOf(5..9 to "u"))
        assertEquals("hi Kappa", text)
        assertEquals(listOf(ChatEmote(3..7, "u")), marks)
    }
}
