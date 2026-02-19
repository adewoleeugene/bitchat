package com.bitchat.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter

/**
 * Custom Painter that renders pixel art icons from a grid array.
 * Each cell in the grid maps to a filled square pixel with a visible
 * gap between cells, creating a classic retro pixel-art look.
 *   0 = transparent
 *   1 = filled with the given color
 *
 * The gap between pixels makes individual blocks visible so the icon
 * looks distinctly pixelated rather than like a smooth vector shape.
 *
 * Usage with Icon():
 *   Icon(
 *       painter = rememberPixelPainter(PixelIcons.Send),
 *       contentDescription = "Send",
 *       tint = Color.Green
 *   )
 */
class PixelArtPainter(
    private val grid: Array<IntArray>,
    private val color: Color = Color.White
) : Painter() {

    private val rows = grid.size
    private val cols = grid.maxOfOrNull { it.size } ?: 0

    override val intrinsicSize: Size
        get() = Size(cols.toFloat(), rows.toFloat())

    override fun DrawScope.onDraw() {
        if (rows == 0 || cols == 0) return
        val pxW = size.width / cols
        val pxH = size.height / rows
        // Gap is ~15% of pixel size — enough to see individual blocks
        // but not so large that the icon becomes unreadable
        val gap = (pxW * 0.15f).coerceAtLeast(0.5f)
        for (row in grid.indices) {
            for (col in grid[row].indices) {
                if (grid[row][col] != 0) {
                    drawRect(
                        color = color,
                        topLeft = Offset(col * pxW + gap / 2f, row * pxH + gap / 2f),
                        size = Size(pxW - gap, pxH - gap)
                    )
                }
            }
        }
    }
}

/**
 * Remember a PixelArtPainter for use with Icon() composable.
 * The tint parameter on Icon() will override the painter's color,
 * so we use Color.White as the base (tint multiplies with it).
 */
@Composable
fun rememberPixelPainter(grid: Array<IntArray>): Painter {
    return remember(grid) { PixelArtPainter(grid) }
}
