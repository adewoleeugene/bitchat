package com.bitchat.android.ui.icons

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Antenna
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.BookmarkCheck
import com.composables.icons.lucide.Bluetooth
import com.composables.icons.lucide.Bug
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.Compass
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.LockOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mail
import com.composables.icons.lucide.Map
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Network
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Power
import com.composables.icons.lucide.QrCode
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Route
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.Wallet
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Battery
import com.composables.icons.lucide.Link

object LucideIconSet {
    val Users: ImageVector = Lucide.Users
    val ArrowLeft: ImageVector = Lucide.ArrowLeft
    val ArrowUp: ImageVector = Lucide.ArrowUp
    val ArrowDown: ImageVector = Lucide.ArrowDown
    val ArrowRight: ImageVector = Lucide.ArrowRight
    val Antenna: ImageVector = Lucide.Antenna
    val Globe: ImageVector = Lucide.Globe
    val Star: ImageVector = Lucide.Star
    val X: ImageVector = Lucide.X
    val Plus: ImageVector = Lucide.Plus
    val Minus: ImageVector = Lucide.Minus
    val Check: ImageVector = Lucide.Check
    val Square: ImageVector = Lucide.Square
    val Copy: ImageVector = Lucide.Copy
    val Download: ImageVector = Lucide.Download
    val Mail: ImageVector = Lucide.Mail
    val Bell: ImageVector = Lucide.Bell
    val Bluetooth: ImageVector = Lucide.Bluetooth
    val Circle: ImageVector = Lucide.Circle
    val Route: ImageVector = Lucide.Route
    val Bug: ImageVector = Lucide.Bug
    val Camera: ImageVector = Lucide.Camera
    val File: ImageVector = Lucide.File
    val Attachment: ImageVector = Lucide.Paperclip
    val Link: ImageVector = Lucide.Link
    val Play: ImageVector = Lucide.Play
    val Pause: ImageVector = Lucide.Pause
    val Mic: ImageVector = Lucide.Mic
    val Map: ImageVector = Lucide.Map
    val Network: ImageVector = Lucide.Network
    val Devices: ImageVector = Lucide.Monitor
    val Compass: ImageVector = Lucide.Compass
    val Power: ImageVector = Lucide.Power
    val Battery: ImageVector = Lucide.Battery
    val Send: ImageVector = Lucide.Send
    val Settings: ImageVector = Lucide.Settings
    val Shield: ImageVector = Lucide.Shield
    val Visibility: ImageVector = Lucide.Eye
    val QrCode: ImageVector = Lucide.QrCode
    val Bookmark: ImageVector = Lucide.Bookmark
    val BookmarkCheck: ImageVector = Lucide.BookmarkCheck
    val Wallet: ImageVector = Lucide.Wallet
    val MapPin: ImageVector = Lucide.MapPin
    val LockOpen: ImageVector = Lucide.LockOpen
    val RefreshCw: ImageVector = Lucide.RefreshCw
    val Lock: ImageVector = Lucide.Lock
    val AlertTriangle: ImageVector = Lucide.TriangleAlert
}

@Composable
fun LucideIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    Icon(
        painter = rememberVectorPainter(image = imageVector),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint
    )
}

val LucideHeaderIconSize: Dp = 22.dp
