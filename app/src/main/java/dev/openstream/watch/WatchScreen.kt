@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.openstream.watch

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberUpdatedState
import dev.openstream.ui.windowLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableLongStateOf
import dev.openstream.library.ItemList
import dev.openstream.library.Library
import dev.openstream.platform.ItemKind
import dev.openstream.platform.DownloadOption
import dev.openstream.sponsor.SkipSegment
import dev.openstream.sponsor.youtubeVideoId
import dev.openstream.platform.Chapter
import androidx.compose.runtime.derivedStateOf
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import dev.openstream.platform.Channel
import dev.openstream.platform.Comment
import dev.openstream.platform.CommentImage
import dev.openstream.share.share
import dev.openstream.share.shareLink
import dev.openstream.share.sharesFromTime
import androidx.compose.material.icons.outlined.Share
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import dev.openstream.platform.Playback
import dev.openstream.platform.Source
import dev.openstream.platform.SourceType
import dev.openstream.platform.StreamItem
import dev.openstream.platform.StreamingPlatform
import dev.openstream.platform.youtube.USER_AGENT
import dev.openstream.settings.AppSettings
import dev.openstream.translate.Translator
import dev.openstream.translate.needsTranslation
import dev.openstream.translate.TextKind
import dev.openstream.translate.TranslatableText
import dev.openstream.translate.autoTranslated
import dev.openstream.ui.Avatar
import dev.openstream.ui.Paged
import dev.openstream.ui.PagedFooter
import dev.openstream.ui.errorText
import dev.openstream.ui.published
import dev.openstream.ui.relativeTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val http = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT).setAllowCrossProtocolRedirects(true)
// Whole files are read piece by piece, which YouTube sends at full speed (see YouTubePieces).
private val pieced = androidx.media3.datasource.DataSource.Factory { PiecedDataSource(http.createDataSource()) }

private fun mediaSource(source: Source): MediaSource {
    val item = MediaItem.fromUri(source.url)
    return when (source.type) {
        SourceType.HLS -> HlsMediaSource.Factory(http).createMediaSource(item)
        SourceType.DASH -> DashMediaSource.Factory(http).createMediaSource(item)
        SourceType.PROGRESSIVE -> {
            val video = ProgressiveMediaSource.Factory(pieced).createMediaSource(item)
            val audio = source.audioUrl?.let { ProgressiveMediaSource.Factory(pieced).createMediaSource(MediaItem.fromUri(it)) }
            if (audio == null) video else MergingMediaSource(video, audio)
        }
    }
}

/** The quality button of a stream that comes in several: what is offered, what was picked, and the button's words. */
private class QualityMenu(
    val variants: List<Variant>,
    val choice: QualityChoice,
    val label: String,
    val onChoose: (QualityChoice) -> Unit,
)

private fun Source.label(): String = when {
    height == null -> "자동"
    fps != null && fps > 30 -> "${height}p$fps"
    else -> "${height}p"
}

