package dev.openstream.platform.twitch

import dev.openstream.platform.ChatMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Listens to busy chats for a real GIF message; only with -Plive=true, and it can take two minutes. */
class TwitchGifLiveCheck {
    @Before fun onlyWhenAsked() = assumeTrue(System.getProperty("live") == "true")

    private val channels = listOf(
        "kaicenat", "xqc", "jynxzi", "caseoh_", "zackrawrr", "hasanabi", "summit1g", "tarik", "shroud", "ibai",
        "auronplay", "elmiillor", "kamet0", "gaules", "lirik", "sodapoppin", "plaqueboymax", "fanum", "lacy",
        "emiru", "stableronaldo", "theburntpeanut", "ohnepixel", "papaplatte", "zoil", "loltyler1", "nmplol",
    )

    @Test fun gifsArriveWithTheirAddress() {
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        var raw: String? = null
        var found: ChatMessage? = null
        val done = CountDownLatch(1)
        val socket = client.newWebSocket(Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("CAP REQ :twitch.tv/tags")
                webSocket.send("NICK justinfan${(10000..99999).random()}")
                webSocket.send("JOIN " + channels.joinToString(",") { "#$it" })
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                text.split("\r\n").forEach { line ->
                    if (line.startsWith("PING")) webSocket.send("PONG :tmi.twitch.tv")
                    if (";gifs=" in line && !line.contains(";gifs=;") && " PRIVMSG " in line && found == null) {
                        raw = line
                        found = parseChatLine(line)
                        done.countDown()
                    }
                }
            }
        })
        done.await(150, TimeUnit.SECONDS)
        socket.close(1000, null)
        println("LIVE twitch gif raw: ${raw?.replace(Regex("[^\\x20-\\x7E]"), "?")}")
        println("LIVE twitch gif: ${found?.let { "${it.author} '${it.text}' ${it.gifs}" }?.replace(Regex("[^\\x20-\\x7E]"), "?")}")
        assumeTrue("no GIF in two and a half minutes", found != null)
        val message = found!!
        assertTrue(message.gifs.isNotEmpty() && message.gifs.all { it.startsWith("https://") })
        assertTrue("label left in the text: '${message.text}'", !message.text.contains(" GIF by "))
        client.newCall(Request.Builder().url(message.gifs.first()).build()).execute().use {
            assertEquals(200, it.code)
            println("LIVE twitch gif type: ${it.header("Content-Type")}")
        }
    }
}
