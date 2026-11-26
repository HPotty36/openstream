package dev.openstream.platform

/**
 * A chat message's text from [text]: [drop] left out and [emotes] marked, both counted in characters (code
 * points), the way Twitch counts. The marks come back as positions in the trimmed result, as the screen
 * needs them. A range that does not fit the text is ignored.
 */
fun chatText(text: String, drop: List<IntRange> = emptyList(), emotes: List<Pair<IntRange, String>> = emptyList()): Pair<String, List<ChatEmote>> {
    val points = text.codePoints().toArray()
    val fits = { range: IntRange -> range.first >= 0 && range.first <= range.last && range.last < points.size }
    val dropped = drop.filter(fits)
    val starts = emotes.filter { (range, _) -> fits(range) && dropped.none { range.first <= it.last && it.first <= range.last } }
        .associateBy { it.first.first }
    val out = StringBuilder()
    val marks = mutableListOf<ChatEmote>()
    var i = 0
    while (i < points.size) {
        val skip = dropped.firstOrNull { i in it }
        val emote = starts[i]
        when {
            skip != null -> i = skip.last + 1
            emote != null -> {
                val from = out.length
                for (j in emote.first) out.appendCodePoint(points[j])
                marks += ChatEmote(from until out.length, emote.second)
                i = emote.first.last + 1
            }
            else -> out.appendCodePoint(points[i++])
        }
    }
    return trimmed(out.toString(), marks)
}

/** [text] trimmed, with [marks] moved along and any that fell off dropped. */
fun trimmed(text: String, marks: List<ChatEmote>): Pair<String, List<ChatEmote>> {
    val lead = text.length - text.trimStart().length
    val result = text.trim()
    return result to marks.map { ChatEmote((it.range.first - lead)..(it.range.last - lead), it.url) }
        .filter { it.range.first >= 0 && it.range.last < result.length }
}

/** The words of a message without its emotes, for translating: an emote-only message has none. */
val ChatMessage.words: String
    get() = if (emotes.isEmpty()) text else buildString {
        var last = 0
        emotes.sortedBy { it.range.first }.forEach { emote ->
            if (emote.range.first >= last) {
                append(text, last, emote.range.first)
                append(' ')
                last = emote.range.last + 1
            }
        }
        append(text, last, text.length)
    }.replace(Regex("\\s+"), " ").trim()
