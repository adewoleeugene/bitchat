package com.bitchat.android.ui.icons

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Antenna
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.BookmarkCheck
import com.composables.icons.lucide.Bluetooth
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.LockOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mail
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Route
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.Wallet
import com.composables.icons.lucide.X

object LucideIconSet {
    val Users: ImageVector = Lucide.Users
    val ArrowLeft: ImageVector = Lucide.ArrowLeft
    val ArrowUp: ImageVector = Lucide.ArrowUp
    val Antenna: ImageVector = Lucide.Antenna
    val Globe: ImageVector = Lucide.Globe
    val Star: ImageVector = Lucide.Star
    val X: ImageVector = Lucide.X
    val Mail: ImageVector = Lucide.Mail
    val Bluetooth: ImageVector = Lucide.Bluetooth
    val Circle: ImageVector = Lucide.Circle
    val Route: ImageVector = Lucide.Route
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
