package com.bitchat.android.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.CourierPrimeFamily

@Composable
fun FeedInputBar(
    onNewPost: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .background(BitchatColors.AccentGreen, RoundedCornerShape(8.dp))
                .clickable { onNewPost() }
                .padding(horizontal = 24.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+ New Post",
                style = MaterialTheme.typography.labelMedium,
                color = Color.Black,
                fontFamily = CourierPrimeFamily
            )
        }
    }
}
