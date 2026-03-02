package com.bitchat.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.bitchat.android.ui.icons.LucideIconSet

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
    fun mappedIcon(): ImageVector? = when {
        grid === PixelIcons.Add -> LucideIconSet.Plus
        grid === PixelIcons.Remove -> LucideIconSet.Minus
        grid === PixelIcons.ArrowBack -> LucideIconSet.ArrowLeft
        grid === PixelIcons.ArrowDown -> LucideIconSet.ArrowDown
        grid === PixelIcons.ArrowRight -> LucideIconSet.ArrowRight
        grid === PixelIcons.ArrowUp -> LucideIconSet.ArrowUp
        grid === PixelIcons.Attachment -> LucideIconSet.Attachment
        grid === PixelIcons.Battery -> LucideIconSet.Battery
        grid === PixelIcons.Bell -> LucideIconSet.Bell
        grid === PixelIcons.Bluetooth -> LucideIconSet.Bluetooth
        grid === PixelIcons.BookmarkFilled -> LucideIconSet.BookmarkCheck
        grid === PixelIcons.BookmarkOutline -> LucideIconSet.Bookmark
        grid === PixelIcons.Bug -> LucideIconSet.Bug
        grid === PixelIcons.Camera -> LucideIconSet.Camera
        grid === PixelIcons.Check -> LucideIconSet.Check
        grid === PixelIcons.CheckboxOff -> LucideIconSet.Square
        grid === PixelIcons.CheckboxOn -> LucideIconSet.Check
        grid === PixelIcons.Close -> LucideIconSet.X
        grid === PixelIcons.Copy -> LucideIconSet.Copy
        grid === PixelIcons.Devices -> LucideIconSet.Devices
        grid === PixelIcons.Download -> LucideIconSet.Download
        grid === PixelIcons.Email -> LucideIconSet.Mail
        grid === PixelIcons.Explore -> LucideIconSet.Compass
        grid === PixelIcons.File -> LucideIconSet.File
        grid === PixelIcons.Globe -> LucideIconSet.Globe
        grid === PixelIcons.Group -> LucideIconSet.Users
        grid === PixelIcons.Link -> LucideIconSet.Link
        grid === PixelIcons.LocationPin -> LucideIconSet.MapPin
        grid === PixelIcons.Lock -> LucideIconSet.Lock
        grid === PixelIcons.Map -> LucideIconSet.Map
        grid === PixelIcons.Mic -> LucideIconSet.Mic
        grid === PixelIcons.Network -> LucideIconSet.Network
        grid === PixelIcons.Pause -> LucideIconSet.Pause
        grid === PixelIcons.PinDrop -> LucideIconSet.MapPin
        grid === PixelIcons.Play -> LucideIconSet.Play
        grid === PixelIcons.Power -> LucideIconSet.Power
        grid === PixelIcons.QrCode -> LucideIconSet.QrCode
        grid === PixelIcons.Route -> LucideIconSet.Route
        grid === PixelIcons.Settings -> LucideIconSet.Settings
        grid === PixelIcons.Shield -> LucideIconSet.Shield
        grid === PixelIcons.StarFilled -> LucideIconSet.Star
        grid === PixelIcons.StarOutline -> LucideIconSet.Star
        grid === PixelIcons.Sync -> LucideIconSet.RefreshCw
        grid === PixelIcons.Unlock -> LucideIconSet.LockOpen
        grid === PixelIcons.Antenna -> LucideIconSet.Antenna
        grid === PixelIcons.Visibility -> LucideIconSet.Visibility
        grid === PixelIcons.Wallet -> LucideIconSet.Wallet
        grid === PixelIcons.Warning -> LucideIconSet.AlertTriangle
        else -> null
    }

    val lucide = mappedIcon()
    return if (lucide != null) {
        rememberVectorPainter(image = lucide)
    } else {
        remember(grid) { PixelArtPainter(grid) }
    }
}
