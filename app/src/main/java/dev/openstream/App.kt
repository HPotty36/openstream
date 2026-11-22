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

    /** Null for the platforms that are not built yet. */
    fun platform(id: PlatformId): StreamingPlatform? = if (id == PlatformId.YOUTUBE) youtube else null

    val feed: FeedRefresher by lazy { FeedRefresher(library, ::platform, settings, scope) }

    /** Key codes of player shortcuts, from the activity that sees the keys to the video that acts on them. */
    val playerKeys = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    /** True while a video is the screen in front; only then do the shortcut keys belong to the player. */
    @Volatile var watching = false

    // A batch of comments takes longer than a page load, so translation waits longer for its answer.
    val translator: Translator by lazy {
        Translator(http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build(), settings::apiKeyBlocking)
    }
}
