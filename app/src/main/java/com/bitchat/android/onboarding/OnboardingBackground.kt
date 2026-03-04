package com.bitchat.android.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.draw.drawWithCache

@Composable
fun OnboardingBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val stripeCount = 22
                val stripeWidth = size.width / stripeCount.toFloat()
                val baseColor = Color(0xFF0D0D0F)
                val stripeDark = Color(0xFF050506)
                val stripeLight = Color(0xFF1A1A1E)
                val topGlow = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.05f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.22f)
                    )
                )

                onDrawBehind {
                    drawRect(baseColor)

                    for (i in 0 until stripeCount) {
                        val x = i * stripeWidth
                        val stripeColor = if (i % 2 == 0) stripeDark.copy(alpha = 0.32f) else stripeLight.copy(alpha = 0.10f)
                        drawRect(
                            color = stripeColor,
                            topLeft = Offset(x, 0f),
                            size = Size(stripeWidth, size.height)
                        )
                    }

                    // Subtle angled shimmer to mimic the textile-like pattern.
                    withTransform({
                        rotate(degrees = -8f, pivot = Offset(size.width / 2f, size.height / 2f))
                    }) {
                        for (i in 0..16) {
                            val y = i * (size.height / 16f)
                            drawRect(
                                color = Color.White.copy(alpha = 0.015f),
                                topLeft = Offset(-size.width * 0.15f, y),
                                size = Size(size.width * 1.3f, 2f)
                            )
                        }
                    }

                    drawRect(brush = topGlow)
                }
            },
        content = content
    )
}
