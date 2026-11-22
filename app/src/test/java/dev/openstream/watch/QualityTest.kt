package dev.openstream.watch

import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityTest {
    private fun video(width: Int, height: Int, fps: Float) =
        Format.Builder().setWidth(width).setHeight(height).setFrameRate(fps).setSampleMimeType("video/avc").build()

    private val twitch = listOf(
        video(1280, 720, 60f),
        video(1920, 1080, 60f),
        video(852, 480, 30f),
        video(640, 360, 30f),
        video(1280, 720, 60f),
        Format.Builder().setSampleMimeType("audio/mp4a-latm").build(),
    )

    @Test fun variantsAreBestFirstOncePerHeightAndRate() {
        assertEquals(listOf("1080p60", "720p60", "480p", "360p"), variantsOf(twitch).map { it.label })
    }

    @Test fun aRateTheStreamDoesNotGiveIsLeftOff() {
        assertEquals("480p", Variant(480, 0).label)
        assertEquals("1080p", Variant(1080, 30).label)
    }

    @Test fun theButtonSaysWhatIsPlaying() {
        val variants = variantsOf(twitch)
        assertEquals("1080p60", qualityLabel(QualityChoice.Best, variants, playingHeight = 1080))
        assertEquals("480p", qualityLabel(QualityChoice.Fixed(Variant(480, 30)), variants, playingHeight = 480))
        assertEquals("자동 · 720p", qualityLabel(QualityChoice.Auto, variants, playingHeight = 720))
        assertEquals("자동", qualityLabel(QualityChoice.Auto, variants, playingHeight = 0))
    }

    @Test fun theMenuMarksTheTopVariantForBest() {
        val variants = variantsOf(twitch)
        assertEquals(Variant(1080, 60), chosenVariant(QualityChoice.Best, variants))
        assertEquals(Variant(360, 30), chosenVariant(QualityChoice.Fixed(Variant(360, 30)), variants))
        assertEquals(null, chosenVariant(QualityChoice.Auto, variants))
    }
}
