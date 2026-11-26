package dev.openstream.platform

import dev.openstream.platform.chzzk.ChzzkPlatform
import dev.openstream.platform.twitch.TwitchPlatform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Reads real chat until a message with emotes comes; only with -Plive=true. */
class ChatEmotesLiveCheck {
    private val client = OkHttpClient()

    @Before fun onlyWhenAsked() = assumeTrue(System.getProperty("live") == "true")

    private suspend fun firstWithEmotes(chat: Flow<ChatMessage>): Pair<ChatMessage?, Int> {
        var seen = 0
        var found: ChatMessage? = null
        withTimeoutOrNull(90_000) {
            chat.takeWhile { found == null }.collect { message ->
                seen++
                if (message.emotes.isNotEmpty()) found = message
            }
        }
        return found to seen
    }

    private fun check(label: String, message: ChatMessage?, seen: Int) {
        println("LIVE $label emotes: after $seen messages: '${message?.text}' ${message?.emotes}".replace(Regex("[^\\x20-\\x7E]"), "?"))
        assertTrue("no message with emotes", message != null)
        message!!.emotes.forEach { assertTrue(it.range.last < message.text.length && it.url.startsWith("https://")) }
        client.newCall(Request.Builder().url(message.emotes.first().url).build()).execute().use {
            assertEquals(200, it.code)
            println("LIVE $label emote type: ${it.header("Content-Type")}")
        }
    }

    @Test fun chzzk() = runBlocking {
        val lives = client.newCall(Request.Builder().url("https://api.chzzk.naver.com/service/v1/lives?size=1&sortType=POPULAR").header("User-Agent", "Mozilla/5.0").build())
            .execute().use { Json.parseToJsonElement(it.body.string()) }
        val channelId = lives.jsonObject["content"]!!.jsonObject["data"]!!.jsonArray[0].jsonObject["channel"]!!.jsonObject["channelId"]!!.jsonPrimitive.content
        val (message, seen) = firstWithEmotes(ChzzkPlatform(client).liveChat("live:$channelId"))
        check("chzzk", message, seen)
    }

    // The busiest of a few large channels that are live now; a quiet chat can go minutes without an emote.
    @Test fun twitch() = runBlocking {
        val twitch = TwitchPlatform(client)
        val lives = listOf("just chatting", "grand theft auto", "league of legends", "valorant").flatMap { query ->
            twitch.search(query).items.filterIsInstance<SearchResult.OfItem>().map { it.item }.filter { it.kind == ItemKind.LIVE }.take(3)
        }
        var best: Pair<ChatMessage?, Int> = null to 0
        for (live in lives) {
            best = withTimeoutOrNull(30_000) { firstWithEmotes(twitch.liveChat(live.id)) } ?: continue
            if (best.first != null) break
        }
        check("twitch", best.first, best.second)
    }
}