/** [active] is false while another screen covers this one; the video pauses then. */
@Composable
fun WatchScreen(
    platform: StreamingPlatform,
    item: StreamItem,
    settings: AppSettings,
    translator: Translator,
    active: Boolean,
    pip: Boolean,
    keys: Flow<Int>,
    onPlaying: (Boolean) -> Unit,
    library: Library,
    onProgress: (StreamItem, Long) -> Unit,
    onDownload: (StreamItem, DownloadOption) -> Unit,
    skipSegments: suspend (StreamItem) -> List<SkipSegment>,
    onChannel: (Channel) -> Unit,
    onSubscribed: (Channel) -> Unit,
    /** Opens a web address from the description or a comment: in the app when it is one of ours, else in the browser. */
    onLink: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Where the video was left the last time, to pick it up from there.
    var resumeAt by remember { mutableLongStateOf(0L) }
    var later by remember { mutableStateOf(false) }
    var playback by remember { mutableStateOf<Playback?>(null) }
    LaunchedEffect(playback) { later = library.contains(ItemList.LATER, playback?.item ?: item) }
    var source by remember { mutableStateOf<Source?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    val player = remember { buildPlayer(context) }
    val latestPlayback by rememberUpdatedState(playback)
    // A stream in several qualities (live, replays) starts at the best one; the viewer can pick another.
    var quality by remember { mutableStateOf<QualityChoice>(QualityChoice.Best) }
    var variants by remember { mutableStateOf(emptyList<Variant>()) }
    var playingHeight by remember { mutableIntStateOf(0) }
    val latestQuality by rememberUpdatedState(quality)
    LaunchedEffect(quality) { player.applyQuality(quality) }
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "재생하지 못했습니다 (${e.errorCodeName})."
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                variants = variantsOf(tracks)
                // A fixed pick names tracks of the stream that was loaded; a new load needs it again.
                if (latestQuality is QualityChoice.Fixed) player.applyQuality(latestQuality)
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.height > 0) playingHeight = videoSize.height
            }
        }
        player.addListener(listener)
        onDispose {
            latestPlayback?.item?.takeIf { it.kind != ItemKind.LIVE && player.currentPosition > 0 }
                ?.let { onProgress(it, player.currentPosition) }
            player.release()
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(item.id, attempt) {
        error = null
        try {
            val loaded = platform.playback(item.id)
            if (loaded.item.ageRestricted && !settings.showAgeRestricted.value) {
                error = "연령 제한 영상입니다. 설정에서 표시를 켜면 볼 수 있습니다."
                return@LaunchedEffect
            }
            if (loaded.item.kind != ItemKind.LIVE) {
                val left = library.position(loaded.item)
                val length = (loaded.item.durationSeconds ?: Long.MAX_VALUE / 1000) * 1000
                // A video left in its last seconds starts over.
                resumeAt = if (left < length - 10_000) left else 0
            }
            onProgress(loaded.item, resumeAt)
            playback = loaded
            source = loaded.sources.firstOrNull { (it.height ?: 0) in 1..1080 } ?: loaded.sources.firstOrNull()
            if (source == null) error = "재생할 수 있는 형식이 없습니다."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
        }
    }
    LaunchedEffect(source, attempt) {
        val chosen = source ?: return@LaunchedEffect
        error = null
        val position = player.currentPosition.takeIf { it > 0 } ?: resumeAt
        player.setMediaSource(mediaSource(chosen))
        player.prepare()
        if (position > 0) player.seekTo(position)
    }
    LaunchedEffect(playback) {
        val watched = playback?.item?.takeIf { it.kind != ItemKind.LIVE } ?: return@LaunchedEffect
        while (true) {
            delay(10_000)
            if (player.isPlaying) onProgress(watched, player.currentPosition)
        }
    }
    // Jumps over the stretches viewers marked as sponsor reads.
    LaunchedEffect(playback) {
        val watched = playback?.item ?: return@LaunchedEffect
        val segments = try {
            skipSegments(watched)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (segments.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(300)
            val at = player.currentPosition
            // Not in its last second: seeking back into a segment's end should not skip again.
            val segment = segments.firstOrNull { at >= it.startMs && at < it.endMs - 1000 } ?: continue
            player.seekTo(segment.endMs)
            Toast.makeText(context, "스폰서 구간을 건너뛰었습니다.", Toast.LENGTH_SHORT).show()
        }
    }
    // Chapters: the ones the platform gives, else a list in the description that starts at 0:00.
    val chapters = remember(playback) {
        playback?.takeIf { it.item.kind != ItemKind.LIVE }
            ?.let { p -> p.chapters.ifEmpty { chaptersFrom(p.description, p.item.durationSeconds?.times(1000)) } }
            .orEmpty()
    }
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(chapters) {
        if (chapters.isEmpty()) return@LaunchedEffect
        while (true) {
            positionMs = player.currentPosition
            delay(500)
        }
    }
    // Read through derivedStateOf, so the header changes only when the chapter does, not twice a second.
    val chapterIndex by remember(chapters) { derivedStateOf { chapterAt(chapters, positionMs) } }
    val toggleLater: () -> Unit = {
        val target = playback?.item ?: item
        val save = !later
        later = save
        scope.launch { if (save) library.put(ItemList.LATER, listOf(target)) else library.remove(ItemList.LATER, target) }
    }

    val layout = windowLayout()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // A phone goes full screen by being turned; a tablet or a monitor is always wide, so there it is a choice.
    var fullscreenChosen by remember { mutableStateOf(false) }
    val fullscreen = if (layout.tablet) fullscreenChosen else landscape
    val tabletNow by rememberUpdatedState(layout.tablet)
    HideSystemBars(fullscreen && active && !pip)
    BackHandler(active && layout.tablet && fullscreenChosen) { fullscreenChosen = false }

    val leaveMode by settings.leaveMode.collectAsState()
    PlayerHousekeeping(player, (playback?.item ?: item).title, active, leaveMode, keys, onPlaying) {
        if (tabletNow) fullscreenChosen = !fullscreenChosen
    }

    val playerBox: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.background(Color.Black)) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        this.player = player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setFullscreenButtonClickListener { full ->
                            if (tabletNow) fullscreenChosen = full
                            else (it as? Activity)?.requestedOrientation =
                                if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        // Chapter starts are marked on the time bar as small white notches.
                        findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
                            setAdMarkerColor(0xE6FFFFFF.toInt())
                            setPlayedAdMarkerColor(0xE6FFFFFF.toInt())
                        }
                    }
                },
                update = {
                    it.setFullscreenButtonState(fullscreen)
                    val marks = chapters.drop(1).map { chapter -> chapter.startMs }
                    it.setExtraAdGroupMarkers(marks.toLongArray(), BooleanArray(marks.size))
                    // The small window has its own buttons; the player's would only cover the picture.
                    it.useController = !pip
                },
                modifier = Modifier.fillMaxSize(),
            )
            val message = error
            when {
                message != null -> Column(
                    Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { attempt++ }) { Text("다시 시도") }
                }
                playback == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
        }
    }

    val shown = playback?.item ?: item
    val libraryChanges by library.changes.collectAsState()
    val channel = shown.channel
    val subscribed by produceState<Boolean?>(null, libraryChanges, channel.id) {
        value = if (channel.id.isEmpty()) null else library.isSubscribed(channel)
    }
    val toggleSubscribed: () -> Unit = {
        val now = subscribed
        scope.launch {
            if (now == true) library.unsubscribe(channel)
            else if (now == false) { library.subscribe(listOf(channel)); onSubscribed(channel) }
        }
    }
    // A live broadcast has no file to save.
    val downloadOptions: (suspend () -> List<DownloadOption>)? =
        if (shown.kind == ItemKind.LIVE) null else ({ platform.downloads(shown.id) })
    // Only a stream that comes in several qualities has them to pick from; a YouTube video picks by source instead.
    val qualityMenu = if (source != null && source?.type != SourceType.PROGRESSIVE && variants.size > 1) {
        QualityMenu(variants, quality, qualityLabel(quality, variants, playingHeight)) { quality = it }
    } else null
    // A time in the description or a comment jumps there; a live broadcast has nowhere to jump to.
    val seekTo: ((Long) -> Unit)? = if (shown.kind == ItemKind.LIVE) null else ({ ms -> player.seekTo(ms); player.play() })
    // A link to this same video jumps within it instead of opening it again.
    val openLink: (String) -> Unit = { url ->
        val target = linkTarget(url)
        val same = target is LinkTarget.Video && target.platform == shown.platform &&
            (target.itemId == shown.id || youtubeVideoId(target.itemId)?.let { it == youtubeVideoId(shown.id) } == true)
        if (same) (target as LinkTarget.Video).startMs?.let { seekTo?.invoke(it) } else onLink(url)
    }
    when {
        pip || fullscreen -> playerBox(Modifier.fillMaxSize())
        // Wide window: the video and its description on the left, the comments in their own column.
        layout.wide -> Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.weight(1.7f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                    playerBox(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.72f).dp).aspectRatio(16f / 9f))
                }
                Header(shown, playback, source, { source = it }, qualityMenu, seekTo, openLink, chapters, chapterIndex, onChannel, subscribed, toggleSubscribed, later, toggleLater, downloadOptions, position = { player.currentPosition }) { onDownload(shown, it) }
            }
            if (shown.kind == ItemKind.LIVE) Chat(platform, shown, translator, settings, Modifier.weight(1f).fillMaxHeight()) {}
            else Comments(platform, shown, translator, settings, Modifier.weight(1f).fillMaxHeight(), onSeek = seekTo, onLink = openLink) {}
        }
        else -> Column(Modifier.fillMaxSize()) {
            playerBox(Modifier.statusBarsPadding().fillMaxWidth().aspectRatio(16f / 9f))
            val header: @Composable () -> Unit = {
                Header(shown, playback, source, { source = it }, qualityMenu, seekTo, openLink, chapters, chapterIndex, onChannel, subscribed, toggleSubscribed, later, toggleLater, downloadOptions, position = { player.currentPosition }) { onDownload(shown, it) }
                HorizontalDivider()
            }
            if (shown.kind == ItemKind.LIVE) Chat(platform, shown, translator, settings, Modifier.fillMaxSize().navigationBarsPadding(), header)
            else Comments(platform, shown, translator, settings, Modifier.fillMaxSize().navigationBarsPadding(), seekTo, openLink, header)
        }
    }
}

