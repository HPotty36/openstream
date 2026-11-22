package dev.openstream

import android.app.Application
import dev.openstream.platform.StreamingPlatform
import dev.openstream.platform.youtube.YouTubePlatform
import dev.openstream.settings.AppSettings
import dev.openstream.settings.SecretStore
import dev.openstream.translate.Translator
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class App : Application() {
    val secrets: SecretStore by lazy { SecretStore() }
    val settings: AppSettings by lazy { AppSettings(this, secrets) }

    val http: OkHttpClient by lazy { OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build() }
    val youtube: StreamingPlatform by lazy { YouTubePlatform(http) }

    /** Key codes of player shortcuts, from the activity that sees the keys to the video that acts on them. */
    val playerKeys = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 8)
    /** True while a video is the screen in front; only then do the shortcut keys belong to the player. */
    @Volatile var watching = false

    // A batch of comments takes longer than a page load, so translation waits longer for its answer.
    val translator: Translator by lazy {
        Translator(http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build(), settings::apiKeyBlocking)
    }
}
