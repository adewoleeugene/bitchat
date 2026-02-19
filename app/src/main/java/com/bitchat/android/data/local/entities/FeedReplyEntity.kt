package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "feed_replies")
data class FeedReplyEntity(
    @PrimaryKey
    val replyId: String,
    val parentPostId: String,
    val authorPeerID: String,
    val authorNickname: String,
    val content: String,
    val timestamp: Long,
    val receivedAt: Long
)