@Composable
private fun HideSystemBars(hide: Boolean) {
    val view = LocalView.current
    DisposableEffect(hide) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (hide) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private sealed interface Translation {
    data object Loading : Translation
    data class Done(val text: String) : Translation
    data class Failed(val message: String) : Translation
}

@Composable
private fun Comments(
    platform: StreamingPlatform,
    item: StreamItem,
    translator: Translator,
    settings: AppSettings,
    modifier: Modifier,
    onSeek: ((Long) -> Unit)? = null,
    onLink: ((String) -> Unit)? = null,
    header: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val comments = remember(item.id) { Paged(scope) { platform.comments(item.id, it) } }
    val durationMs = item.durationSeconds?.times(1000)
    val replies = remember(item.id) { mutableStateMapOf<Int, Paged<Comment>>() }
    val translations = remember(item.id) { mutableStateMapOf<String, Translation>() }
    val keyHint by settings.apiKeyHint.collectAsState()
    val canTranslate = keyHint != null

    fun translate(texts: List<String>) {
        val wanted = texts.distinct().filter { translations[it] !is Translation.Done && translations[it] != Translation.Loading }
        // Small batches: one bad reply then costs twenty comments, not the whole list.
        wanted.chunked(20).forEach { batch ->
            batch.forEach { translations[it] = Translation.Loading }
            scope.launch {
                try {
                    val out = translator.translate(batch)
                    batch.zip(out).forEach { (from, to) -> translations[from] = Translation.Done(to) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = e.message ?: "번역하지 못했습니다."
                    batch.forEach { translations[it] = Translation.Failed(message) }
                }
            }
        }
    }

    LazyColumn(modifier) {
        item { header() }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("댓글", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
                if (canTranslate) {
                    TextButton(onClick = {
                        val shown = comments.items + replies.values.flatMap { it.items }
                        translate(shown.map { it.text }.filter(::needsTranslation))
                    }) { Text("모두 번역") }
                }
            }
        }
        comments.items.forEachIndexed { index, comment ->
            item {
                CommentRow(
                    comment, translations[comment.text], canTranslate,
                    onTranslate = { translate(listOf(comment.text)) },
                    onHide = { translations.remove(comment.text) },
                    repliesOpen = replies[index] != null,
                    onReplies = {
                        if (replies[index] != null) replies.remove(index)
                        else comment.repliesPage?.let { first -> replies[index] = Paged(scope) { platform.replies(item.id, it ?: first) } }
                    },
                    durationMs = durationMs,
                    onSeek = onSeek,
                    onLink = onLink,
                )
            }
            val thread = replies[index]
            if (thread != null) {
                items(thread.items) { reply ->
                    CommentRow(
                        reply, translations[reply.text], canTranslate,
                        onTranslate = { translate(listOf(reply.text)) },
                        onHide = { translations.remove(reply.text) },
                        isReply = true,
                        durationMs = durationMs,
                        onSeek = onSeek,
                        onLink = onLink,
                    )
                }
                item { PagedFooter(thread, empty = "답글이 없습니다.", modifier = Modifier.padding(start = 44.dp)) }
            }
        }
        item { PagedFooter(comments, empty = "댓글이 없습니다.") }
    }
}

/** One action under a video: an icon and a word in a thin outline. */
@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Header(
    item: StreamItem,
    playback: Playback?,
    source: Source?,
    onSource: (Source) -> Unit,
    qualityMenu: QualityMenu?,
    onSeek: ((Long) -> Unit)?,
    onLink: (String) -> Unit,
    chapters: List<Chapter>,
    chapter: Int,
    onChannel: (Channel) -> Unit,
    subscribed: Boolean?,
    onSubscribe: () -> Unit,
    later: Boolean,
    onLater: () -> Unit,
    downloadOptions: (suspend () -> List<DownloadOption>)?,
    /** Where playback is now, for a link that starts there. */
    position: () -> Long = { 0L },
    onDownload: (DownloadOption) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        // The date sits on the title's action row, so a translate action costs no extra line.
        TranslatableText(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            auto = true,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
        ) {
            Text(
                item.published().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val channel = item.channel
        Row(
            Modifier.fillMaxWidth().clickable(enabled = channel.id.isNotEmpty()) { onChannel(channel) }
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dev.openstream.ui.ChannelAvatar(channel, 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                autoTranslated(channel.name, TextKind.NAME),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            when (subscribed) {
                true -> OutlinedButton(onClick = onSubscribe) { Text("구독 중") }
                false -> Button(onClick = onSubscribe) { Text("구독") }
                null -> {}
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Action(if (later) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, if (later) "저장됨" else "나중에 보기", onLater)
            shareLink(item)?.let { link ->
                // Partway into a video or replay, the link can start from here; otherwise it shares at once.
                val context = LocalContext.current
                var open by remember { mutableStateOf(false) }
                var at by remember { mutableLongStateOf(0L) }
                Box {
                    Action(Icons.Outlined.Share, "공유") {
                        at = position()
                        if (sharesFromTime(item) && at >= 1000) open = true else context.share(link, item.title)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text("처음부터 공유") }, onClick = { open = false; context.share(link, item.title) })
                        DropdownMenuItem(
                            text = { Text("${clockTime(at)}부터 공유") },
                            onClick = { open = false; context.share(shareLink(item, at) ?: link, item.title) },
                        )
                    }
                }
            }
            if (downloadOptions != null) {
                val scope = rememberCoroutineScope()
                var open by remember { mutableStateOf(false) }
                var options by remember { mutableStateOf<List<DownloadOption>?>(null) }
                var failed by remember { mutableStateOf(false) }
                Box {
                    Action(Icons.Outlined.Download, "다운로드") {
                        open = true
                        if (options == null) scope.launch {
                            failed = false
                            options = try {
                                downloadOptions()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failed = true
                                null
                            }
                        }
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        val list = options
                        when {
                            failed -> DropdownMenuItem(text = { Text("불러오지 못했습니다") }, onClick = { open = false })
                            list == null -> DropdownMenuItem(text = { Text("불러오는 중") }, onClick = {}, enabled = false)
                            list.isEmpty() -> DropdownMenuItem(text = { Text("저장할 수 있는 형식이 없습니다") }, onClick = { open = false })
                            else -> list.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label) }, onClick = { open = false; onDownload(option) })
                            }
                        }
                    }
                }
            }
            if (qualityMenu != null) {
                // The qualities inside one stream, best first, then the player's own pick.
                var open by remember { mutableStateOf(false) }
                Box {
                    Action(Icons.Outlined.Tune, qualityMenu.label) { open = true }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        val marked = chosenVariant(qualityMenu.choice, qualityMenu.variants)
                        val check: @Composable (Boolean) -> Unit = { on -> if (on) Icon(Icons.Outlined.Check, contentDescription = "선택됨") }
                        qualityMenu.variants.forEach { variant ->
                            DropdownMenuItem(
                                text = { Text(variant.label) },
                                onClick = { open = false; qualityMenu.onChoose(QualityChoice.Fixed(variant)) },
                                trailingIcon = { check(variant == marked) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("자동") },
                            onClick = { open = false; qualityMenu.onChoose(QualityChoice.Auto) },
                            trailingIcon = { check(qualityMenu.choice == QualityChoice.Auto) },
                        )
                    }
                }
            } else if (playback != null && source?.type == SourceType.PROGRESSIVE && playback.sources.size > 1) {
                // A YouTube video: one source per height.
                var open by remember { mutableStateOf(false) }
                Box {
                    Action(Icons.Outlined.Tune, source.label()) { open = true }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        playback.sources.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label()) }, onClick = { open = false; onSource(option) })
                        }
                    }
                }
            }
        }
        if (chapters.isNotEmpty()) ChapterBar(chapters, chapter, onSeek)
        val description = playback?.description.orEmpty().trim()
        if (description.isNotBlank()) {
            TranslatableText(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                collapsedLines = 3,
                durationMs = item.durationSeconds?.times(1000),
                onTimestamp = onSeek,
                onLink = onLink,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            )
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** A small action under a comment. Every comment's row is this high, with or without actions. */
@Composable
private fun CommentAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

@Composable
private fun CommentRow(
    comment: Comment,
    translation: Translation?,
    canTranslate: Boolean,
    onTranslate: () -> Unit,
    onHide: () -> Unit,
    isReply: Boolean = false,
    repliesOpen: Boolean = false,
    onReplies: (() -> Unit)? = null,
    durationMs: Long? = null,
    onSeek: ((Long) -> Unit)? = null,
    onLink: ((String) -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(start = if (isReply) 60.dp else 16.dp, end = 16.dp, top = 10.dp)) {
        Avatar(comment.authorAvatarUrl, if (isReply) 24.dp else 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val meta = listOfNotNull(
                "고정".takeIf { comment.pinned },
                comment.author.takeIf { it.isNotEmpty() },
                comment.publishedAt?.let { relativeTime(it) } ?: comment.publishedText,
            ).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (comment.text.isNotBlank()) {
                Text(linkedText(comment.text, durationMs, onSeek, onLink), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            }
            comment.image?.let { CommentGif(it) }
            when (translation) {
                is Translation.Done -> Text(
                    linkedText(translation.text, durationMs, onSeek, onLink),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                is Translation.Failed -> Text(
                    translation.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
                else -> {}
            }
            Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                val likes = comment.likeCount
                if (likes != null && likes > 0) {
                    Text(
                        "좋아요 ${compactCount(likes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                if (onReplies != null && comment.repliesPage != null) {
                    CommentAction(if (repliesOpen) "답글 접기" else if (comment.replyCount > 0) "답글 ${comment.replyCount}개" else "답글", onClick = onReplies)
                }
                if (canTranslate && needsTranslation(comment.text)) {
                    when (translation) {
                        Translation.Loading -> CommentAction("번역 중", enabled = false) {}
                        is Translation.Done -> CommentAction("번역 숨기기", onClick = onHide)
                        is Translation.Failed -> CommentAction("다시 번역", onClick = onTranslate)
                        null -> CommentAction("번역", onClick = onTranslate)
                    }
                }
            }
        }
    }
}

/** A GIF in a comment, at its own shape inside a modest box, moving as it does on YouTube. */
@Composable
private fun CommentGif(image: CommentImage) {
    var failed by remember(image.url) { mutableStateOf(false) }
    if (failed) {
        Text(
            "GIF를 불러오지 못했습니다.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    } else {
        AsyncImage(
            model = image.url,
            contentDescription = "GIF",
            contentScale = ContentScale.Fit,
            onError = { failed = true },
            modifier = Modifier.padding(top = 6.dp)
                .sizeIn(maxWidth = 240.dp, maxHeight = 180.dp)
                .aspectRatio(image.width.toFloat() / image.height)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

fun compactCount(n: Int): String = when {
    n >= 100_000_000 -> "%.1f억".format(n / 100_000_000.0)
    n >= 10_000 -> "%.1f만".format(n / 10_000.0)
    n >= 1_000 -> "%.1f천".format(n / 1_000.0)
    else -> n.toString()
}.replace(".0", "")
