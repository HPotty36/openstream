@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.openstream.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import dev.openstream.App
import dev.openstream.MainActivity
import dev.openstream.R
import dev.openstream.settings.LeaveMode
import kotlinx.coroutines.flow.Flow

fun buildPlayer(context: Context): ExoPlayer =
    // Fallback: when a phone's hardware decoder gives up on a stream, try its next decoder instead of failing.
    ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        // Audio focus: a call or another player pauses this one, and unplugged headphones do too.
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        // Keeps the network awake while sound plays with the screen off.
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build().apply { playWhenReady = true }

/**
 * What every player screen does around its player: the keyboard shortcuts, pausing when covered,
 * and what happens when the app is left ([leaveMode]).
 */
@Composable
fun PlayerHousekeeping(
    player: Player,
    title: String,
    active: Boolean,
    leaveMode: LeaveMode,
    keys: Flow<Int>,
    onPlaying: (Boolean) -> Unit,
    onFullscreenKey: () -> Unit,
) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            onPlaying(false)
            PlaybackService.stop(context)
        }
    }
    LaunchedEffect(active, playing) { if (active) onPlaying(playing) }
    LaunchedEffect(active) { if (!active) player.pause() }

    val mode by rememberUpdatedState(leaveMode)
    val name by rememberUpdatedState(title)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (mode != LeaveMode.AUDIO) player.pause() }
    // The service starts while the app is still in front: Android refuses to start one from the background.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { if (mode == LeaveMode.AUDIO && player.isPlaying) PlaybackService.start(context, name) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { PlaybackService.stop(context) }

    val fullscreenKey by rememberUpdatedState(onFullscreenKey)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        keys.collect { code ->
            when (code) {
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K -> if (player.isPlaying) player.pause() else player.play()
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_J -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_L -> player.seekTo(player.currentPosition + 10_000)
                KeyEvent.KEYCODE_F -> fullscreenKey()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
            }
        }
    }
}

/**
 * Keeps the app alive while a video's sound plays with the app out of sight. The player itself
 * stays with its screen; this only holds the notification that lets Android keep the process.
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "백그라운드 재생", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(intent?.getStringExtra(TITLE) ?: getString(R.string.app_name))
            .setContentText("재생 중")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "정지", stop)
            .build()
        // Always first, also for a stop: a service asked to start in the foreground has to do so before it may end.
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, notification)
        if (intent?.action == STOP || intent?.action == QUIET_STOP) {
            if (intent.action == STOP) (application as App).playerKeys.tryEmit(KeyEvent.KEYCODE_MEDIA_PAUSE)
            end()
        }
        return START_NOT_STICKY
    }

    // Swiping the app away from the recent apps ends the playback with it.
    override fun onTaskRemoved(rootIntent: Intent?) = end()

    private fun end() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val ID = 7
        private const val TITLE = "title"
        private const val STOP = "dev.openstream.STOP"
        private const val QUIET_STOP = "dev.openstream.QUIET_STOP"
        @Volatile private var running = false

        fun start(context: Context, title: String) {
            running = true
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java).putExtra(TITLE, title))
            }.onFailure { running = false }
        }

        /** Ends the service without touching the player. */
        fun stop(context: Context) {
            if (!running) return
            running = false
            runCatching { context.startService(Intent(context, PlaybackService::class.java).setAction(QUIET_STOP)) }
        }
    }
}
