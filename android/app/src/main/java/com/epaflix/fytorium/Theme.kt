package com.epaflix.fytorium

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color

val LightColors = lightColorScheme(
    primary = Color(0xFF9A3412), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEDD5), onPrimaryContainer = Color(0xFF7C2D12),
    secondary = Color(0xFF9A3412), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFED7AA), onSecondaryContainer = Color(0xFF7C2D12),
    background = Color(0xFFFFF7ED), onBackground = Color(0xFF292524),
    surface = Color(0xFFFFF7ED), onSurface = Color(0xFF292524),
    surfaceVariant = Color(0xFFFFEDD5), onSurfaceVariant = Color(0xFF44403C),
    surfaceContainerHigh = Color(0xFFF1E9E1), surfaceContainerHighest = Color(0xFFE7DED5),
    tertiary = Color(0xFF805500), onTertiary = Color.White,
    surfaceContainer = Color(0xFFFFEDD5), outline = Color(0xFF78716C)
)
val DarkColors = darkColorScheme(
    primary = Color(0xFFFB923C), onPrimary = Color(0xFF431407),
    primaryContainer = Color(0xFF7C2D12), onPrimaryContainer = Color(0xFFFFEDD5),
    secondary = Color(0xFFFB923C), onSecondary = Color(0xFF431407),
    secondaryContainer = Color(0xFF7C2D12), onSecondaryContainer = Color(0xFFFFEDD5),
    background = Color(0xFF1C1917), onBackground = Color(0xFFFFF7ED),
    surface = Color(0xFF1C1917), onSurface = Color(0xFFFFF7ED),
    surfaceVariant = Color(0xFF292524), onSurfaceVariant = Color(0xFFE7E5E4),
    surfaceContainerHigh = Color(0xFF36312E), surfaceContainerHighest = Color(0xFF403A36),
    tertiary = Color(0xFFE9C46A), onTertiary = Color(0xFF423500),
    surfaceContainer = Color(0xFF292524), outline = Color(0xFFA8A29E)
)
@Composable fun FytoriumTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !dark
            bars.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
