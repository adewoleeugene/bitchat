package com.bitchat.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter

/**
 * Legacy API wrapper kept for compatibility with existing callsites.
 * Despite the name, this now returns a vector painter.
 */
@Composable
fun rememberPixelPainter(icon: ImageVector): Painter {
    return rememberVectorPainter(image = icon)
}
