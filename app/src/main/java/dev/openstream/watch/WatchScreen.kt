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
import dev.openstream.platform.Playback
import dev.openstream.platform.Source
import dev.openstream.platform.SourceType
import dev.openstream.platform.StreamItem
import dev.openstream.platform.StreamingPlatform
import dev.openstream.platform.youtube.USER_AGENT
import dev.openstream.settings.AppSettings
import dev.openstream.translate.Translator
import dev.openstream.translate.needsTranslation
import dev.openstream.ui.Avatar
import dev.openstream.ui.Paged
import dev.openstream.ui.PagedFooter
import dev.openstream.ui.errorText
import dev.openstream.ui.published
import dev.openstream.ui.relativeTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val http = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT).setAllowCrossProtocolRedirects(true)

private fun mediaSource(source: Source): MediaSource {
    val item = MediaItem.fromUri(source.url)
    return when (source.type) {
        SourceType.HLS -> HlsMediaSource.Factory(http).createMediaSource(item)
        SourceType.DASH -> DashMediaSource.Factory(http).createMediaSource(item)
        SourceType.PROGRESSIVE -> {
            val video = ProgressiveMediaSource.Factory(http).createMediaSource(item)
            val audio = source.audioUrl?.let { ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(it)) }
            if (audio == null) video else MergingMediaSource(video, audio)
        }
    }
}

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
    keys: Flow<Int>,
    onChannel: (Channel) -> Unit,
) {
    val context = LocalContext.current
    var playback by remember { mutableStateOf<Playback?>(null) }
    var source by remember { mutableStateOf<Source?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    val player = remember {
        // Fallback: when a phone's hardware decoder gives up on a stream, try its next decoder instead of failing.
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true)).build()
            .apply { playWhenReady = true }
    }
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "재생하지 못했습니다 (${e.errorCodeName})."
            }
        }
        player.addListener(listener)
        onDispose {
            player.release()
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    LaunchedEffect(active) { if (!active) player.pause() }

    LaunchedEffect(item.id, attempt) {
        error = null
        try {
            val loaded = platform.playback(item.id)
            if (loaded.item.ageRestricted && !settings.showAgeRestricted.value) {
                error = "연령 제한 영상입니다. 설정에서 표시를 켜면 볼 수 있습니다."
                return@LaunchedEffect
            }
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
        val position = player.currentPosition
        player.setMediaSource(mediaSource(chosen))
        player.prepare()
        if (position > 0) player.seekTo(position)
    }

    val layout = windowLayout()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // A phone goes full screen by being turned; a tablet or a monitor is always wide, so there it is a choice.
    var fullscreenChosen by remember { mutableStateOf(false) }
    val fullscreen = if (layout.tablet) fullscreenChosen else landscape
    val tabletNow by rememberUpdatedState(layout.tablet)
    HideSystemBars(fullscreen && active)
    BackHandler(active && layout.tablet && fullscreenChosen) { fullscreenChosen = false }

    // Keyboard shortcuts, for a tablet with a keyboard or a phone on a monitor.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        keys.collect { code ->
            when (code) {
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K -> if (player.isPlaying) player.pause() else player.play()
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_J -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_L -> player.seekTo(player.currentPosition + 10_000)
                KeyEvent.KEYCODE_F -> if (tabletNow) fullscreenChosen = !fullscreenChosen
            }
        }
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
                    }
                },
                update = { it.setFullscreenButtonState(fullscreen) },
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
    when {
        fullscreen -> playerBox(Modifier.fillMaxSize())
        // Wide window: the video and its description on the left, the comments in their own column.
        layout.wide -> Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.weight(1.7f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                    playerBox(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.72f).dp).aspectRatio(16f / 9f))
                }
                Header(shown, playback, source, { source = it }, onChannel)
            }
            Comments(platform, shown, translator, settings, Modifier.weight(1f).fillMaxHeight()) {}
        }
        else -> Column(Modifier.fillMaxSize()) {
            playerBox(Modifier.statusBarsPadding().fillMaxWidth().aspectRatio(16f / 9f))
            Comments(platform, shown, translator, settings, Modifier.fillMaxSize().navigationBarsPadding()) {
                Header(shown, playback, source, { source = it }, onChannel)
                HorizontalDivider()
            }
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
    header: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val comments = remember(item.id) { Paged(scope) { platform.comments(item.id, it) } }
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
                    )
                }
                item { PagedFooter(thread, empty = "답글이 없습니다", modifier = Modifier.padding(start = 44.dp)) }
            }
        }
        item { PagedFooter(comments, empty = "댓글이 없습니다") }
    }
}

@Composable
private fun Header(item: StreamItem, playback: Playback?, source: Source?, onSource: (Source) -> Unit, onChannel: (Channel) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.published().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (playback != null && source != null && playback.sources.size > 1) {
                var open by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { open = true }) { Text("화질 ${source.label()}") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        playback.sources.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label()) }, onClick = { open = false; onSource(option) })
                        }
                    }
                }
            }
        }
        val channel = item.channel
        Row(
            Modifier.fillMaxWidth().clickable(enabled = channel.id.isNotEmpty()) { onChannel(channel) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(channel.avatarUrl, 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(channel.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val description = playback?.description.orEmpty()
        if (description.isNotBlank()) {
            var expanded by remember { mutableStateOf(false) }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
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
            Text(comment.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            when (translation) {
                is Translation.Done -> Text(
                    translation.text,
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    TextButton(onClick = onReplies) {
                        Text(if (repliesOpen) "답글 접기" else if (comment.replyCount > 0) "답글 ${comment.replyCount}개" else "답글")
                    }
                }
                if (canTranslate && needsTranslation(comment.text)) {
                    when (translation) {
                        Translation.Loading -> TextButton(onClick = {}, enabled = false) { Text("번역 중") }
                        is Translation.Done -> TextButton(onClick = onHide) { Text("번역 숨기기") }
                        is Translation.Failed -> TextButton(onClick = onTranslate) { Text("다시 번역") }
                        null -> TextButton(onClick = onTranslate) { Text("번역") }
                    }
                }
            }
        }
    }
}

fun compactCount(n: Int): String = when {
    n >= 100_000_000 -> "%.1f억".format(n / 100_000_000.0)
    n >= 10_000 -> "%.1f만".format(n / 10_000.0)
    n >= 1_000 -> "%.1f천".format(n / 1_000.0)
    else -> n.toString()
}.replace(".0", "")
