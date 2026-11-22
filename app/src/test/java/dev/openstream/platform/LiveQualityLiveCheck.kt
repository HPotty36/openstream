package dev.openstream.platform

import dev.openstream.platform.chzzk.ChzzkPlatform
import dev.openstream.platform.twitch.TwitchPlatform
import dev.openstream.platform.youtube.USER_AGENT
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Which qualities a live broadcast offers without an account: the variants in the HLS playlist the
 * player is given. Runs only with -Plive=true, on the CHZZK and Twitch channels of .superpowers/user-backup.json.
 */
class LiveQualityLiveCheck {
    private val client = OkHttpClient()

    @Test fun variantsOfLiveBroadcasts() = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        val file = File("../.superpowers/user-backup.json")
        assumeTrue(file.exists())
        val channels = Json.parseToJsonElement(file.readText()).jsonObject["subscriptions"]!!.jsonArray.map {
            val o = it.jsonObject
            Channel(PlatformId.entries.first { p -> p.id == o["platform"]!!.jsonPrimitive.content }, o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content)
        }.filter { it.platform != PlatformId.YOUTUBE }
        val platforms = mapOf(PlatformId.CHZZK to ChzzkPlatform(client), PlatformId.TWITCH to TwitchPlatform(client))
        for (channel in channels) {
            val platform = platforms.getValue(channel.platform)
            val live = runCatching { platform.latest(channel) }.getOrNull()?.firstOrNull { it.kind == ItemKind.LIVE }
            if (live == null) {
                println("QUALITY ${channel.platform.id} ${channel.id}: not live")
                continue
            }
            val source = platform.playback(live.id).sources.first()
            val master = client.newCall(Request.Builder().url(source.url).header("User-Agent", USER_AGENT).build()).execute().use { it.body.string() }
            val variants = master.lines().filter { it.startsWith("#EXT-X-STREAM-INF") }.map { line ->
                val resolution = Regex("RESOLUTION=(\\d+x\\d+)").find(line)?.groupValues?.get(1)
                val rate = Regex("FRAME-RATE=([\\d.]+)").find(line)?.groupValues?.get(1)
                val bandwidth = Regex("[^-]BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLong()?.let { "%.1fMbps".format(it / 1e6) }
                val name = Regex("VIDEO=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                "${resolution ?: "audio"}@${rate ?: "?"} $bandwidth ${name ?: ""}".trim()
            }
            println("QUALITY ${channel.platform.id} ${channel.id} '${live.title.take(20)}': ${variants.joinToString(" | ")}".replace(Regex("[^\\x20-\\x7E|]"), "?"))
            reachTop(source.url, master)
        }
    }

    /** Fetches the highest variant's playlist and its newest segment, to see that it plays and is not only listed. */
    private fun reachTop(masterUrl: String, master: String) {
        val lines = master.lines()
        val top = lines.withIndex().filter { it.value.startsWith("#EXT-X-STREAM-INF") }
            .maxByOrNull { Regex("RESOLUTION=\\d+x(\\d+)").find(it.value)?.groupValues?.get(1)?.toInt() ?: 0 } ?: return
        val variantUrl = masterUrl.toHttpUrlOrNull()!!.resolve(lines[top.index + 1].trim())!!
        val playlist = client.newCall(Request.Builder().url(variantUrl).header("User-Agent", USER_AGENT).build()).execute().use { it.code to it.body.string() }
        val segment = playlist.second.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") }?.let { variantUrl.resolve(it.trim()) }
        val segmentCode = segment?.let { client.newCall(Request.Builder().url(it).header("User-Agent", USER_AGENT).build()).execute().use { r -> "${r.code} ${r.body.bytes().size shr 10}KB" } }
        println("QUALITY   top ${Regex("RESOLUTION=(\\d+x\\d+)").find(top.value)?.groupValues?.get(1)}: playlist ${playlist.first}, segment $segmentCode")
    }

    @Test fun aTwitchBroadcastInFullHd() = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        val twitch = TwitchPlatform(client)
        val lives = twitch.search("league of legends").items.filterIsInstance<SearchResult.OfItem>().map { it.item }.take(6)
        for (live in lives) {
            val source = twitch.playback(live.id).sources.first()
            val master = client.newCall(Request.Builder().url(source.url).header("User-Agent", USER_AGENT).build()).execute().use { it.body.string() }
            val names = master.lines().filter { it.startsWith("#EXT-X-MEDIA") }.mapNotNull { Regex("NAME=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            println("QUALITY twitch ${live.channel.id}: ${names.joinToString(", ")}")
            if (names.any { it.startsWith("1080") }) {
                reachTop(source.url, master)
                break
            }
        }
    }
}
