package dev.openstream.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {
    @Test fun offersTranslationOnlyForForeignText() {
        assertTrue(needsTranslation("This is the best video ever"))
        assertTrue(needsTranslation("これは最高です"))
        assertTrue(needsTranslation("lol 진짜"))
        assertFalse(needsTranslation("이 영상 진짜 좋네요"))
        assertFalse(needsTranslation("LCK 결승 진짜 재밌었다"))
        assertFalse(needsTranslation("ㅋㅋㅋㅋ"))
        assertFalse(needsTranslation("😂😂 123"))
    }

    @Test fun readsTheReplyEvenWithTextAroundIt() {
        assertEquals(listOf("안녕", "고마워 [진짜]"), parseTranslations("Here:\n[\"안녕\", \"고마워 [진짜]\"]", 2))
    }

    @Test fun rejectsAReplyOfTheWrongLength() {
        assertThrows(TranslationException::class.java) { parseTranslations("[\"안녕\"]", 2) }
        assertThrows(TranslationException::class.java) { parseTranslations("sorry, no", 1) }
    }
}
