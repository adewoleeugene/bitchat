package com.bitchat.android.ui.feed

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.data.local.entities.FeedPostEntity
import com.bitchat.android.data.local.entities.FeedReactionEntity
import com.bitchat.android.data.local.entities.FeedReplyEntity
import com.bitchat.android.ui.media.VoiceNotePlayer
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.SatoshiFamily
import java.text.SimpleDateFormat
import java.util.*
import java.io.File

@Composable
fun FeedPostCard(
    post: FeedPostEntity,
    isExpanded: Boolean,
    reactions: List<FeedReactionEntity>,
    replies: List<FeedReplyEntity>,
    myPeerID: String,
    onToggleExpand: () -> Unit,
    onReaction: (String) -> Unit,
    onReply: (String) -> Unit,
    canManagePin: Boolean,
    onTogglePin: (Boolean) -> Unit,
    isFocused: Boolean = false,
    modifier: Modifier = Modifier
) {
    val accentColor = if (post.isOwnPost) BitchatColors.AccentGreen else BitchatColors.TextSecondary
    val bubbleBg = if (post.isOwnPost) {
        BitchatColors.AccentGreen.copy(alpha = 0.14f)
    } else {
        BitchatColors.BackgroundElevated
    }
    val focusStrength by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "feed_focus_strength"
    )
    val cardBackground = lerp(bubbleBg, BitchatColors.SurfaceVariant, focusStrength * 0.55f)
    val focusBorderColor by animateColorAsState(
        targetValue = if (isFocused) BitchatColors.AccentGreen.copy(alpha = 0.8f) else Color.Transparent,
        animationSpec = tween(durationMillis = 220),
        label = "feed_focus_border"
    )

    var showEmojiPicker by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .border(
                width = if (isFocused) 1.5.dp else 0.dp,
                color = focusBorderColor,
                shape = RoundedCornerShape(14.dp)
            ),
        shape = RoundedCornerShape(14.dp),
        color = cardBackground
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            // Header: author + timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "@${post.authorNickname}",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontFamily = SatoshiFamily,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (post.isPinned) {
                        Text(
                            text = "PINNED",
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            fontFamily = SatoshiFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (canManagePin) {
                        Text(
                            text = if (post.isPinned) "unpin" else "pin",
                            style = MaterialTheme.typography.labelSmall,
                            color = BitchatColors.TextSecondary,
                            fontFamily = SatoshiFamily,
                            modifier = Modifier.clickable { onTogglePin(!post.isPinned) }
                        )
                    }
                    Text(
                        text = formatRelativeTime(post.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = BitchatColors.TextTertiary,
                        fontFamily = SatoshiFamily
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Content
            if (post.content.isNotBlank()) {
                Text(
                    text = post.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BitchatColors.TextPrimary,
                    fontFamily = SatoshiFamily,
                    lineHeight = 20.sp
                )
            }

            // Image
            if (post.hasImage && post.imagePath != null) {
                Spacer(modifier = Modifier.height(8.dp))
                val bitmap = remember(post.imagePath) {
                    try {
                        BitmapFactory.decodeFile(post.imagePath)
                    } catch (_: Exception) { null }
                }
                bitmap?.let { bmp ->
                    val aspect = bmp.width.toFloat() / bmp.height.toFloat()
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            // Audio
            if (post.hasAudio && !post.audioPath.isNullOrBlank() && File(post.audioPath).exists()) {
                Spacer(modifier = Modifier.height(8.dp))
                VoiceNotePlayer(path = post.audioPath)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Reaction bar
            ReactionBar(
                reactions = reactions,
                myPeerID = myPeerID,
                onAddReaction = { showEmojiPicker = !showEmojiPicker },
                onReaction = { emoji ->
                    onReaction(emoji)
                    showEmojiPicker = false
                }
            )

            // Emoji picker
            if (showEmojiPicker) {
                Spacer(modifier = Modifier.height(4.dp))
                EmojiReactionPicker(
                    onEmojiSelected = { emoji ->
                        onReaction(emoji)
                        showEmojiPicker = false
                    }
                )
            }

            // Reply count + expand toggle
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .clickable { onToggleExpand() }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val replyCount = post.replyCount
                Text(
                    text = if (isExpanded) "hide replies" else "$replyCount ${if (replyCount == 1) "reply" else "replies"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitchatColors.TextSecondary,
                    fontFamily = SatoshiFamily,
                    fontWeight = FontWeight.Medium
                )
            }

            // Expanded: replies + reply input
            if (isExpanded) {
                Spacer(modifier = Modifier.height(6.dp))
                replies.forEach { reply ->
                    FeedReplyItem(reply = reply)
                    Spacer(modifier = Modifier.height(1.dp))
                }

                Spacer(modifier = Modifier.height(6.dp))
                ReplyInput(onReply = onReply)
            }
        }
    }
}

@Composable
private fun ReactionBar(
    reactions: List<FeedReactionEntity>,
    myPeerID: String,
    onAddReaction: () -> Unit,
    onReaction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Group reactions by emoji
    val grouped = reactions.groupBy { it.emoji }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        grouped.forEach { (emoji, reactionList) ->
            val hasMyReaction = reactionList.any { it.reactorPeerID == myPeerID }
            val bgColor = if (hasMyReaction) {
                BitchatColors.SurfaceVariant
            } else {
                BitchatColors.BackgroundElevated
            }

            Row(
                modifier = Modifier
                    .background(bgColor, RoundedCornerShape(12.dp))
                    .clickable { onReaction(emoji) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = emoji, fontSize = 14.sp)
                Text(
                    text = "${reactionList.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = BitchatColors.TextSecondary,
                    fontFamily = SatoshiFamily
                )
            }
        }

        // Add reaction button
        Text(
            text = "+",
            style = MaterialTheme.typography.labelMedium,
            color = BitchatColors.TextSecondary,
            fontFamily = SatoshiFamily,
            modifier = Modifier
                .background(BitchatColors.SurfaceVariant, RoundedCornerShape(12.dp))
                .clickable { onAddReaction() }
                .padding(horizontal = 9.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun ReplyInput(
    onReply: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(BitchatColors.InputFieldBg, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = BitchatColors.TextPrimary,
                    fontFamily = SatoshiFamily
                ),
                cursorBrush = SolidColor(colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (text.text.isNotBlank()) {
                        onReply(text.text.trim())
                        text = TextFieldValue("")
                    }
                }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (text.text.isEmpty()) {
                Text(
                    text = "Reply...",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitchatColors.TextTertiary,
                    fontFamily = SatoshiFamily
                )
            }
        }

        Text(
            text = "send",
            style = MaterialTheme.typography.labelSmall,
            color = if (text.text.isNotBlank()) BitchatColors.TextPrimary else BitchatColors.TextDisabled,
            fontFamily = SatoshiFamily,
            modifier = Modifier
                .clickable(enabled = text.text.isNotBlank()) {
                    if (text.text.isNotBlank()) {
                        onReply(text.text.trim())
                        text = TextFieldValue("")
                    }
                }
                .padding(8.dp)
        )
    }
}

internal fun formatRelativeTime(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestampMs
    return when {
        diff < 60_000 -> "now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> "${diff / 3600_000}h"
        else -> {
            val sdf = SimpleDateFormat("MMM dd", Locale.US)
            sdf.format(Date(timestampMs))
        }
    }
}
