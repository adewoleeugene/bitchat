package com.bitchat.android.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object BitchatShapes {
    val None = RoundedCornerShape(0.dp)
    val Small = RoundedCornerShape(2.dp)
    val Medium = RoundedCornerShape(4.dp)
    val Large = RoundedCornerShape(8.dp)
    val XLarge = RoundedCornerShape(12.dp)
    val XXLarge = RoundedCornerShape(16.dp)
    val Circle = CircleShape

    // Semantic shapes
    val MessageBubble = RoundedCornerShape(6.dp)
    val Button = RoundedCornerShape(8.dp)
    val Card = RoundedCornerShape(12.dp)
    val Sheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

    val MaterialShapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(6.dp),
        medium = RoundedCornerShape(8.dp),
        large = RoundedCornerShape(12.dp),
        extraLarge = RoundedCornerShape(16.dp)
    )
}
