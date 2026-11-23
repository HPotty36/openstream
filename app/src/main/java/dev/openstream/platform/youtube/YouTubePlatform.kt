package dev.openstream.platform.youtube

import dev.openstream.platform.Channel
import dev.openstream.platform.ChannelGoneException
import dev.openstream.platform.Chapter
import dev.openstream.platform.ChannelTab
import dev.openstream.platform.PlatformException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import dev.openstream.platform.ChatMessage
import dev.openstream.platform.Comment
import dev.openstream.platform.ItemKind
import dev.openstream.platform.Page
import dev.openstream.platform.PlatformId
import dev.openstream.platform.Playback
import dev.openstream.platform.SearchResult
import dev.openstream.platform.Source
import dev.openstream.platform.SourceType
import dev.openstream.platform.StreamItem
import dev.openstream.platform.StreamingPlatform
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import dev.openstream.library.youtubeChannelId
import java.time.OffsetDateTime
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import dev.openstream.platform.DownloadOption
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.Extractor
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

private typealias ExtractorPage = org.schabi.newpipe.extractor.Page

private val KOREAN = Localization("ko")
private val KOREA = ContentCountry("KR")

/** YouTube through NewPipeExtractor. Items and channels are identified by their address. */
class YouTubePlatform(private val client: OkHttpClient) : StreamingPlatform {
    override val id = PlatformId.YOUTUBE

    private val service get() = ServiceList.YouTube
    /** The extractor's page objects, kept here so the rest of the app only passes a short token around. */
    private val pages = ConcurrentHashMap<String, ExtractorPage>()
    private val channels = ConcurrentHashMap<String, ChannelInfo>()

    init {
        NewPipe.init(OkHttpDownloader(client), KOREAN, KOREA)
    }

    /**
     * The extractor offers YouTube only in English (its list of languages stops at en-GB), and in English
     * YouTube hands out the English titles creators or its own translation added. Set on each request,
     * Korean goes through, and the titles come back as they were written.
     */
    private fun <T : Extractor> T.inKorean(): T = apply {
        forceLocalization(KOREAN)
        forceContentCountry(KOREA)
    }

    private fun token(page: ExtractorPage?): String? {
        if (!ExtractorPage.isValid(page)) return null
        return UUID.randomUUID().toString().also { pages[it] = page!! }
    }

    private fun page(token: String): ExtractorPage = pages[token] ?: throw IOException("page expired")

    override suspend fun search(query: String, page: String?): Page<SearchResult> = withContext(Dispatchers.IO) {
        val extractor = service.getSearchExtractor(service.searchQHFactory.fromQuery(query, listOf("all"), "")).inKorean()
        val (items, next) = if (page == null) {
            extractor.fetchPage()
            SearchInfo.getInfo(extractor).let { it.relatedItems to it.nextPage }
        } else {
            extractor.getPage(page(page)).let { it.items to it.nextPage }
        }
        Page(items.mapNotNull(::searchResult), token(next))
    }

    private fun searchResult(item: InfoItem): SearchResult? = when (item) {
        is ChannelInfoItem -> SearchResult.OfChannel(Channel(id, item.url, item.name, item.thumbnails.best()))
        is StreamInfoItem -> item.toItem().takeIf { it.kind != ItemKind.SHORT }?.let(SearchResult::OfItem)
        else -> null
    }

    private fun channelInfo(channelId: String): ChannelInfo =
        channels.getOrPut(channelId) {
            ChannelInfo.getInfo(service.getChannelExtractor(channelId).inKorean().also { it.fetchPage() })
        }

    override suspend fun channel(channelId: String): Channel = withContext(Dispatchers.IO) {
        val info = channelInfo(channelId)
        Channel(id, info.url, info.name, info.avatars.best())
    }

    override suspend fun about(channelId: String): String? = withContext(Dispatchers.IO) {
        channelInfo(channelId).description?.takeIf { it.isNotBlank() }
    }

    override suspend fun channelItems(channelId: String, tab: ChannelTab, page: String?): Page<StreamItem> =
        withContext(Dispatchers.IO) {
            val filter = when (tab) {
                ChannelTab.VIDEOS -> ChannelTabs.VIDEOS
                ChannelTab.SHORTS -> ChannelTabs.SHORTS
                ChannelTab.LIVE -> ChannelTabs.LIVESTREAMS
            }
            val handler = channelInfo(channelId).tabs.firstOrNull { filter in it.contentFilters }
                ?: return@withContext Page(emptyList())
            val extractor = service.getChannelTabExtractor(handler).inKorean()
            val (items, next) = if (page == null) {
                extractor.fetchPage()
                ChannelTabInfo.getInfo(extractor).let { it.relatedItems to it.nextPage }
            } else {
                extractor.getPage(page(page)).let { it.items to it.nextPage }
            }
            val mapped = items.filterIsInstance<StreamInfoItem>().map {
                val item = it.toItem()
                // The shorts tab does not always flag its own items.
                if (tab == ChannelTab.SHORTS) item.copy(kind = ItemKind.SHORT) else item
            }
            Page(mapped, token(next))
        }

