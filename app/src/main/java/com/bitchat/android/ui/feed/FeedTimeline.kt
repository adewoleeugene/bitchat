package com.bitchat.android.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.data.local.entities.FeedPostEntity
import com.bitchat.android.ui.icons.LucideIcon
import com.bitchat.android.ui.icons.LucideIconSet
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.SatoshiFamily
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun FeedTimeline(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val posts by viewModel.feedPosts.observeAsState(emptyList())
    val expandedPostId by viewModel.expandedPostId.observeAsState(null)
    val reactionsMap by viewModel.feedReactions.observeAsState(emptyMap())
    val repliesMap by viewModel.feedReplies.observeAsState(emptyMap())
    val canManagePins = viewModel.canManageFeedPins()
    val pinnedPosts = posts.filter { it.isPinned }
        .sortedByDescending { it.pinnedAt ?: 0L }
        .take(4)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val indexByPostId = posts.mapIndexed { index, post -> post.postId to index }.toMap()
    var focusedPostId by remember { mutableStateOf<String?>(null) }
    var focusTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(focusedPostId, focusTrigger) {
        val currentFocused = focusedPostId
        val currentTrigger = focusTrigger
        if (currentFocused != null) {
            delay(1800)
            if (focusedPostId == currentFocused && focusTrigger == currentTrigger) {
                focusedPostId = null
            }
        }
    }

    if (posts.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No posts yet",
                color = BitchatColors.TextSecondary,
                fontFamily = SatoshiFamily,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            if (pinnedPosts.isNotEmpty()) {
                PinnedLinksBar(
                    pinnedPosts = pinnedPosts,
                    onJumpToPost = { postId ->
                        val index = indexByPostId[postId] ?: return@PinnedLinksBar
                        focusTrigger += 1
                        focusedPostId = postId
                        viewModel.expandPost(postId)
                        scope.launch { listState.animateScrollToItem(index = index) }
                    }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items(posts, key = { it.postId }) { post ->
                    FeedPostCard(
                        post = post,
                        isExpanded = expandedPostId == post.postId,
                        reactions = reactionsMap[post.postId] ?: emptyList(),
                        replies = repliesMap[post.postId] ?: emptyList(),
                        myPeerID = viewModel.meshService.myPeerID,
                        onToggleExpand = {
                            viewModel.expandPost(
                                if (expandedPostId == post.postId) null else post.postId
                            )
                        },
                        onReaction = { emoji ->
                            viewModel.toggleFeedReaction(post.postId, emoji)
                        },
                        onReply = { content ->
                            viewModel.createFeedReply(post.postId, content)
                        },
                        canManagePin = post.isOwnPost || canManagePins,
                        onTogglePin = { shouldPin ->
                            viewModel.setFeedPostPinned(post.postId, shouldPin)
                        },
                        isFocused = focusedPostId == post.postId
                    )
                }
            }
        }
    }
}

@Composable
private fun PinnedLinksBar(
    pinnedPosts: List<FeedPostEntity>,
    onJumpToPost: (String) -> Unit
) {
    data class ChipPalette(val background: Color, val text: Color)
    val chipPalettes = listOf(
        ChipPalette(
            background = Color(0xFFF6E1E8), // muted rose
            text = Color(0xFF5A2434)
        ),
        ChipPalette(
            background = Color(0xFFE1EEFB), // muted sky
            text = Color(0xFF214565)
        ),
        ChipPalette(
            background = Color(0xFFE2F3E8), // muted mint
            text = Color(0xFF28503A)
        ),
        ChipPalette(
            background = Color(0xFFF8EEDB), // muted amber
            text = Color(0xFF5D441B)
        ),
        ChipPalette(
            background = Color(0xFFE9E3F8), // muted lavender
            text = Color(0xFF453169)
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BitchatColors.Background)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LucideIcon(
                imageVector = LucideIconSet.Pin,
                contentDescription = "Pinned links",
                tint = BitchatColors.MeshChannel,
                modifier = Modifier
                    .size(14.dp)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pinnedPosts.forEach { post ->
                    val palette = chipPalettes[abs(post.postId.hashCode()) % chipPalettes.size]
                    Text(
                        text = post.content.ifBlank { "@${post.authorNickname}" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.text,
                        fontFamily = SatoshiFamily,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.background)
                            .clickable { onJumpToPost(post.postId) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                            .widthIn(max = 156.dp)
                    )
                }
            }
        }
    }
}
