package dev.openstream.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class TimestampsTest {
    private fun times(text: String, durationMs: Long? = null) = timestamps(text, durationMs).map { it.ms }

    @Test fun readsMinutesAndHours() {
        assertEquals(listOf(83_000L), times("1:23 여기 진짜 웃김"))
        assertEquals(listOf(3_723_000L), times("01:02:03"))
        assertEquals(listOf(225_000L, 250_000L), times("3:45~4:10 구간"))
        assertEquals(listOf(4_530_000L), times("75:30에 나옴"))
    }

    @Test fun marksWhereEachTimeSits() {
        val text = "인트로 0:15, 본론 12:05"
        val stamps = timestamps(text)
        assertEquals(listOf("0:15", "12:05"), stamps.map { text.substring(it.range) })
    }

    @Test fun leavesOutWhatIsNotATime() {
        assertEquals(emptyList<Long>(), times("3:4"))
        assertEquals(emptyList<Long>(), times("2:61"))
        assertEquals(emptyList<Long>(), times("1:60:00"))
        assertEquals(emptyList<Long>(), times("12:34:56:78"))
        assertEquals(emptyList<Long>(), times("비율 1234:56"))
    }

    @Test fun leavesOutTimesPastTheEnd() {
        // In a five-minute video "10:30" is a time of day, not a place in it.
        assertEquals(listOf(90_000L), times("1:30에 나오고 10:30에 끝남", durationMs = 300_000L))
    }
}
