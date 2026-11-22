package dev.openstream.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Comments and chat are a lot of short text and little thinking, so the fast model translates them. */
const val TRANSLATION_MODEL = "claude-haiku-5-5"

enum class ThemeMode(val id: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }

fun themeModeById(id: String?): ThemeMode = ThemeMode.entries.firstOrNull { it.id == id } ?: ThemeMode.SYSTEM

/** The theme, the Anthropic API key (stored encrypted) and the content switches. */
class AppSettings(context: Context, private val secrets: SecretStore, prefsName: String = "settings") {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(themeModeById(prefs.getString("theme", null)))
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.id).apply()
        _themeMode.value = mode
    }

    private val _showAgeRestricted = MutableStateFlow(prefs.getBoolean("show_age_restricted", true))
    val showAgeRestricted: StateFlow<Boolean> = _showAgeRestricted

    fun setShowAgeRestricted(on: Boolean) {
        prefs.edit().putBoolean("show_age_restricted", on).apply()
        _showAgeRestricted.value = on
    }

    private var apiKey: String? = prefs.getString("api_key_enc", null)?.let { runCatching { secrets.decrypt(it) }.getOrNull() }
    private val _apiKeyHint = MutableStateFlow(hint())

    /** Last four characters of the saved key, or null when none is saved. */
    val apiKeyHint: StateFlow<String?> = _apiKeyHint

    private fun hint() = apiKey?.takeLast(4)

    fun setApiKey(key: String?) {
        apiKey = key?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit().apply {
            if (apiKey == null) remove("api_key_enc") else putString("api_key_enc", secrets.encrypt(apiKey!!))
        }.apply()
        _apiKeyHint.value = hint()
    }

    fun apiKeyBlocking(): String? = apiKey
}
