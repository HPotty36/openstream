package dev.openstream.platform

import dev.openstream.platform.chzzk.ChzzkPlatform
import dev.openstream.platform.twitch.TwitchPlatform
import dev.openstream.platform.youtube.YouTubePlatform
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Refreshes every channel of a real backup the way the home screen does, 8 at a time, and lists the
 * ones that fail. Runs only with -Plive=true and a backup at .superpowers/user-backup.json.
 */
class RefreshLiveCheck {
    private val client = OkHttpClient()

    @Test fun everySubscriptionRefreshes() = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        val file = File("../.superpowers/user-backup.json")
        assumeTrue(file.exists())
        val channels = Json.parseToJsonElement(file.readText()).jsonObject["subscriptions"]!!.jsonArray.map {
            val o = it.jsonObject
            Channel(PlatformId.entries.first { p -> p.id == o["platform"]!!.jsonPrimitive.content }, o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content)
        }
        val platforms = mapOf(
            PlatformId.YOUTUBE to YouTubePlatform(client),
            PlatformId.CHZZK to ChzzkPlatform(client),
            PlatformId.TWITCH to TwitchPlatform(client),
        )
        val failures = ConcurrentHashMap<Channel, Throwable>()
        val gate = Semaphore(8)
        val start = System.currentTimeMillis()
        coroutineScope {
            channels.map { channel ->
                async { gate.withPermit { runCatching { platforms.getValue(channel.platform).latest(channel) }.onFailure { failures[channel] = it } } }
            }.awaitAll()
        }
        println("REFRESH ${channels.size} channels in ${(System.currentTimeMillis() - start) / 1000}s, ${failures.size} failed")
        // What the list behind "채널 N개를 불러오지 못함" will show.
        failures.forEach { (channel, e) ->
            val failure = dev.openstream.feed.refreshFailure(channel, e)
            println("REFRESH FAIL ${channel.platform.id} '${channel.name}' gone=${failure.gone}: ${failure.reason} (${e::class.simpleName})")
        }
    }
}
