package com.bitchat.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter

@Composable
fun rememberAppIconPainter(icon: ImageVector): Painter {
    return rememberVectorPainter(image = icon)
}
