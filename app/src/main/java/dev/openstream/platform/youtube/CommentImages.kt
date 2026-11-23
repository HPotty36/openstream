package dev.openstream.platform.youtube

import dev.openstream.platform.CommentImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * GIFs people attach to YouTube comments. The extractor leaves them out, so comment answers are read here
 * on their way through [OkHttpDownloader], and each GIF is kept by its comment's id until the comments
 * made from the same answer look it up.
 */
object CommentImages {
    private const val LIMIT = 2000
    private val byComment = object : LinkedHashMap<String, CommentImage>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CommentImage>) = size > LIMIT
    }

    fun read(answer: String) {
        val found = commentImages(answer)
        if (found.isNotEmpty()) synchronized(byComment) { byComment.putAll(found) }
    }

    operator fun get(commentId: String): CommentImage? = synchronized(byComment) { byComment[commentId] }
}

private const val ATTACHMENT = "commentImageAttachmentViewModel"

/**
 * The GIFs in one comment answer, by comment id. A comment names its surface by `commentSurfaceKey`, and the
 * surface, one of the answer's mutations, carries the GIF.
 */
fun commentImages(answer: String): Map<String, CommentImage> {
    if (ATTACHMENT !in answer) return emptyMap()
    val root = runCatching { Json.parseToJsonElement(answer) }.getOrNull() as? JsonObject ?: return emptyMap()
    val surfaces = HashMap<String, CommentImage>()
    root.child("frameworkUpdates")?.child("entityBatchUpdate")?.list("mutations")?.forEach { mutation ->
        val key = mutation.text("entityKey") ?: return@forEach
        mutation.child("payload")?.child("commentSurfaceEntityPayload")?.let(::imageOf)?.let { surfaces[key] = it }
    }
    if (surfaces.isEmpty()) return emptyMap()
    val found = HashMap<String, CommentImage>()
    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                val id = element.text("commentId")
                val image = element.text("commentSurfaceKey")?.let(surfaces::get)
                if (id != null && image != null) found[id] = image
                element.values.forEach(::walk)
            }
            is JsonArray -> element.forEach(::walk)
            else -> {}
        }
    }
    walk(root)
    return found
}

private fun imageOf(surface: JsonObject): CommentImage? {
    val view = surface.child("imageAttachmentViewModel")?.child(ATTACHMENT) ?: return null
    val source = view.child("image")?.list("sources")?.maxByOrNull { it.int("width") ?: 0 } ?: return null
    val url = source.text("url") ?: return null
    val width = view.int("width") ?: source.int("width") ?: return null
    val height = view.int("height") ?: source.int("height") ?: return null
    return CommentImage(url, width, height).takeIf { width > 0 && height > 0 }
}

private fun JsonObject.child(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
