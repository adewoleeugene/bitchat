package com.bitchat.android.feed

import android.content.Context
import android.util.Log
import com.bitchat.android.data.local.FeedDao
import com.bitchat.android.lending.LendingTelemetryStore
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
    private val feedDao: FeedDao,
    private val lendingTelemetryStore: LendingTelemetryStore
) {
    data class FeedReplyNotificationEvent(
        val parentPostId: String,
        val parentPostChannelKey: String,
        val parentPostAuthorPeerID: String,
        val senderPeerID: String,
        val senderNickname: String,
        val content: String,
        val timestamp: Long,
        val priorCommenterPeerIDs: Set<String>
    )

    data class FeedSyncSnapshot(
        val posts: List<FeedPostPayload>,
        val replies: List<FeedReplyPayload>,
        val reactions: List<FeedReactionPayload>,
        val pins: List<FeedPinPayload>
    )

    private data class PinApplyResult(
        val changed: Boolean,
        val evictedPinVersions: Map<String, Long> = emptyMap()
    )

    companion object {
        private const val TAG = "FeedService"
        private const val MAX_FEED_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
        private const val DEDUP_CACHE_SIZE = 500
        private const val MAX_PINNED_POSTS = 4
        private const val SYNC_MEDIA_MAX_BYTES = 8 * 1024 * 1024 // 8 MB per media blob in sync
        const val FEED_AUDIO_FILE_PREFIX = "__feed_audio__"
        const val DEFAULT_MESH_FEED_CHANNEL_KEY = "mesh:#mesh"

        fun normalizeChannelKey(channelKey: String?): String {
            return if (channelKey.isNullOrBlank()) DEFAULT_MESH_FEED_CHANNEL_KEY else channelKey
        }

        fun encodeFeedAudioFileName(postId: String): String = "${FEED_AUDIO_FILE_PREFIX}${postId}.m4a"

        fun decodeFeedAudioPostId(fileName: String): String? {
            if (!fileName.startsWith(FEED_AUDIO_FILE_PREFIX)) return null
            return fileName.removePrefix(FEED_AUDIO_FILE_PREFIX).substringBeforeLast(".").takeIf { it.isNotBlank() }
        }
    }

    // In-memory dedup cache (itemId -> timestamp)
    private val processedItems = ConcurrentHashMap<String, Long>()
    private val pendingAudioAttachments = ConcurrentHashMap<String, ByteArray>()

    // Callbacks for broadcasting through the mesh
    var onBroadcastFeedPost: ((FeedPostPayload) -> Unit)? = null
    var onBroadcastFeedReaction: ((FeedReactionPayload) -> Unit)? = null
    var onBroadcastFeedReply: ((FeedReplyPayload) -> Unit)? = null
    var onBroadcastFeedPin: ((FeedPinPayload) -> Unit)? = null

    // Callback for UI refresh
    var onFeedUpdated: (() -> Unit)? = null
    var onFeedReplyNotification: ((FeedReplyNotificationEvent) -> Unit)? = null

    // --- Post creation (local user) ---

    suspend fun createPost(
        content: String,
        imageBytes: ByteArray?,
        audioBytes: ByteArray?,
        localAudioPath: String?,
        myPeerID: String,
        myNickname: String,
        channelKey: String,
        context: Context
    ): FeedPostEntity {
        val postId = UUID.randomUUID().toString().uppercase()
        val now = System.currentTimeMillis()
        val normalizedChannelKey = normalizeChannelKey(channelKey)
        val resolvedAudioBytes = audioBytes ?: localAudioPath?.let { path ->
            runCatching { File(path).readBytes() }.getOrNull()
        }

        val imagePath = if (imageBytes != null) {
            saveImageToFeedDir(context, postId, imageBytes)
        } else null
        // Always persist a canonical feed-audio copy so sync/replay can reliably include audio
        // even when the temporary recording path is unavailable later.
        val audioPath = if (resolvedAudioBytes != null) {
            saveAudioToFeedDir(context, postId, resolvedAudioBytes)
        } else {
            localAudioPath
        }

        val entity = FeedPostEntity(
            postId = postId,
            channelKey = normalizedChannelKey,
            authorPeerID = myPeerID,
            authorNickname = myNickname,
            content = content,
            hasImage = imageBytes != null,
            imagePath = imagePath,
            hasAudio = resolvedAudioBytes != null,
            audioPath = audioPath,
            timestamp = now,
            receivedAt = now,
            isOwnPost = true
        )
        feedDao.insertPost(entity)
        markProcessed(postId)
        lendingTelemetryStore.recordFeedPost(myPeerID, now)

        val payload = FeedPostPayload(
            postId = postId,
            authorNickname = myNickname,
            timestamp = now,
            content = content,
            imageData = imageBytes,
            audioData = resolvedAudioBytes,
            channelKey = normalizedChannelKey
        )
        onBroadcastFeedPost?.invoke(payload)

        return entity
    }

    // --- Handle incoming post from mesh ---

    suspend fun handleIncomingPost(
        payload: FeedPostPayload,
        senderPeerID: String,
        context: Context
    ) {
        val existing = feedDao.getPostById(payload.postId)
        if (existing != null) {
            // Allow richer payloads (e.g. later real-time post with audio) to fill media
            // when an earlier sync snapshot inserted a lightweight record first.
            val shouldAddImage = payload.imageData != null && (!existing.hasImage || existing.imagePath.isNullOrBlank())
            val shouldAddAudio = payload.audioData != null && (!existing.hasAudio || existing.audioPath.isNullOrBlank())
            if (shouldAddImage || shouldAddAudio) {
                val imagePath = if (shouldAddImage) saveImageToFeedDir(context, payload.postId, payload.imageData!!) else null
                val audioPath = if (shouldAddAudio) saveAudioToFeedDir(context, payload.postId, payload.audioData!!) else null
                val updated = feedDao.mergePostMedia(
                    postId = payload.postId,
                    hasImage = shouldAddImage,
                    imagePath = imagePath,
                    hasAudio = shouldAddAudio,
                    audioPath = audioPath
                )
                if (updated > 0) {
                    onFeedUpdated?.invoke()
                }
            }
            markProcessed(payload.postId)
            applyPendingAudioAttachmentIfAny(payload.postId, senderPeerID, context)
            return
        }

        if (isDuplicate(payload.postId)) return

        val imagePath = if (payload.imageData != null) {
            saveImageToFeedDir(context, payload.postId, payload.imageData)
        } else null
        val audioPath = if (payload.audioData != null) {
            saveAudioToFeedDir(context, payload.postId, payload.audioData)
        } else null

        val entity = FeedPostEntity(
            postId = payload.postId,
            channelKey = normalizeChannelKey(payload.channelKey),
            authorPeerID = senderPeerID,
            authorNickname = payload.authorNickname,
            content = payload.content,
            hasImage = payload.imageData != null,
            imagePath = imagePath,
            hasAudio = payload.audioData != null,
            audioPath = audioPath,
            timestamp = payload.timestamp,
            receivedAt = System.currentTimeMillis(),
            isOwnPost = false
        )
        feedDao.insertPost(entity)
        markProcessed(payload.postId)
        lendingTelemetryStore.recordFeedPost(senderPeerID, payload.timestamp)
        applyPendingAudioAttachmentIfAny(payload.postId, senderPeerID, context)
        onFeedUpdated?.invoke()
    }

    suspend fun handleIncomingPostAudioAttachment(
        postId: String,
        senderPeerID: String,
        audioBytes: ByteArray,
        context: Context
    ) {
        val dedupKey = "feed_audio:$postId:${audioBytes.contentHashCode()}"
        if (isDuplicate(dedupKey)) return

        val post = feedDao.getPostById(postId)
        if (post == null) {
            // Post may arrive later; keep attachment in memory for a short window.
            pendingAudioAttachments[postId] = audioBytes
            markProcessed(dedupKey)
            return
        }

        if (post.hasAudio && !post.audioPath.isNullOrBlank()) {
            markProcessed(dedupKey)
            return
        }

        val audioPath = saveAudioToFeedDir(context, postId, audioBytes)
        if (!audioPath.isNullOrBlank()) {
            feedDao.mergePostMedia(
                postId = postId,
                hasImage = false,
                imagePath = null,
                hasAudio = true,
                audioPath = audioPath
            )
            onFeedUpdated?.invoke()
        }
        markProcessed(dedupKey)
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
            lendingTelemetryStore.recordFeedReaction(myPeerID, now)
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
            lendingTelemetryStore.recordFeedReaction(senderPeerID, payload.timestamp)
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
        lendingTelemetryStore.recordFeedReply(myPeerID, now)
        updateCachedReplyCount(parentPostId)

        val payload = FeedReplyPayload(replyId, parentPostId, myNickname, now, content)
        onBroadcastFeedReply?.invoke(payload)

        return entity
    }

    suspend fun handleIncomingReply(payload: FeedReplyPayload, senderPeerID: String) {
        if (isDuplicate(payload.replyId)) return
        val parentPost = feedDao.getPostById(payload.parentPostId)
        val priorCommenters = if (parentPost != null) {
            feedDao.getRepliesForPost(payload.parentPostId)
                .map { it.authorPeerID }
                .filter { it != senderPeerID }
                .toSet()
        } else {
            emptySet()
        }

        feedDao.insertReply(
            FeedReplyEntity(
                payload.replyId, payload.parentPostId, senderPeerID,
                payload.authorNickname, payload.content, payload.timestamp,
                System.currentTimeMillis()
            )
        )
        lendingTelemetryStore.recordFeedReply(senderPeerID, payload.timestamp)
        markProcessed(payload.replyId)
        updateCachedReplyCount(payload.parentPostId)
        onFeedUpdated?.invoke()
        if (parentPost != null) {
            onFeedReplyNotification?.invoke(
                FeedReplyNotificationEvent(
                    parentPostId = payload.parentPostId,
                    parentPostChannelKey = parentPost.channelKey,
                    parentPostAuthorPeerID = parentPost.authorPeerID,
                    senderPeerID = senderPeerID,
                    senderNickname = payload.authorNickname,
                    content = payload.content,
                    timestamp = payload.timestamp,
                    priorCommenterPeerIDs = priorCommenters
                )
            )
        }
    }

    // --- Pinning ---

    suspend fun setPostPinned(
        postId: String,
        isPinned: Boolean,
        myPeerID: String,
        myNickname: String,
        canManageAnyPost: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val pinVersion = now
        val post = feedDao.getPostById(postId) ?: return
        if (!canManageAnyPost && !post.isOwnPost && post.authorPeerID != myPeerID) return

        val applyResult = applyPinStateAndEnforceLimit(
            postId = postId,
            isPinned = isPinned,
            actorPeerID = myPeerID,
            pinVersion = pinVersion,
            timestamp = now
        )
        if (!applyResult.changed) return

        onBroadcastFeedPin?.invoke(
            FeedPinPayload(
                postId = postId,
                actorNickname = myNickname,
                timestamp = now,
                pinVersion = pinVersion,
                isPinned = isPinned
            )
        )
        applyResult.evictedPinVersions.forEach { (evictedPostId, evictedVersion) ->
            onBroadcastFeedPin?.invoke(
                FeedPinPayload(
                    postId = evictedPostId,
                    actorNickname = myNickname,
                    timestamp = now,
                    pinVersion = evictedVersion,
                    isPinned = false
                )
            )
        }
        onFeedUpdated?.invoke()
    }

    suspend fun handleIncomingPin(payload: FeedPinPayload, senderPeerID: String) {
        val dedupKey = "pin:${payload.postId}:${senderPeerID}:${payload.pinVersion}:${payload.isPinned}"
        if (isDuplicate(dedupKey)) return

        val post = feedDao.getPostById(payload.postId) ?: return
        if (payload.pinVersion <= post.pinVersion) return

        applyPinStateAndEnforceLimit(
            postId = payload.postId,
            isPinned = payload.isPinned,
            actorPeerID = senderPeerID,
            pinVersion = payload.pinVersion,
            timestamp = payload.timestamp
        )
        markProcessed(dedupKey)
        onFeedUpdated?.invoke()
    }

    // --- Observe ---

    fun observePosts(channelKey: String): Flow<List<FeedPostEntity>> =
        feedDao.observePostsByChannel(normalizeChannelKey(channelKey))

    fun observeReactions(postId: String): Flow<List<FeedReactionEntity>> =
        feedDao.observeReactionsForPost(postId)

    fun observeReplies(postId: String): Flow<List<FeedReplyEntity>> =
        feedDao.observeRepliesForPost(postId)

    suspend fun getSyncSnapshot(
        postLimit: Int = 80,
        replyLimit: Int = 200,
        reactionLimit: Int = 200
    ): FeedSyncSnapshot {
        val posts = feedDao.getRecentPosts(postLimit)
        val replies = feedDao.getRecentReplies(replyLimit)
        val reactions = feedDao.getRecentReactions(reactionLimit)

        val postPayloads = posts.map { post ->
            val imageBytes = post.imagePath
                ?.takeIf { post.hasImage }
                ?.let { path ->
                    runCatching {
                        val f = File(path)
                        if (f.exists() && f.length() in 1..SYNC_MEDIA_MAX_BYTES) f.readBytes() else null
                    }.getOrNull()
                }
            val audioBytes = post.audioPath
                ?.takeIf { post.hasAudio }
                ?.let { path ->
                    runCatching {
                        val f = File(path)
                        if (f.exists() && f.length() in 1..SYNC_MEDIA_MAX_BYTES) f.readBytes() else null
                    }.getOrNull()
                }
            FeedPostPayload(
                postId = post.postId,
                authorNickname = post.authorNickname,
                timestamp = post.timestamp,
                content = post.content,
                imageData = imageBytes,
                audioData = audioBytes,
                channelKey = post.channelKey
            )
        }

        val replyPayloads = replies.map { reply ->
            FeedReplyPayload(
                replyId = reply.replyId,
                parentPostId = reply.parentPostId,
                authorNickname = reply.authorNickname,
                timestamp = reply.timestamp,
                content = reply.content
            )
        }

        val reactionPayloads = reactions.map { reaction ->
            FeedReactionPayload(
                postId = reaction.postId,
                reactorNickname = reaction.reactorNickname,
                timestamp = reaction.timestamp,
                emoji = reaction.emoji,
                isRemoval = false
            )
        }

        val pinPayloads = posts.filter { it.pinVersion > 0L }.map { post ->
            FeedPinPayload(
                postId = post.postId,
                actorNickname = "sync",
                timestamp = post.pinnedAt ?: post.timestamp,
                pinVersion = post.pinVersion,
                isPinned = post.isPinned
            )
        }

        return FeedSyncSnapshot(
            posts = postPayloads,
            replies = replyPayloads,
            reactions = reactionPayloads,
            pins = pinPayloads
        )
    }

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

    private suspend fun applyPinStateAndEnforceLimit(
        postId: String,
        isPinned: Boolean,
        actorPeerID: String,
        pinVersion: Long,
        timestamp: Long
    ): PinApplyResult {
        val post = feedDao.getPostById(postId) ?: return PinApplyResult(changed = false)
        val updatedRows = feedDao.updatePinState(
            postId = postId,
            isPinned = isPinned,
            pinnedAt = if (isPinned) timestamp else null,
            pinnedByPeerID = if (isPinned) actorPeerID else null,
            pinVersion = pinVersion
        )
        if (updatedRows <= 0) return PinApplyResult(changed = false)

        val evictedPinVersions = mutableMapOf<String, Long>()
        if (isPinned) {
            while (feedDao.getPinnedCount(post.channelKey) > MAX_PINNED_POSTS) {
                val oldestPinned = feedDao.getOldestPinnedPostId(post.channelKey) ?: break
                if (oldestPinned == postId) break
                val overflowVersion = System.currentTimeMillis()
                feedDao.updatePinState(
                    postId = oldestPinned,
                    isPinned = false,
                    pinnedAt = null,
                    pinnedByPeerID = null,
                    pinVersion = overflowVersion
                )
                evictedPinVersions[oldestPinned] = overflowVersion
            }
        }
        return PinApplyResult(changed = true, evictedPinVersions = evictedPinVersions)
    }

    private suspend fun applyPendingAudioAttachmentIfAny(
        postId: String,
        senderPeerID: String,
        context: Context
    ) {
        val pendingBytes = pendingAudioAttachments.remove(postId) ?: return
        handleIncomingPostAudioAttachment(postId, senderPeerID, pendingBytes, context)
    }

    suspend fun getPostById(postId: String): FeedPostEntity? = feedDao.getPostById(postId)

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

    private fun saveAudioToFeedDir(context: Context, postId: String, audioBytes: ByteArray): String? {
        return try {
            val dir = File(context.filesDir, "audio/feed")
            dir.mkdirs()
            val file = File(dir, "feed_${postId}.m4a")
            file.writeBytes(audioBytes)
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save feed audio: ${e.message}")
            null
        }
    }
}
