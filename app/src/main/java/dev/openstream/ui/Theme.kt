package dev.openstream.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.openstream.settings.ThemeMode

// Neutral graphite with one coral accent. Layers are told apart by container tone, not shadows.
private val Light = lightColorScheme(
    primary = Color(0xFFB8382D), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD5), onPrimaryContainer = Color(0xFF410001),
    secondary = Color(0xFF444846), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8EAE6), onSecondaryContainer = Color(0xFF1A1C1B),
    tertiary = Color(0xFFB8382D), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD5), onTertiaryContainer = Color(0xFF410001),
    background = Color(0xFFFAFAF8), onBackground = Color(0xFF1A1C1B),
    surface = Color(0xFFFAFAF8), onSurface = Color(0xFF1A1C1B),
    surfaceVariant = Color(0xFFE1E4E0), onSurfaceVariant = Color(0xFF444846),
    surfaceTint = Color(0xFFFAFAF8),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF4F5F2),
    surfaceContainer = Color(0xFFEEEFEC), surfaceContainerHigh = Color(0xFFE8EAE6),
    surfaceContainerHighest = Color(0xFFE1E4E0),
    outline = Color(0xFF747976), outlineVariant = Color(0xFFC4C8C4),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

// Dark is pitch black: on OLED the chrome disappears and only the video and the accent carry light.
private val Dark = darkColorScheme(
    primary = Color(0xFFFF6B5E), onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF3A1512), onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFB4B8B6), onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1A1A1A), onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFFFF6B5E), onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF3A1512), onTertiaryContainer = Color(0xFFFFDAD5),
    background = Color(0xFF000000), onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF000000), onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF222222), onSurfaceVariant = Color(0xFFA6ABA9),
    surfaceTint = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF000000),
    surfaceContainer = Color(0xFF000000), surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF1E1E1E),
    outline = Color(0xFF6E7371), outlineVariant = Color(0xFF242424),
    error = Color(0xFFFF8A80), onError = Color(0xFF3B0A06),
    errorContainer = Color(0xFF5C1A14), onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // The system bars are drawn over the app, so their icons follow the app's theme, not the system's.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
