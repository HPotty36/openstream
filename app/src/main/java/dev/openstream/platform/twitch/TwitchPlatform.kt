package dev.openstream.platform.twitch

import dev.openstream.platform.Channel
import dev.openstream.platform.ChannelGoneException
import dev.openstream.platform.ChannelTab
import dev.openstream.platform.ChatMessage
import dev.openstream.platform.Comment
import dev.openstream.platform.DownloadOption
import dev.openstream.platform.ItemKind
import dev.openstream.platform.Page
import dev.openstream.platform.PlatformException
import dev.openstream.platform.PlatformId
import dev.openstream.platform.Playback
import dev.openstream.platform.SearchResult
import dev.openstream.platform.Source
import dev.openstream.platform.SourceType
import dev.openstream.platform.StreamItem
import dev.openstream.platform.StreamingPlatform
import dev.openstream.platform.youtube.USER_AGENT
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

// The id Twitch's own website sends; it is what lets a visitor without an account watch.
private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
private const val USER = "id login displayName profileImageURL(width:150)"
private const val STREAM = "id title createdAt previewImageURL(width:440,height:248) game{displayName}"
private const val VIDEO = "id title lengthSeconds publishedAt previewThumbnailURL(width:440,height:248)"
private const val ACCESS = """params:{platform:"web",playerBackend:"mediaplayer",playerType:"site"}"""

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.child(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun time(text: String?): Long? = text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** One line of Twitch chat as a message, or null for every other kind of line. */
fun parseChatLine(line: String): ChatMessage? {
    val marker = line.indexOf(" PRIVMSG #")
    if (marker < 0) return null
    val text = line.indexOf(" :", marker).takeIf { it >= 0 }?.let { line.substring(it + 2) }?.takeIf { it.isNotBlank() } ?: return null
    val tags = if (line.startsWith("@")) {
        line.substring(1, line.indexOf(' ')).split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
    } else {
        emptyMap()
    }
    val login = line.substring(0, marker).substringAfterLast(" :").removePrefix(":").substringBefore('!')
    // "/me" messages arrive wrapped in a control character.
    val clean = text.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
    val gifs = chatGifs(tags["gifs"]?.let(::unescapeTag).orEmpty())
    return ChatMessage(
        tags["id"] ?: "${login}-${text.hashCode()}",
        tags["display-name"]?.takeIf { it.isNotEmpty() } ?: login,
        withoutRanges(clean, gifs.map { it.first }),
        gifs.map { it.second },
    )
}

/**
 * The "gifs" tag: `start-end|id|address`, comma separated. The address is used exactly as given, as
 * Twitch asks; a new entry starts only where a comma is followed by a position, so commas in it are safe.
 */
private fun chatGifs(tag: String): List<Pair<IntRange, String>> =
    if (tag.isEmpty()) emptyList() else tag.split(Regex(",(?=\\d+-\\d+\\|)")).mapNotNull { entry ->
        val parts = entry.split('|', limit = 3)
        val start = parts.getOrNull(0)?.substringBefore('-')?.toIntOrNull()
        val end = parts.getOrNull(0)?.substringAfter('-', "")?.toIntOrNull()
        val url = parts.getOrNull(2)?.takeIf { it.startsWith("https://") }
        if (start == null || end == null || url == null || end < start) null else (start..end) to url
    }

/** [text] without [ranges], which count characters (code points), the way Twitch counts emote positions. */
private fun withoutRanges(text: String, ranges: List<IntRange>): String {
    if (ranges.isEmpty()) return text
    val points = text.codePoints().toArray()
    if (ranges.any { it.last >= points.size }) return text
    val kept = points.indices.filter { i -> ranges.none { i in it } }
    return String(kept.map { points[it] }.toIntArray(), 0, kept.size).trim()
}

/** IRCv3 tag values escape `;`, spaces and backslashes. */
private fun unescapeTag(value: String): String = buildString {
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length) {
            when (value[i + 1]) {
                ':' -> append(';')
                's' -> append(' ')
                'r' -> append('\r')
                'n' -> append('\n')
                else -> append(value[i + 1])
            }
            i += 2
        } else {
            append(c)
            i++
        }
    }
}

