package com.bitchat.android.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.BitchatColors

object FeedEmojis {
    val ALL = listOf(
        "\uD83D\uDC4D", // thumbs up
        "\u2764\uFE0F", // red heart
        "\uD83D\uDD25", // fire
        "\uD83D\uDE02", // laugh
        "\uD83D\uDE2E", // surprised
        "\uD83D\uDC4E"  // thumbs down
    )
}

@Composable
fun EmojiReactionPicker(
    onEmojiSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(BitchatColors.BackgroundElevated, RoundedCornerShape(8.dp))
            .border(1.dp, BitchatColors.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FeedEmojis.ALL.forEach { emoji ->
            Text(
                text = emoji,
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable { onEmojiSelected(emoji) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