    /** From the channel's RSS feed: one small request, where the channel page takes several large ones. */
    override suspend fun latest(channel: Channel): List<StreamItem> = withContext(Dispatchers.IO) {
        val channelId = youtubeChannelId(channel.id) ?: return@withContext fromPage(channel)
        val request = okhttp3.Request.Builder().url("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
            .header("User-Agent", USER_AGENT).build()
        val xml = client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> response.body.string()
                // No feed: the channel is gone, or has no feed for some other reason. Its page tells which.
                response.code == 404 -> null
                else -> throw PlatformException("YouTube가 응답하지 않습니다 (HTTP ${response.code}).")
            }
        } ?: return@withContext fromPage(channel)
        parseFeed(xml, channel)
    }

    /** The newest uploads from the channel page; a channel YouTube took down fails with YouTube's own reason. */
    private suspend fun fromPage(channel: Channel): List<StreamItem> = try {
        channelItems(channel.id, ChannelTab.VIDEOS, null).items
    } catch (e: ContentNotAvailableException) {
        throw ChannelGoneException(goneReason(e))
    }

    private fun streamInfo(itemId: String): StreamInfo = StreamInfo.getInfo(service.getStreamExtractor(itemId).inKorean())

    override suspend fun playback(itemId: String): Playback = withContext(Dispatchers.IO) {
        val info = streamInfo(itemId)
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        val item = StreamItem(
            platform = id,
            id = info.url,
            kind = when {
                live -> ItemKind.LIVE
                info.isShortFormContent -> ItemKind.SHORT
                else -> ItemKind.VIDEO
            },
            title = info.name,
            channel = Channel(id, info.uploaderUrl.orEmpty(), info.uploaderName.orEmpty(), info.uploaderAvatars.best()),
            thumbnailUrl = info.thumbnails.best(),
            durationSeconds = info.duration.takeIf { it > 0 },
            publishedAt = info.uploadDate?.instant?.toEpochMilli(),
            publishedText = info.textualUploadDate,
            ageRestricted = info.ageLimit >= 18,
        )
        val chapters = info.streamSegments.map { Chapter(it.startTimeSeconds * 1000L, it.title, it.previewUrl) }
        Playback(item, info.description.plain(), sources(info, live), chapters)
    }

    override suspend fun downloads(itemId: String): List<DownloadOption> = withContext(Dispatchers.IO) {
        val info = streamInfo(itemId)
        val audio = info.audioStreams.filter { it.playable() && it.format == MediaFormat.M4A }
            .sortedWith(compareBy<AudioStream> { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                .thenBy { it.averageBitrate })
            .lastOrNull() ?: return@withContext emptyList()
        // H.264 in MP4 only: it goes up to 1080p, and the phone can join it with the sound without re-encoding.
        val videos = info.videoOnlyStreams
            .filter { it.playable() && it.format == MediaFormat.MPEG_4 && it.codec?.startsWith("avc1") == true }
            .sortedByDescending { it.height }.distinctBy { it.height }
        videos.map { DownloadOption("${it.height}p", it.content, audio.content) } +
            DownloadOption("오디오만", null, audio.content)
    }

    private fun sources(info: StreamInfo, live: Boolean): List<Source> {
        val manifests = buildList {
            info.hlsUrl?.takeIf { it.isNotEmpty() }?.let { add(Source(it, SourceType.HLS)) }
            info.dashMpdUrl?.takeIf { it.isNotEmpty() }?.let { add(Source(it, SourceType.DASH)) }
        }
        if (live) return manifests

        val audio = info.audioStreams.filter { it.playable() }
            .sortedWith(compareBy<AudioStream> { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                .thenBy { it.averageBitrate })
            .lastOrNull()
        val separate = if (audio == null) emptyList() else info.videoOnlyStreams.filter { it.playable() }
            .map { Source(it.content, SourceType.PROGRESSIVE, it.height, it.fps, audioUrl = audio.content) }
        val muxed = info.videoStreams.filter { it.playable() }
            .map { Source(it.content, SourceType.PROGRESSIVE, it.height, it.fps) }
        // One entry per height: the separate stream when there is one, since those go higher and look better.
        val byHeight = (separate + muxed).filter { it.height != null }.distinctBy { it.height }
            .sortedByDescending { it.height }
        return byHeight + manifests
    }

    private fun VideoStream.playable() = isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
    private fun AudioStream.playable() = isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP

    override suspend fun comments(itemId: String, page: String?): Page<Comment> = withContext(Dispatchers.IO) {
        if (page == null) {
            val info = CommentsInfo.getInfo(service, itemId)
            Page(info.relatedItems.map { it.toComment() }, token(info.nextPage))
        } else {
            replies(itemId, page)
        }
    }

    override suspend fun replies(itemId: String, page: String): Page<Comment> = withContext(Dispatchers.IO) {
        val more = CommentsInfo.getMoreItems(service, itemId, page(page))
        Page(more.items.map { it.toComment() }, token(more.nextPage))
    }

    /** The extractor finds where the chat starts; reading it from there is [youtubeLiveChat]'s work. */
    override fun liveChat(itemId: String): Flow<ChatMessage> = flow {
        val start = streamInfo(itemId).liveChatContinuation?.takeIf { it.isNotEmpty() } ?: return@flow
        emitAll(youtubeLiveChat(client, start))
    }.flowOn(Dispatchers.IO)

    private fun StreamInfoItem.toItem() = StreamItem(
        platform = id,
        id = url,
        kind = when {
            isShortFormContent -> ItemKind.SHORT
            streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM -> ItemKind.LIVE
            else -> ItemKind.VIDEO
        },
        title = name,
        channel = Channel(id, uploaderUrl.orEmpty(), uploaderName.orEmpty(), uploaderAvatars.best()),
        thumbnailUrl = thumbnails.best(),
        durationSeconds = duration.takeIf { it > 0 },
        publishedAt = uploadDate?.instant?.toEpochMilli(),
        publishedText = textualUploadDate,
    )

    private fun CommentsInfoItem.toComment() = Comment(
        id = commentId.orEmpty(),
        author = uploaderName.orEmpty(),
        text = commentText.plain(),
        authorAvatarUrl = uploaderAvatars.best(),
        publishedAt = uploadDate?.instant?.toEpochMilli(),
        publishedText = textualUploadDate,
        likeCount = likeCount.takeIf { it >= 0 },
        pinned = isPinned,
        replyCount = replyCount.coerceAtLeast(0),
        repliesPage = token(replies),
    )
}

/** A channel's RSS feed as feed items. The feed has no durations; a short is told by its address. */
fun parseFeed(xml: String, channel: Channel): List<StreamItem> =
    Jsoup.parse(xml, "", Parser.xmlParser()).select("entry").mapNotNull { entry ->
        val link = entry.selectFirst("link[rel=alternate]")?.attr("href")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val videoId = entry.getElementsByTag("yt:videoId").first()?.text()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        StreamItem(
            platform = PlatformId.YOUTUBE,
            id = "https://www.youtube.com/watch?v=$videoId",
            kind = if ("/shorts/" in link) ItemKind.SHORT else ItemKind.VIDEO,
            title = entry.selectFirst("title")?.text().orEmpty(),
            channel = channel,
            thumbnailUrl = entry.getElementsByTag("media:thumbnail").first()?.attr("url")?.takeIf { it.isNotEmpty() },
            publishedAt = runCatching { OffsetDateTime.parse(entry.selectFirst("published")!!.text()).toInstant().toEpochMilli() }.getOrNull(),
        )
    }

/**
 * YouTube's own words for why something cannot be shown (a terminated channel, a video blocked in this
 * country, ...), which the extractor passes on as `Got error: "…"` or `Got error UNPLAYABLE: "…"`,
 * in Korean since the requests ask for Korean. Null when the error carries none.
 */
fun youtubeReason(e: ContentNotAvailableException): String? =
    e.message?.let { Regex("Got error[^\"]*\"(.+)\"", RegexOption.DOT_MATCHES_ALL).find(it)?.groupValues?.get(1)?.trim() }
        ?.takeIf { it.isNotEmpty() }

/** Why a channel cannot be shown, in YouTube's words when it gave some. */
fun goneReason(e: ContentNotAvailableException): String = youtubeReason(e) ?: "YouTube에서 볼 수 없는 채널입니다."

private fun List<Image>.best(): String? = maxByOrNull { it.height }?.url

private fun Description.plain(): String {
    val text = content.orEmpty()
    if (type != Description.Type.HTML) return text
    return Jsoup.parse(text.replace(Regex("<br\\s*/?>"), "\n")).wholeText().trim()
}
