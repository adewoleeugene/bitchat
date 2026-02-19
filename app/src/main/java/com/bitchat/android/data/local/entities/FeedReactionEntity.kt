package com.bitchat.android.data.local.entities

import androidx.room.Entity

@Entity(
    tableName = "feed_reactions",
    primaryKeys = ["postId", "reactorPeerID", "emoji"]
)
data class FeedReactionEntity(
    val postId: String,
    val reactorPeerID: String,
    val reactorNickname: String,
    val emoji: String,
    val timestamp: Long,
    val receivedAt: Long
)
