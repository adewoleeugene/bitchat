package com.bitchat.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.WindowInsetsController
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

// Dark-only color scheme per BitChat Design System
private val BitchatColorScheme = darkColorScheme(
    primary = BitchatColors.AccentGreen,
    onPrimary = BitchatColors.BackgroundDeep,
    secondary = BitchatColors.AccentGreen,
    onSecondary = BitchatColors.BackgroundDeep,
    background = BitchatColors.Background,
    onBackground = BitchatColors.TextPrimary,
    surface = BitchatColors.BackgroundElevated,
    onSurface = BitchatColors.TextPrimary,
    surfaceVariant = BitchatColors.SurfaceVariant,
    onSurfaceVariant = BitchatColors.TextSecondary,
    surfaceContainer = BitchatColors.BackgroundLayer1,
    surfaceContainerHigh = BitchatColors.BackgroundElevated,
    surfaceContainerHighest = BitchatColors.SurfaceVariant,
    primaryContainer = BitchatColors.GlowGreen,
    error = BitchatColors.StatusError,
    onError = BitchatColors.TextPrimary,
    outline = BitchatColors.Border,
    outlineVariant = BitchatColors.BorderHover
)

@Composable
fun BitchatTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = 0
            }
            window.navigationBarColor = BitchatColors.Background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    MaterialTheme(
        colorScheme = BitchatColorScheme,
        typography = Typography,
        shapes = BitchatShapes.MaterialShapes,
        content = content
    )
}
