@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.openstream.watch

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import kotlin.math.roundToInt

/** One quality of a stream that comes in several (a live broadcast, a replay): its height and frame rate. */
data class Variant(val height: Int, val fps: Int) {
    /** "1080p60" for a fast frame rate, "480p" otherwise, the way the platforms name them. */
    val label: String get() = if (fps > 30) "${height}p$fps" else "${height}p"
}

/** What the viewer picked. [Best] is the default: the highest quality, from the first second. */
sealed interface QualityChoice {
    data object Best : QualityChoice
    /** The player's own pick, by how fast the connection is. */
    data object Auto : QualityChoice
    data class Fixed(val variant: Variant) : QualityChoice
}

private fun Format.variant(): Variant? =
    if (height <= 0) null else Variant(height, if (frameRate == Format.NO_VALUE.toFloat()) 0 else frameRate.roundToInt())

/** The video qualities among [formats], best first, one per height and frame rate; sound-only ones are left out. */
fun variantsOf(formats: List<Format>): List<Variant> =
    formats.mapNotNull { it.variant() }.distinct().sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.fps })

/** The video qualities of what is playing. */
fun variantsOf(tracks: Tracks): List<Variant> = variantsOf(
    tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { group.getTrackFormat(it) }
    },
)

/** The variant the menu marks; none for [QualityChoice.Auto]. */
fun chosenVariant(choice: QualityChoice, variants: List<Variant>): Variant? = when (choice) {
    QualityChoice.Best -> variants.firstOrNull()
    QualityChoice.Auto -> null
    is QualityChoice.Fixed -> choice.variant
}

/** The words on the quality button. */
fun qualityLabel(choice: QualityChoice, variants: List<Variant>, playingHeight: Int): String =
    chosenVariant(choice, variants)?.label ?: if (playingHeight > 0) "자동 · ${playingHeight}p" else "자동"

/** Points the player at [choice]. A fixed one waits for the stream's qualities to be known; call again then. */
fun Player.applyQuality(choice: QualityChoice) {
    val params = trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
    when (choice) {
        QualityChoice.Best -> params.setForceHighestSupportedBitrate(true)
        QualityChoice.Auto -> params.setForceHighestSupportedBitrate(false)
        is QualityChoice.Fixed -> {
            params.setForceHighestSupportedBitrate(false)
            currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.firstNotNullOfOrNull { group ->
                (0 until group.length).firstOrNull { group.getTrackFormat(it).variant() == choice.variant }
                    ?.let { TrackSelectionOverride(group.mediaTrackGroup, it) }
            }?.let(params::addOverride)
        }
    }
    trackSelectionParameters = params.build()
}
