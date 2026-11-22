package dev.openstream.sponsor

import dev.openstream.platform.youtube.USER_AGENT
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A stretch of a video that viewers marked as a paid promotion. */
data class SkipSegment(val startMs: Long, val endMs: Long)

private val VIDEO_ID = Regex("(?:[?&]v=|youtu\\.be/|/shorts/)([\\w-]{11})")

fun youtubeVideoId(url: String): String? = VIDEO_ID.find(url)?.groupValues?.get(1)

/** Picks the segments of [videoId] out of an answer that covers every video sharing its hash prefix. */
fun parseSegments(json: String, videoId: String): List<SkipSegment> {
    val videos = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return emptyList()
    val video = videos.mapNotNull { it as? JsonObject }
        .firstOrNull { (it["videoID"] as? JsonPrimitive)?.contentOrNull == videoId } ?: return emptyList()
    return (video["segments"] as? JsonArray).orEmpty().mapNotNull { element ->
        val times = ((element as? JsonObject)?.get("segment") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        if (times == null || times.size != 2 || times[1] <= times[0]) null
        else SkipSegment((times[0] * 1000).toLong(), (times[1] * 1000).toLong())
    }.sortedBy { it.startMs }
}

/**
 * Asks SponsorBlock, a public database kept by viewers, where the sponsor reads of a YouTube video are.
 * Only the first characters of the id's hash are sent, so the server cannot tell which video is watched.
 */
class SponsorBlock(private val client: OkHttpClient) {
    suspend fun segments(videoId: String): List<SkipSegment> = withContext(Dispatchers.IO) {
        val hash = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray()).joinToString("") { "%02x".format(it) }
        val categories = URLEncoder.encode("[\"sponsor\"]", "UTF-8")
        val actions = URLEncoder.encode("[\"skip\"]", "UTF-8")
        val request = Request.Builder()
            .url("https://sponsor.ajay.app/api/skipSegments/${hash.take(4)}?categories=$categories&actionTypes=$actions")
            .header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            // 404 is the ordinary answer for a video nobody has marked.
            if (!response.isSuccessful) emptyList() else parseSegments(response.body.string(), videoId)
        }
    }
}
