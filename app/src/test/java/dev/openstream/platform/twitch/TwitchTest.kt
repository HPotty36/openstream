package dev.openstream.platform.twitch

import dev.openstream.platform.ChatMessage
import dev.openstream.platform.ItemKind
import dev.openstream.platform.SearchResult
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TwitchTest {
    @Test fun readsChatLines() {
        val line = "@badge-info=;color=#1E90FF;display-name=Foo_Bar;emotes=;id=abc-123;mod=0 :foo_bar!foo_bar@foo_bar.tmi.twitch.tv PRIVMSG #chan :hello : world"
        assertEquals(ChatMessage("abc-123", "Foo_Bar", "hello : world"), parseChatLine(line))
        assertEquals("waves", parseChatLine(":foo!foo@foo.tmi.twitch.tv PRIVMSG #chan :\u0001ACTION waves\u0001")?.text)
        assertEquals("foo", parseChatLine(":foo!foo@foo.tmi.twitch.tv PRIVMSG #chan :hi")?.author)
        assertNull(parseChatLine(":tmi.twitch.tv 001 justinfan1 :Welcome, GLHF!"))
        assertNull(parseChatLine("PING :tmi.twitch.tv"))
    }

    // Twitch's own example: the GIF's label fills the message, and the tag says where it sits.
    @Test fun readsChatGifs() {
        val gif = "https://media4.giphy.com/media/joSNxeswxuc74Juo8X/giphy.gif?cid=095d7a5dzizsiwgabonagkmigggv8v1spfai91ac3x0dsiy0&ep=v1_gifs_trending&rid=giphy.gif&ct=g"
        val line = "@badge-info=subscriber/30;badges=broadcaster/1,subscriber/0;color=#033700;display-name=TwitchDev;emotes=;" +
            "first-msg=0;flags=;gifs=0-33|joSNxeswxuc74Juo8X|$gif;id=401abf17-7e99-45d6-9bdf-43934e839327;mod=0;" +
            "returning-chatter=0;room-id=12826;subscriber=1;tmi-sent-ts=1783632907018;turbo=0;user-id=141981764;user-type= " +
            ":twitchdev!twitchdev@twitchdev.tmi.twitch.tv PRIVMSG #twitch :[Y A Y Yes GIF by Djemilah Birnie]"
        assertEquals(ChatMessage("401abf17-7e99-45d6-9bdf-43934e839327", "TwitchDev", "", listOf(gif)), parseChatLine(line))

        // Positions count characters, so an emoji before the label takes one place, not two.
        val mixed = "@display-name=a;gifs=2-8|x|https://media.giphy.com/x.gif;id=m :a!a@a.tmi.twitch.tv PRIVMSG #c :😀 [A GIF] nice"
        assertEquals(ChatMessage("m", "a", "😀  nice", listOf("https://media.giphy.com/x.gif")), parseChatLine(mixed))
    }

    /** Talks to the real Twitch, so it only runs with -Plive=true. */
    @Test fun searchPlaybackChat() = runBlocking {
        assumeTrue(System.getProperty("live") == "true")
        val client = OkHttpClient()
        val twitch = TwitchPlatform(client)

        val results = twitch.search("league of legends")
        val lives = results.items.filterIsInstance<SearchResult.OfItem>().map { it.item }
        println("LIVE twitch search: channels=${results.items.count { it is SearchResult.OfChannel }} live=${lives.size}")
        assertTrue(results.items.isNotEmpty())

        val live = lives.first { it.kind == ItemKind.LIVE }
        val playback = twitch.playback(live.id)
        val status = client.newCall(Request.Builder().url(playback.sources.first().url).build()).execute().use { it.code }
        println("LIVE twitch live by '${playback.item.channel.name}' (${playback.description}): playlist status=$status")
        assertEquals(200, status)

        val channel = twitch.channel(live.channel.id)
        val latest = twitch.latest(channel)
        println("LIVE twitch channel '${channel.name}': latest=${latest.size} first=${latest.firstOrNull()?.kind}")

        // The most watched broadcast, where chat is certain to move.
        val top = client.newCall(
            Request.Builder().url("https://gql.twitch.tv/gql").header("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko")
                .post("""{"query":"query{streams(first:1){edges{node{broadcaster{login}}}}}"}""".toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { it.body.string() }
        val busiest = Regex("\"login\":\"([^\"]+)\"").find(top)!!.groupValues[1]
        val chat = runCatching { withTimeoutOrNull(40_000) { twitch.liveChat("live:$busiest").take(2).toList() } }
        assertEquals(2, chat.getOrNull()?.size)
        println("LIVE twitch chat: ${chat.map { list -> list?.map { "${it.author}: ${it.text.take(20)}" } }}".replace(Regex("[^\\x20-\\x7E]"), "?"))
    }
}
