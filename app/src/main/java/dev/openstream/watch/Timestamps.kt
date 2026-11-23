package dev.openstream.watch

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/** A time written in a description or a comment ("1:23", "01:02:03"), where it sits in the text, and the moment it names. */
data class Stamp(val range: IntRange, val ms: Long)

// Minutes and seconds, hours before them if given; not part of a longer run of digits and colons.
private val STAMP = Regex("(?<![\\d:])(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?![\\d:])")

/** The times in [text]. Ones past [durationMs] are left out: in a short video "10:30" is a time of day. */
fun timestamps(text: String, durationMs: Long? = null): List<Stamp> = STAMP.findAll(text).mapNotNull { match ->
    val (hours, minutes, seconds) = match.destructured
    if (seconds.toInt() >= 60 || (hours.isNotEmpty() && minutes.toInt() >= 60)) return@mapNotNull null
    val ms = ((hours.toIntOrNull() ?: 0) * 3600L + minutes.toInt() * 60L + seconds.toInt()) * 1000
    if (durationMs != null && ms > durationMs) null else Stamp(match.range, ms)
}.toList()

/** [text] with each time in it pressable, jumping there through [onSeek]; plain text when there is nowhere to jump. */
@Composable
fun withTimestamps(text: String, durationMs: Long?, onSeek: ((Long) -> Unit)?): AnnotatedString {
    val seek by rememberUpdatedState(onSeek)
    val color = MaterialTheme.colorScheme.primary
    val seekable = onSeek != null
    return remember(text, durationMs, color, seekable) {
        if (!seekable) return@remember AnnotatedString(text)
        buildAnnotatedString {
            append(text)
            timestamps(text, durationMs).forEach { stamp ->
                addLink(
                    // Accent and a heavier weight, so a time stands out in a translation that is in the accent already.
                    LinkAnnotation.Clickable("t${stamp.ms}", TextLinkStyles(SpanStyle(color = color, fontWeight = FontWeight.SemiBold))) {
                        seek?.invoke(stamp.ms)
                    },
                    stamp.range.first,
                    stamp.range.last + 1,
                )
            }
        }
    }
}
