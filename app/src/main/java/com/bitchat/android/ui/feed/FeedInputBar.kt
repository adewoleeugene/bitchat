package com.bitchat.android.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.AppIcons
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.SatoshiFamily
import com.bitchat.android.ui.theme.rememberAppIconPainter

@Composable
fun FeedInputBar(
    onNewPost: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = BitchatColors.BackgroundElevated
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Share with the mesh",
                style = MaterialTheme.typography.labelSmall,
                color = BitchatColors.TextSecondary,
                fontFamily = SatoshiFamily,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .background(BitchatColors.ButtonPrimaryBg, RoundedCornerShape(10.dp))
                    .clickable { onNewPost() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = rememberAppIconPainter(AppIcons.Add),
                        contentDescription = null,
                        tint = BitchatColors.ButtonPrimaryFg,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "New Post",
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 12.sp,
                        color = BitchatColors.ButtonPrimaryFg,
                        fontFamily = SatoshiFamily
                    )
                }
            }
        }
    }
}
