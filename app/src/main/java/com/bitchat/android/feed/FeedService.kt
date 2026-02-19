package com.bitchat.android.feed

import android.content.Context
import android.util.Log
import com.bitchat.android.data.local.FeedDao
import com.bitchat.android.data.local.entities.FeedPostEntity
import com.bitchat.android.data.local.entities.FeedReactionEntity
import com.bitchat.android.data.local.entities.FeedReplyEntity
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedService @Inject constructor(
    private val feedDao: FeedDao
) {
    companion object {
        private const val TAG = "FeedService"
        private const val MAX_FEED_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
        private const val DEDUP_CACHE_SIZE = 500
    }

    // In-memory dedup cache (itemId -> timestamp)
    private val processedItems = ConcurrentHashMap<String, Long>()

    // Callbacks for broadcasting through the mesh
    var onBroadcastFeedPost: ((FeedPostPayload) -> Unit)? = null
    var onBroadcastFeedReaction: ((FeedReactionPayload) -> Unit)? = null
    var onBroadcastFeedReply: ((FeedReplyPayload) -> Unit)? = null

    // Callback for UI refresh
    var onFeedUpdated: (() -> Unit)? = null

    // --- Post creation (local user) ---

    suspend fun createPost(
        content: String,
        imageBytes: ByteArray?,
        myPeerID: String,
        myNickname: String,
        context: Context
    ): FeedPostEntity {
        val postId = UUID.randomUUID().toString().uppercase()
        val now = System.currentTimeMillis()

        val imagePath = if (imageBytes != null) {
            saveImageToFeedDir(context, postId, imageBytes)
        } else null

        val entity = FeedPostEntity(
            postId = postId,
            authorPeerID = myPeerID,
            authorNickname = myNickname,
            content = content,
            hasImage = imageBytes != null,
            imagePath = imagePath,
            timestamp = now,
            receivedAt = now,
            isOwnPost = true
        )
        feedDao.insertPost(entity)
        markProcessed(postId)

        val payload = FeedPostPayload(postId, myNickname, now, content, imageBytes)
        onBroadcastFeedPost?.invoke(payload)

        return entity
    }

    // --- Handle incoming post from mesh ---

    suspend fun handleIncomingPost(
        payload: FeedPostPayload,
        senderPeerID: String,
        context: Context
    ) {
        if (isDuplicate(payload.postId)) return

        val imagePath = if (payload.imageData != null) {
            saveImageToFeedDir(context, payload.postId, payload.imageData)
        } else null

        val entity = FeedPostEntity(
            postId = payload.postId,
            authorPeerID = senderPeerID,
            authorNickname = payload.authorNickname,
            content = payload.content,
            hasImage = payload.imageData != null,
            imagePath = imagePath,
            timestamp = payload.timestamp,
            receivedAt = System.currentTimeMillis(),
            isOwnPost = false
        )
        feedDao.insertPost(entity)
        markProcessed(payload.postId)
        onFeedUpdated?.invoke()
    }

    // --- Reactions ---

    suspend fun toggleReaction(postId: String, emoji: String, myPeerID: String, myNickname: String) {
        val now = System.currentTimeMillis()
        val exists = feedDao.hasReaction(postId, myPeerID, emoji)
        val isRemoval = exists

        if (isRemoval) {
            feedDao.removeReaction(postId, myPeerID, emoji)
        } else {
            feedDao.insertReaction(
                FeedReactionEntity(postId, myPeerID, myNickname, emoji, now, now)
            )
        }
        updateCachedReactionCount(postId)

        val payload = FeedReactionPayload(postId, myNickname, now, emoji, isRemoval)
        onBroadcastFeedReaction?.invoke(payload)
    }

    suspend fun handleIncomingReaction(payload: FeedReactionPayload, senderPeerID: String) {
        val dedupKey = "reaction:${payload.postId}:${senderPeerID}:${payload.emoji}:${payload.timestamp}"
        if (isDuplicate(dedupKey)) return

        if (payload.isRemoval) {
            feedDao.removeReaction(payload.postId, senderPeerID, payload.emoji)
        } else {
            feedDao.insertReaction(
                FeedReactionEntity(
                    payload.postId, senderPeerID, payload.reactorNickname,
                    payload.emoji, payload.timestamp, System.currentTimeMillis()
                )
            )
        }
        markProcessed(dedupKey)
        updateCachedReactionCount(payload.postId)
        onFeedUpdated?.invoke()
    }

    // --- Replies ---

    suspend fun createReply(
        parentPostId: String,
        content: String,
        myPeerID: String,
        myNickname: String
    ): FeedReplyEntity {
        val replyId = UUID.randomUUID().toString().uppercase()
        val now = System.currentTimeMillis()

        val entity = FeedReplyEntity(replyId, parentPostId, myPeerID, myNickname, content, now, now)
        feedDao.insertReply(entity)
        updateCachedReplyCount(parentPostId)

        val payload = FeedReplyPayload(replyId, parentPostId, myNickname, now, content)
        onBroadcastFeedReply?.invoke(payload)

        return entity
    }

    suspend fun handleIncomingReply(payload: FeedReplyPayload, senderPeerID: String) {
        if (isDuplicate(payload.replyId)) return

        feedDao.insertReply(
            FeedReplyEntity(
                payload.replyId, payload.parentPostId, senderPeerID,
                payload.authorNickname, payload.content, payload.timestamp,
                System.currentTimeMillis()
            )
        )
        markProcessed(payload.replyId)
        updateCachedReplyCount(payload.parentPostId)
        onFeedUpdated?.invoke()
    }

    // --- Observe ---

    fun observePosts(): Flow<List<FeedPostEntity>> = feedDao.observeAllPosts()

    fun observeReactions(postId: String): Flow<List<FeedReactionEntity>> =
        feedDao.observeReactionsForPost(postId)

    fun observeReplies(postId: String): Flow<List<FeedReplyEntity>> =
        feedDao.observeRepliesForPost(postId)

    // --- Cleanup ---

    suspend fun pruneOldData() {
        val cutoff = System.currentTimeMillis() - MAX_FEED_AGE_MS
        feedDao.pruneOldPosts(cutoff)
        feedDao.pruneOrphanedReactions()
        feedDao.pruneOrphanedReplies()
    }

    // --- Helpers ---

    private fun isDuplicate(itemId: String): Boolean = processedItems.containsKey(itemId)

    private fun markProcessed(itemId: String) {
        processedItems[itemId] = System.currentTimeMillis()
        if (processedItems.size > DEDUP_CACHE_SIZE) {
            val cutoff = System.currentTimeMillis() - 3600_000L
            processedItems.entries.removeAll { it.value < cutoff }
        }
    }

    private suspend fun updateCachedReactionCount(postId: String) {
        try {
            val count = feedDao.countReactions(postId)
            feedDao.updateReactionCount(postId, count)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update reaction count for $postId: ${e.message}")
        }
    }

    private suspend fun updateCachedReplyCount(postId: String) {
        try {
            val count = feedDao.countReplies(postId)
            feedDao.updateReplyCount(postId, count)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update reply count for $postId: ${e.message}")
        }
    }

    private fun saveImageToFeedDir(context: Context, postId: String, imageBytes: ByteArray): String? {
        return try {
            val dir = File(context.filesDir, "images/feed")
            dir.mkdirs()
            val file = File(dir, "feed_${postId}.jpg")
            file.writeBytes(imageBytes)
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save feed image: ${e.message}")
            null
        }
    }
}
