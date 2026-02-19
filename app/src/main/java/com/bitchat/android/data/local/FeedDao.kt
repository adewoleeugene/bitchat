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

    // --- Reactions ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReaction(reaction: FeedReactionEntity)

    @Query("DELETE FROM feed_reactions WHERE postId = :postId AND reactorPeerID = :reactorPeerID AND emoji = :emoji")
    suspend fun removeReaction(postId: String, reactorPeerID: String, emoji: String)

    @Query("SELECT * FROM feed_reactions WHERE postId = :postId")
    fun observeReactionsForPost(postId: String): Flow<List<FeedReactionEntity>>

    @Query("SELECT * FROM feed_reactions WHERE postId = :postId")
    suspend fun getReactionsForPost(postId: String): List<FeedReactionEntity>

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