/**
 * Twitch through the API its own website uses, without an account. A channel is its login name,
 * a live broadcast is "live:<login>", a past broadcast is "video:<id>".
 */
class TwitchPlatform(private val client: OkHttpClient) : StreamingPlatform {
    override val id = PlatformId.TWITCH

    private fun gql(query: String, variables: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://gql.twitch.tv/gql").post(body)
            .header("Client-ID", CLIENT_ID).header("User-Agent", USER_AGENT).build()
        val root = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            Json.parseToJsonElement(response.body.string()).jsonObject
        }
        return root.child("data") ?: throw PlatformException("Twitch가 응답하지 않습니다.")
    }

    private fun vars(name: String, value: String) = buildJsonObject { put(name, value) }

    private fun channelOf(user: JsonObject) =
        Channel(id, user.text("login").orEmpty(), user.text("displayName") ?: user.text("login").orEmpty(), user.text("profileImageURL"))

    private fun liveItem(stream: JsonObject, channel: Channel) = StreamItem(
        platform = id,
        id = "live:${channel.id}",
        kind = ItemKind.LIVE,
        title = stream.text("title").orEmpty(),
        channel = channel,
        thumbnailUrl = stream.text("previewImageURL"),
        publishedAt = time(stream.text("createdAt")),
    )

    private fun videoItem(video: JsonObject, channel: Channel) = StreamItem(
        platform = id,
        id = "video:${video.text("id")}",
        kind = ItemKind.VIDEO,
        title = video.text("title").orEmpty(),
        channel = channel,
        thumbnailUrl = video.text("previewThumbnailURL"),
        durationSeconds = (video["lengthSeconds"] as? JsonPrimitive)?.longOrNull,
        publishedAt = time(video.text("publishedAt")),
    )

    /** Channels that are live come first, as broadcasts; then every channel that matched. */
    override suspend fun search(query: String, page: String?): Page<SearchResult> = withContext(Dispatchers.IO) {
        val data = gql(
            "query(\$q:String!){searchFor(userQuery:\$q,platform:\"web\",options:{targets:[{index:CHANNEL}]}){channels{edges{item{... on User{$USER stream{$STREAM}}}}}}}",
            vars("q", query),
        )
        val users = data.child("searchFor")?.child("channels")?.list("edges").orEmpty().mapNotNull { it.child("item") }
            .filter { it.text("login") != null }
        val live = users.mapNotNull { user -> user.child("stream")?.let { SearchResult.OfItem(liveItem(it, channelOf(user))) } }
        Page(live + users.map { SearchResult.OfChannel(channelOf(it)) })
    }

    private fun user(login: String, extra: String = ""): JsonObject =
        gql("query(\$l:String!){user(login:\$l){$USER stream{$STREAM} $extra}}", vars("l", login)).child("user")
            ?: throw ChannelGoneException("Twitch에 없는 채널입니다.")

    override suspend fun channel(channelId: String): Channel = withContext(Dispatchers.IO) { channelOf(user(channelId)) }

    override suspend fun about(channelId: String): String? = withContext(Dispatchers.IO) {
        user(channelId, "description").text("description")?.takeIf { it.isNotBlank() }
    }

    private fun videos(login: String, after: String?, count: Int): Pair<JsonObject, Page<StreamItem>> {
        val cursor = if (after == null) "" else ",after:\"$after\""
        val user = user(login, "videos(first:$count,sort:TIME$cursor){edges{cursor node{$VIDEO}} pageInfo{hasNextPage}}")
        val channel = channelOf(user)
        val edges = user.child("videos")?.list("edges").orEmpty()
        val more = (user.child("videos")?.child("pageInfo")?.get("hasNextPage") as? JsonPrimitive)?.booleanOrNull == true
        val items = edges.mapNotNull { edge -> edge.child("node")?.let { videoItem(it, channel) } }
        // In some countries Twitch lists no past broadcasts at all and still says there are more.
        return user to Page(items, if (more && items.isNotEmpty()) edges.last().text("cursor") else null)
    }

    override suspend fun channelItems(channelId: String, tab: ChannelTab, page: String?): Page<StreamItem> =
        withContext(Dispatchers.IO) {
            when (tab) {
                ChannelTab.VIDEOS -> videos(channelId, page, 20).second
                ChannelTab.LIVE -> user(channelId).let { user -> Page(listOfNotNull(user.child("stream")?.let { liveItem(it, channelOf(user)) })) }
                ChannelTab.SHORTS -> Page(emptyList())
            }
        }

    override suspend fun latest(channel: Channel): List<StreamItem> = withContext(Dispatchers.IO) {
        val (user, page) = videos(channel.id, null, 15)
        listOfNotNull(user.child("stream")?.let { liveItem(it, channelOf(user)) }) + page.items
    }

    private fun playlist(base: String, token: JsonObject?): String {
        val value = token?.text("value") ?: throw PlatformException("볼 수 없는 방송입니다.")
        return "$base?sig=${token.text("signature")}&token=${URLEncoder.encode(value, "UTF-8")}" +
            "&allow_source=true&allow_audio_only=true&p=${Random.nextInt(1_000_000)}&player=twitchweb"
    }

    override suspend fun playback(itemId: String): Playback = withContext(Dispatchers.IO) {
        if (itemId.startsWith("live:")) {
            val login = itemId.removePrefix("live:")
            val user = user(login)
            val stream = user.child("stream") ?: throw PlatformException("지금은 방송 중이 아닙니다.")
            val token = gql("query(\$l:String!){streamPlaybackAccessToken(channelName:\$l,$ACCESS){value signature}}", vars("l", login))
                .child("streamPlaybackAccessToken")
            Playback(
                liveItem(stream, channelOf(user)),
                stream.child("game")?.text("displayName").orEmpty(),
                listOf(Source(playlist("https://usher.ttvnw.net/api/channel/hls/$login.m3u8", token), SourceType.HLS)),
            )
        } else {
            val videoId = itemId.removePrefix("video:")
            val video = gql("query(\$id:ID!){video(id:\$id){$VIDEO description owner{$USER}}}", vars("id", videoId)).child("video")
                ?: throw PlatformException("볼 수 없는 영상입니다. 이 지역에서는 Twitch 지난 방송을 볼 수 없을 수 있습니다.")
            val token = gql("query(\$id:ID!){videoPlaybackAccessToken(id:\$id,$ACCESS){value signature}}", vars("id", videoId))
                .child("videoPlaybackAccessToken")
            Playback(
                videoItem(video, video.child("owner")?.let(::channelOf) ?: Channel(id, "", "")),
                video.text("description").orEmpty(),
                listOf(Source(playlist("https://usher.ttvnw.net/vod/$videoId.m3u8", token), SourceType.HLS)),
            )
        }
    }

    // Twitch only hands out playlists, never a file.
    override suspend fun downloads(itemId: String): List<DownloadOption> = emptyList()

    override suspend fun comments(itemId: String, page: String?): Page<Comment> = Page(emptyList())

    override suspend fun replies(itemId: String, page: String): Page<Comment> = Page(emptyList())

    /** Chat is plain IRC over a web socket; a "justinfan" name reads without an account. */
    override fun liveChat(itemId: String): Flow<ChatMessage> = callbackFlow {
        val login = itemId.removePrefix("live:").lowercase()
        val request = Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("CAP REQ :twitch.tv/tags")
                webSocket.send("PASS SCHMOOPIIE")
                webSocket.send("NICK justinfan${Random.nextInt(10_000, 99_999)}")
                webSocket.send("JOIN #$login")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                text.split("\r\n").forEach { line ->
                    if (line.startsWith("PING")) webSocket.send("PONG :tmi.twitch.tv")
                    else parseChatLine(line)?.let { trySend(it) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        awaitClose { socket.cancel() }
    }
}
