package dev.openstream

import android.app.Application
import dev.openstream.settings.AppSettings
import dev.openstream.settings.SecretStore

class App : Application() {
    val secrets: SecretStore by lazy { SecretStore() }
    val settings: AppSettings by lazy { AppSettings(this, secrets) }
}
