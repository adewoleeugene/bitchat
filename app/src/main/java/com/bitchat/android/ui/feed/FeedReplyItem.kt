package com.bitchat.android.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.bitchat.android.data.local.entities.FeedReplyEntity
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.SatoshiFamily

@Composable
fun FeedReplyItem(
    reply: FeedReplyEntity,
    modifier: Modifier = Modifier
) {
    val accentColor = BitchatColors.Border

    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = accentColor,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "@${reply.authorNickname}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitchatColors.MeshChannel,
                    fontFamily = SatoshiFamily
                )
                Text(
                    text = formatRelativeTime(reply.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = BitchatColors.TextTertiary,
                    fontFamily = SatoshiFamily
                )
            }
            Text(
                text = reply.content,
                style = MaterialTheme.typography.bodySmall,
                color = BitchatColors.TextPrimary,
                fontFamily = SatoshiFamily,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
