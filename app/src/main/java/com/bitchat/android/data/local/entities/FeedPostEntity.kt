package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "feed_posts")
data class FeedPostEntity(
    @PrimaryKey
    val postId: String,
    val channelKey: String,
    val authorPeerID: String,
    val authorNickname: String,
    val content: String,
    val hasImage: Boolean = false,
    val imagePath: String? = null,
    val hasAudio: Boolean = false,
    val audioPath: String? = null,
    val timestamp: Long,
    val receivedAt: Long,
    val reactionCount: Int = 0,
    val replyCount: Int = 0,
    val isOwnPost: Boolean = false,
    val isPinned: Boolean = false,
    val pinnedAt: Long? = null,
    val pinnedByPeerID: String? = null,
    val pinVersion: Long = 0L
)
