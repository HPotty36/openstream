@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.openstream.watch

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import dev.openstream.settings.LeaveMode
import kotlinx.coroutines.flow.Flow

/** Plays a file saved on the phone. Nothing but the video: there are no comments to show for a file. */
@Composable
fun LocalScreen(
    uri: Uri,
    title: String,
    active: Boolean,
    pip: Boolean,
    leaveMode: LeaveMode,
    keys: Flow<Int>,
    onPlaying: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val player = remember {
        buildPlayer(context).apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { failed = true }
        }
        player.addListener(listener)
        onDispose {
            player.release()
            (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    PlayerHousekeeping(player, title, active, leaveMode, keys, onPlaying) {}

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setFullscreenButtonClickListener { full ->
                        (it as? Activity)?.requestedOrientation =
                            if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            },
            update = {
                it.setFullscreenButtonState(landscape)
                it.useController = !pip
            },
            // Upright, the buttons stay clear of the status bar and the gesture bar; turned, the picture takes the whole screen.
            modifier = if (landscape || pip) Modifier.fillMaxSize() else Modifier.fillMaxSize().systemBarsPadding(),
        )
        if (failed) {
            Text(
                "이 파일을 재생하지 못했습니다.",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}
