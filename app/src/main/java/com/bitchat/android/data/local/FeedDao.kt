package com.bitchat.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bitchat.android.data.local.entities.FeedPostEntity
import com.bitchat.android.data.local.entities.FeedReactionEntity
import com.bitchat.android.data.local.entities.FeedReplyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {

    // --- Posts ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPost(post: FeedPostEntity)

    @Query("SELECT * FROM feed_posts WHERE channelKey = :channelKey ORDER BY timestamp DESC")
    fun observePostsByChannel(channelKey: String): Flow<List<FeedPostEntity>>

    @Query("SELECT * FROM feed_posts ORDER BY timestamp DESC")
    fun observeAllPosts(): Flow<List<FeedPostEntity>>

    @Query("SELECT * FROM feed_posts ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentPosts(limit: Int = 100): List<FeedPostEntity>

    @Query("SELECT * FROM feed_posts WHERE postId = :postId")
    suspend fun getPostById(postId: String): FeedPostEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM feed_posts WHERE postId = :postId)")
    suspend fun postExists(postId: String): Boolean

    @Query("UPDATE feed_posts SET reactionCount = :count WHERE postId = :postId")
    suspend fun updateReactionCount(postId: String, count: Int)

    @Query("UPDATE feed_posts SET replyCount = :count WHERE postId = :postId")
    suspend fun updateReplyCount(postId: String, count: Int)

    @Query(
        """
        UPDATE feed_posts
        SET hasImage = CASE WHEN hasImage = 1 THEN 1 ELSE :hasImage END,
            imagePath = CASE
                WHEN imagePath IS NOT NULL AND imagePath != '' THEN imagePath
                ELSE :imagePath
            END,
            hasAudio = CASE WHEN hasAudio = 1 THEN 1 ELSE :hasAudio END,
            audioPath = CASE
                WHEN audioPath IS NOT NULL AND audioPath != '' THEN audioPath
                ELSE :audioPath
            END
        WHERE postId = :postId
        """
    )
    suspend fun mergePostMedia(
        postId: String,
        hasImage: Boolean,
        imagePath: String?,
        hasAudio: Boolean,
        audioPath: String?
    ): Int

    @Query(
        """
        UPDATE feed_posts
        SET isPinned = :isPinned,
            pinnedAt = :pinnedAt,
            pinnedByPeerID = :pinnedByPeerID,
            pinVersion = :pinVersion
        WHERE postId = :postId
        """
    )
    suspend fun updatePinState(
        postId: String,
        isPinned: Boolean,
        pinnedAt: Long?,
        pinnedByPeerID: String?,
        pinVersion: Long
    ): Int

    @Query("SELECT COUNT(*) FROM feed_posts WHERE channelKey = :channelKey AND isPinned = 1")
    suspend fun getPinnedCount(channelKey: String): Int

    @Query(
        "SELECT postId FROM feed_posts WHERE channelKey = :channelKey AND isPinned = 1 " +
            "ORDER BY COALESCE(pinnedAt, timestamp) ASC LIMIT 1"
    )
    suspend fun getOldestPinnedPostId(channelKey: String): String?

    // --- Reactions ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReaction(reaction: FeedReactionEntity)

    @Query("DELETE FROM feed_reactions WHERE postId = :postId AND reactorPeerID = :reactorPeerID AND emoji = :emoji")
    suspend fun removeReaction(postId: String, reactorPeerID: String, emoji: String)

    @Query("SELECT * FROM feed_reactions WHERE postId = :postId")
    fun observeReactionsForPost(postId: String): Flow<List<FeedReactionEntity>>

    @Query("SELECT * FROM feed_reactions WHERE postId = :postId")
    suspend fun getReactionsForPost(postId: String): List<FeedReactionEntity>

    @Query("SELECT * FROM feed_reactions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentReactions(limit: Int = 200): List<FeedReactionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM feed_reactions WHERE postId = :postId AND reactorPeerID = :peerID AND emoji = :emoji)")
    suspend fun hasReaction(postId: String, peerID: String, emoji: String): Boolean

    @Query("SELECT COUNT(*) FROM feed_reactions WHERE postId = :postId")
    suspend fun countReactions(postId: String): Int

    // --- Replies ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReply(reply: FeedReplyEntity)

    @Query("SELECT * FROM feed_replies WHERE parentPostId = :postId ORDER BY timestamp ASC")
    fun observeRepliesForPost(postId: String): Flow<List<FeedReplyEntity>>

    @Query("SELECT * FROM feed_replies WHERE parentPostId = :postId ORDER BY timestamp ASC")
    suspend fun getRepliesForPost(postId: String): List<FeedReplyEntity>

    @Query("SELECT * FROM feed_replies ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentReplies(limit: Int = 200): List<FeedReplyEntity>

    @Query("SELECT COUNT(*) FROM feed_posts WHERE authorPeerID = :peerId")
    suspend fun countPostsByAuthor(peerId: String): Int

    @Query("SELECT COUNT(*) FROM feed_replies WHERE authorPeerID = :peerId")
    suspend fun countRepliesByAuthor(peerId: String): Int

    @Query("SELECT COUNT(*) FROM feed_reactions WHERE reactorPeerID = :peerId")
    suspend fun countReactionsByAuthor(peerId: String): Int

    @Query("SELECT timestamp FROM feed_posts WHERE authorPeerID = :peerId AND timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getRecentPostTimestampsByAuthor(peerId: String, since: Long): List<Long>

    @Query("SELECT timestamp FROM feed_replies WHERE authorPeerID = :peerId AND timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getRecentReplyTimestampsByAuthor(peerId: String, since: Long): List<Long>

    @Query("SELECT timestamp FROM feed_reactions WHERE reactorPeerID = :peerId AND timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getRecentReactionTimestampsByAuthor(peerId: String, since: Long): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM feed_replies WHERE replyId = :replyId)")
    suspend fun replyExists(replyId: String): Boolean

    @Query("SELECT COUNT(*) FROM feed_replies WHERE parentPostId = :postId")
    suspend fun countReplies(postId: String): Int

    // --- Cleanup ---

    @Query("DELETE FROM feed_posts WHERE timestamp < :beforeTimestamp")
    suspend fun pruneOldPosts(beforeTimestamp: Long)

    @Query("DELETE FROM feed_reactions WHERE postId NOT IN (SELECT postId FROM feed_posts)")
    suspend fun pruneOrphanedReactions()

    @Query("DELETE FROM feed_replies WHERE parentPostId NOT IN (SELECT postId FROM feed_posts)")
    suspend fun pruneOrphanedReplies()
}
