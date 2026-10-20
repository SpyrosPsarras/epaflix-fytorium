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
    primary = Color(0xFF3F6A2A), onPrimary = Color.White,
    primaryContainer = Color(0xFFBFEBA4), onPrimaryContainer = Color(0xFF0B2100),
    secondary = Color(0xFF53634B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E8C8), onSecondaryContainer = Color(0xFF12210B),
    background = Color(0xFFF7FAF0), onBackground = Color(0xFF1A1D17),
    surface = Color(0xFFF7FAF0), onSurface = Color(0xFF1A1D17),
    surfaceVariant = Color(0xFFE0E5D7), onSurfaceVariant = Color(0xFF43483E),
    surfaceBright = Color(0xFFF7FAF0), surfaceDim = Color(0xFFD8DCCF), surfaceTint = Color(0xFF3F6A2A),
    surfaceContainerLow = Color(0xFFF1F5E8), surfaceContainerLowest = Color.White,
    surfaceContainerHigh = Color(0xFFE6EADD), surfaceContainerHighest = Color(0xFFE0E4D7),
    inverseSurface = Color(0xFF2F332B), inverseOnSurface = Color(0xFFF0F3E6), inversePrimary = Color(0xFFB5D99C),
    tertiary = Color(0xFF805500), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6E8C8), onTertiaryContainer = Color(0xFF12210B),
    surfaceContainer = Color(0xFFECF0E3), outline = Color(0xFF74796D), outlineVariant = Color(0xFFC4C9BB)
)
val DarkColors = darkColorScheme(
    primary = Color(0xFFB5D99C), onPrimary = Color(0xFF173807),
    primaryContainer = Color(0xFF2B5017), onPrimaryContainer = Color(0xFFD0F4B7),
    secondary = Color(0xFFBBCDB0), onSecondary = Color(0xFF263420),
    secondaryContainer = Color(0xFF3D4B35), onSecondaryContainer = Color(0xFFD6E8C8),
    background = Color(0xFF12150F), onBackground = Color(0xFFE1E5D8),
    surface = Color(0xFF12150F), onSurface = Color(0xFFE1E5D8),
    surfaceVariant = Color(0xFF23271F), onSurfaceVariant = Color(0xFFC4C9BB),
    surfaceBright = Color(0xFF383D34), surfaceDim = Color(0xFF12150F), surfaceTint = Color(0xFFB5D99C),
    surfaceContainerLow = Color(0xFF1A1D17), surfaceContainerLowest = Color(0xFF0D100A),
    surfaceContainerHigh = Color(0xFF2D3229), surfaceContainerHighest = Color(0xFF383D34),
    inverseSurface = Color(0xFFE1E5D8), inverseOnSurface = Color(0xFF2D3229), inversePrimary = Color(0xFF3F6A2A),
    tertiary = Color(0xFFE9C46A), onTertiary = Color(0xFF423500),
    tertiaryContainer = Color(0xFF3D4B35), onTertiaryContainer = Color(0xFFD6E8C8),
    surfaceContainer = Color(0xFF23271F), outline = Color(0xFF8E9487), outlineVariant = Color(0xFF43483E)
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
