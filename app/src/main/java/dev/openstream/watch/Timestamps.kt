package dev.openstream.watch

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
