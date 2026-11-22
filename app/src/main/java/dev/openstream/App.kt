package dev.openstream

import android.app.Application
import dev.openstream.feed.FeedRefresher
import dev.openstream.library.Library
import dev.openstream.platform.PlatformId
import dev.openstream.platform.StreamingPlatform
import dev.openstream.platform.youtube.YouTubePlatform
import dev.openstream.settings.AppSettings
import dev.openstream.settings.SecretStore
import dev.openstream.translate.Translator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.OkHttpClient

class App : Application() {
    /** For work that has to finish even when the screen that started it is gone. */
    val scope = MainScope()
    val secrets: SecretStore by lazy { SecretStore() }
    val settings: AppSettings by lazy { AppSettings(this, secrets) }
    val library: Library by lazy { Library(this) }

    val http: OkHttpClient by lazy { OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build() }
    val youtube: StreamingPlatform by lazy { YouTubePlatform(http) }

    val chzzk: StreamingPlatform by lazy { dev.openstream.platform.chzzk.ChzzkPlatform(http) }

    val twitch: StreamingPlatform by lazy { dev.openstream.platform.twitch.TwitchPlatform(http) }

    /** In the order the search offers them. */
    val platforms: List<StreamingPlatform> by lazy { listOf(youtube, chzzk, twitch) }

    /** Null for a platform this build does not know. */
    fun platform(id: PlatformId): StreamingPlatform? = platforms.firstOrNull { it.id == id }

    val downloads: dev.openstream.download.Downloads by lazy { dev.openstream.download.Downloads(this, http, scope) }

    val feed: FeedRefresher by lazy { FeedRefresher(library, ::platform, settings, scope) }

    val sponsorBlock: dev.openstream.sponsor.SponsorBlock by lazy { dev.openstream.sponsor.SponsorBlock(http) }

    val updater: dev.openstream.update.Updater by lazy {
        dev.openstream.update.Updater(
            installedVersion = BuildConfig.VERSION_NAME,
            // A debug build is a different app id; the release APK would install beside it, not over it.
            enabled = !BuildConfig.DEBUG,
            dir = java.io.File(cacheDir, "update"),
            install = { dev.openstream.update.installApk(this, it) },
            lastCheck = { settings.lastUpdateCheck },
            saveLastCheck = { settings.lastUpdateCheck = it },
            http = http,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Keeps the background check registered across updates and restarts; it does nothing when the switch is off.
        if (settings.notifyUpdates.value) dev.openstream.notify.scheduleUpdates(this, true)
    }

    /** Key codes of player shortcuts, from the activity that sees the keys to the video that acts on them. */
    val playerKeys = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    /** True while a video is the screen in front; only then do the shortcut keys belong to the player. */
    @Volatile var watching = false

    /** True while the video in front is playing; leaving the app then can shrink it into a small window. */
    val playing = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** True while the app is shown as that small window; the screen then draws nothing but the video. */
    val inPip = kotlinx.coroutines.flow.MutableStateFlow(false)

    val autoTranslate: dev.openstream.translate.AutoTranslate by lazy { dev.openstream.translate.AutoTranslate(this, translator, scope) }

    // A batch of comments takes longer than a page load, so translation waits longer for its answer.
    val translator: Translator by lazy {
        Translator(http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build(), settings::apiKeyBlocking)
    }
}
