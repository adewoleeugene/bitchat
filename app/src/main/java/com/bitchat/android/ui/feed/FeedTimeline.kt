package com.bitchat.android.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.SatoshiFamily

@Composable
fun FeedTimeline(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val posts by viewModel.feedPosts.observeAsState(emptyList())
    val expandedPostId by viewModel.expandedPostId.observeAsState(null)
    val reactionsMap by viewModel.feedReactions.observeAsState(emptyMap())
    val repliesMap by viewModel.feedReplies.observeAsState(emptyMap())

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
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
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
                    }
                )
            }
        }
    }
}
