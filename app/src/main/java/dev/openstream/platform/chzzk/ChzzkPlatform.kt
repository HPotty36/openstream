package dev.openstream.platform.chzzk

import dev.openstream.platform.Channel
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

private const val API = "https://api.chzzk.naver.com/service"
private val SEOUL = ZoneId.of("Asia/Seoul")
private val OPEN_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.child(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

/**
 * CHZZK through the API its own website uses. Nothing here is documented by Naver and none of it
 * needs an account. A live broadcast is "live:<channel id>", a replay is "video:<number>".
 */
class ChzzkPlatform(private val client: OkHttpClient) : StreamingPlatform {
    override val id = PlatformId.CHZZK

    private fun body(url: String, accept: String? = null): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .apply { if (accept != null) header("Accept", accept) }.build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }
    }

    /** The "content" of an API answer. */
    private fun get(url: String): JsonObject {
        val root = Json.parseToJsonElement(body(url)).jsonObject
        return root.child("content") ?: throw PlatformException(root.text("message") ?: "치지직이 응답하지 않습니다.")
    }

    private fun channelOf(json: JsonObject) =
        Channel(id, json.text("channelId").orEmpty(), json.text("channelName").orEmpty(), json.text("channelImageUrl"))

    private fun videoItem(video: JsonObject, channel: Channel) = StreamItem(
        platform = id,
        id = "video:${video.long("videoNo")}",
        kind = ItemKind.VIDEO,
        title = video.text("videoTitle").orEmpty(),
        channel = channel,
        thumbnailUrl = video.text("thumbnailImageUrl"),
        durationSeconds = video.long("duration"),
        publishedAt = video.long("publishDateAt"),
        ageRestricted = video.flag("adult"),
    )

    private fun liveItem(live: JsonObject, channel: Channel) = StreamItem(
        platform = id,
        id = "live:${channel.id}",
        kind = ItemKind.LIVE,
        title = live.text("liveTitle").orEmpty(),
        channel = channel,
        thumbnailUrl = live.text("liveImageUrl")?.replace("{type}", "480"),
        publishedAt = live.text("openDate")?.let {
            runCatching { LocalDateTime.parse(it, OPEN_DATE).atZone(SEOUL).toInstant().toEpochMilli() }.getOrNull()
        },
        ageRestricted = live.flag("adult"),
    )

    override suspend fun search(query: String, page: String?): Page<SearchResult> = withContext(Dispatchers.IO) {
        val keyword = URLEncoder.encode(query, "UTF-8")
        val offset = page?.toIntOrNull() ?: 0
        val results = mutableListOf<SearchResult>()
        if (offset == 0) {
            get("$API/v1/search/channels?keyword=$keyword&offset=0&size=8").list("data").forEach { entry ->
                entry.child("channel")?.let { results.add(SearchResult.OfChannel(channelOf(it))) }
            }
            get("$API/v1/search/lives?keyword=$keyword&offset=0&size=8").list("data").forEach { entry ->
                val live = entry.child("live") ?: return@forEach
                val channel = entry.child("channel") ?: return@forEach
                results.add(SearchResult.OfItem(liveItem(live, channelOf(channel))))
            }
        }
        val videos = get("$API/v1/search/videos?keyword=$keyword&offset=$offset&size=20")
        videos.list("data").forEach { entry ->
            val video = entry.child("video") ?: return@forEach
            val channel = entry.child("channel") ?: return@forEach
            results.add(SearchResult.OfItem(videoItem(video, channelOf(channel))))
        }
        val next = videos.child("page")?.child("next")?.let { (it["offset"] as? JsonPrimitive)?.intOrNull }
        Page(results, next?.toString())
    }

    override suspend fun channel(channelId: String): Channel = withContext(Dispatchers.IO) {
        channelOf(get("$API/v1/channels/$channelId"))
    }

    private fun liveDetail(channelId: String): JsonObject = get("$API/v3/channels/$channelId/live-detail")

    private fun liveNow(channel: Channel): StreamItem? {
        val detail = runCatching { liveDetail(channel.id) }.getOrNull() ?: return null
        return if (detail.text("status") == "OPEN") liveItem(detail, channel) else null
    }

    private fun videos(channel: Channel, page: Int, size: Int): Page<StreamItem> {
        val content = get("$API/v1/channels/${channel.id}/videos?sortType=LATEST&pagingType=PAGE&page=$page&size=$size")
        val pages = (content["totalPages"] as? JsonPrimitive)?.intOrNull ?: 0
        return Page(content.list("data").map { videoItem(it, channel) }, if (page + 1 < pages) (page + 1).toString() else null)
    }

    override suspend fun channelItems(channelId: String, tab: ChannelTab, page: String?): Page<StreamItem> =
        withContext(Dispatchers.IO) {
            val channel = channelOf(get("$API/v1/channels/$channelId"))
            when (tab) {
                ChannelTab.VIDEOS -> videos(channel, page?.toIntOrNull() ?: 0, 24)
                ChannelTab.LIVE -> Page(listOfNotNull(liveNow(channel)))
                ChannelTab.SHORTS -> Page(emptyList())
            }
        }

    /** The broadcast that is on right now, when there is one, and the newest replays. */
    override suspend fun latest(channel: Channel): List<StreamItem> = withContext(Dispatchers.IO) {
        listOfNotNull(liveNow(channel)) + videos(channel, 0, 15).items
    }

    override suspend fun playback(itemId: String): Playback = withContext(Dispatchers.IO) {
        if (itemId.startsWith("live:")) livePlayback(itemId.removePrefix("live:")) else videoPlayback(itemId.removePrefix("video:"))
    }

    private fun livePlayback(channelId: String): Playback {
        val detail = liveDetail(channelId)
        val channel = detail.child("channel")?.let(::channelOf) ?: Channel(id, channelId, "")
        if (detail.text("status") != "OPEN") throw PlatformException("지금은 방송 중이 아닙니다.")
        val playbackJson = detail.text("livePlaybackJson")
            ?: throw PlatformException(if (detail.flag("adult")) "성인 인증이 필요한 방송이라 로그인 없이는 볼 수 없습니다." else "볼 수 없는 방송입니다.")
        val media = Json.parseToJsonElement(playbackJson).jsonObject.list("media")
        val hls = media.firstOrNull { it.text("mediaId") == "HLS" }?.text("path")
            ?: media.firstNotNullOfOrNull { it.text("path") }
            ?: throw PlatformException("재생 주소를 찾지 못했습니다.")
        // The adult flag only matters when the broadcast would not play; this one does.
        val item = liveItem(detail, channel).copy(ageRestricted = false)
        return Playback(item, listOfNotNull(detail.text("liveCategoryValue")).joinToString(), listOf(Source(hls, SourceType.HLS)))
    }

    /** The manifest of a replay: it names an HLS playlist and, for saving, whole MP4 files. */
    private fun manifest(detail: JsonObject) = detail.text("inKey")?.let { key ->
        val xml = body("https://apis.naver.com/neonplayer/vodplay/v2/playback/${detail.text("videoId")}?key=$key", "application/dash+xml")
        Jsoup.parse(xml, "", Parser.xmlParser())
    }

    private fun videoPlayback(videoNo: String): Playback {
        val detail = get("$API/v3/videos/$videoNo")
        val channel = detail.child("channel")?.let(::channelOf) ?: Channel(id, "", "")
        val manifest = manifest(detail)
            ?: throw PlatformException(if (detail.flag("adult")) "성인 인증이 필요한 영상이라 로그인 없이는 볼 수 없습니다." else "볼 수 없는 영상입니다.")
        val hls = manifest.select("AdaptationSet").firstNotNullOfOrNull { set -> set.attr("nvod:m3u").takeIf { it.isNotEmpty() } }
            ?: throw PlatformException("재생 주소를 찾지 못했습니다.")
        val item = videoItem(detail, channel).copy(ageRestricted = false)
        return Playback(item, listOfNotNull(detail.text("videoCategoryValue")).joinToString(), listOf(Source(hls, SourceType.HLS)))
    }

    override suspend fun downloads(itemId: String): List<DownloadOption> = withContext(Dispatchers.IO) {
        if (!itemId.startsWith("video:")) return@withContext emptyList()
        val manifest = manifest(get("$API/v3/videos/${itemId.removePrefix("video:")}")) ?: return@withContext emptyList()
        manifest.select("AdaptationSet[mimeType=video/mp4] > Representation").mapNotNull { representation ->
            val url = representation.selectFirst("BaseURL")?.text()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val height = representation.attr("height").toIntOrNull() ?: return@mapNotNull null
            height to DownloadOption("${height}p", videoUrl = null, audioUrl = null, fileUrl = url)
        }.sortedByDescending { it.first }.map { it.second }
    }

    override suspend fun comments(itemId: String, page: String?): Page<Comment> = Page(emptyList())

    override suspend fun replies(itemId: String, page: String): Page<Comment> = Page(emptyList())

    override fun liveChat(itemId: String): Flow<ChatMessage> = callbackFlow {
        val (chatId, token) = withContext(Dispatchers.IO) {
            val chatId = liveDetail(itemId.removePrefix("live:")).text("chatChannelId")
                ?: throw PlatformException("채팅이 없는 방송입니다.")
            val token = get("https://comm-api.game.naver.com/nng_main/v1/chats/access-token?channelId=$chatId&chatType=STREAMING")
                .text("accessToken") ?: throw PlatformException("채팅에 들어갈 수 없습니다.")
            chatId to token
        }
        val server = chatId.sumOf { it.code } % 9 + 1
        val request = Request.Builder().url("wss://kr-ss$server.chat.naver.com/chat").header("User-Agent", USER_AGENT).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    """{"ver":"3","cmd":100,"svcid":"game","cid":"$chatId","tid":1,"bdy":{"uid":null,"devType":2001,"accTkn":"$token","auth":"READ","libVer":"4.9.3","osVer":"Windows/10","devName":"Mozilla Firefox/140.0","locale":"ko","timezone":"Asia/Seoul"}}""",
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when ((message["cmd"] as? JsonPrimitive)?.intOrNull) {
                    // The server asks whether we are still here.
                    0 -> webSocket.send("""{"ver":"3","cmd":10000}""")
                    93101 -> message.list("bdy").forEach { chat ->
                        val text = chat.text("msg")?.takeIf { it.isNotBlank() } ?: return@forEach
                        val nickname = chat.text("profile")
                            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject.text("nickname") }.getOrNull() }
                        trySend(ChatMessage("${chat.long("msgTime")}-${chat.text("uid")}", nickname ?: "익명", text))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        val keepAlive = launch {
            while (true) {
                delay(20_000)
                socket.send("""{"ver":"3","cmd":0}""")
            }
        }
        awaitClose {
            keepAlive.cancel()
            socket.cancel()
        }
    }
}
