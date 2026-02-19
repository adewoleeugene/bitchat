package com.bitchat.android.feed

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format for social feed messages over Bluetooth mesh.
 *
 * FEED_POST (0x40) payload:
 * - postId: length-prefixed (1 byte len + data)
 * - authorNickname: length-prefixed (1 byte len + data)
 * - timestamp: 8 bytes (big-endian Long)
 * - flags: 1 byte (bit 0 = hasImage)
 * - content: length-prefixed (2 byte len + data)
 * - [if hasImage] imageData: length-prefixed (4 byte len + data)
 *
 * FEED_REACTION (0x41) payload:
 * - postId: length-prefixed (1 byte len + data)
 * - reactorNickname: length-prefixed (1 byte len + data)
 * - timestamp: 8 bytes (big-endian Long)
 * - isRemoval: 1 byte (0=add, 1=remove)
 * - emoji: length-prefixed (1 byte len + data)
 *
 * FEED_REPLY (0x42) payload:
 * - replyId: length-prefixed (1 byte len + data)
 * - parentPostId: length-prefixed (1 byte len + data)
 * - authorNickname: length-prefixed (1 byte len + data)
 * - timestamp: 8 bytes (big-endian Long)
 * - content: length-prefixed (2 byte len + data)
 */

data class FeedPostPayload(
    val postId: String,
    val authorNickname: String,
    val timestamp: Long,
    val content: String,
    val imageData: ByteArray? = null
) {
    fun encode(): ByteArray {
        val postIdBytes = postId.toByteArray(Charsets.UTF_8)
        val nicknameBytes = authorNickname.toByteArray(Charsets.UTF_8)
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        val hasImage = imageData != null

        val size = 1 + postIdBytes.size.coerceAtMost(255) +
                1 + nicknameBytes.size.coerceAtMost(255) +
                8 + 1 +
                2 + contentBytes.size.coerceAtMost(65535) +
                if (hasImage) 4 + (imageData?.size ?: 0) else 0

        val buffer = ByteBuffer.allocate(size).apply { order(ByteOrder.BIG_ENDIAN) }

        // Post ID
        buffer.put(postIdBytes.size.coerceAtMost(255).toByte())
        buffer.put(postIdBytes.take(255).toByteArray())

        // Author nickname
        buffer.put(nicknameBytes.size.coerceAtMost(255).toByte())
        buffer.put(nicknameBytes.take(255).toByteArray())

        // Timestamp
        buffer.putLong(timestamp)

        // Flags
        var flags: Byte = 0
        if (hasImage) flags = (flags.toInt() or 0x01).toByte()
        buffer.put(flags)

        // Content
        buffer.putShort(contentBytes.size.coerceAtMost(65535).toShort())
        buffer.put(contentBytes.take(65535).toByteArray())

        // Image data (if present)
        if (hasImage && imageData != null) {
            buffer.putInt(imageData.size)
            buffer.put(imageData)
        }

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FeedPostPayload
        if (postId != other.postId) return false
        if (authorNickname != other.authorNickname) return false
        if (timestamp != other.timestamp) return false
        if (content != other.content) return false
        if (imageData != null) {
            if (other.imageData == null) return false
            if (!imageData.contentEquals(other.imageData)) return false
        } else if (other.imageData != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = postId.hashCode()
        result = 31 * result + authorNickname.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        fun decode(data: ByteArray): FeedPostPayload? {
            try {
                if (data.size < 13) return null
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                // Post ID
                val postIdLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < postIdLen) return null
                val postIdBytes = ByteArray(postIdLen)
                buffer.get(postIdBytes)
                val postId = String(postIdBytes, Charsets.UTF_8)

                // Author nickname
                if (buffer.remaining() < 1) return null
                val nicknameLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < nicknameLen) return null
                val nicknameBytes = ByteArray(nicknameLen)
                buffer.get(nicknameBytes)
                val authorNickname = String(nicknameBytes, Charsets.UTF_8)

                // Timestamp
                if (buffer.remaining() < 8) return null
                val timestamp = buffer.getLong()

                // Flags
                if (buffer.remaining() < 1) return null
                val flags = buffer.get().toInt() and 0xFF
                val hasImage = (flags and 0x01) != 0

                // Content
                if (buffer.remaining() < 2) return null
                val contentLen = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < contentLen) return null
                val contentBytes = ByteArray(contentLen)
                buffer.get(contentBytes)
                val content = String(contentBytes, Charsets.UTF_8)

                // Image data (optional)
                val imageData = if (hasImage) {
                    if (buffer.remaining() < 4) return null
                    val imageLen = buffer.getInt()
                    if (imageLen < 0 || buffer.remaining() < imageLen) return null
                    val imgBytes = ByteArray(imageLen)
                    buffer.get(imgBytes)
                    imgBytes
                } else null

                return FeedPostPayload(postId, authorNickname, timestamp, content, imageData)
            } catch (_: Exception) {
                return null
            }
        }
    }
}

data class FeedReactionPayload(
    val postId: String,
    val reactorNickname: String,
    val timestamp: Long,
    val emoji: String,
    val isRemoval: Boolean = false
) {
    fun encode(): ByteArray {
        val postIdBytes = postId.toByteArray(Charsets.UTF_8)
        val nicknameBytes = reactorNickname.toByteArray(Charsets.UTF_8)
        val emojiBytes = emoji.toByteArray(Charsets.UTF_8)

        val size = 1 + postIdBytes.size.coerceAtMost(255) +
                1 + nicknameBytes.size.coerceAtMost(255) +
                8 + 1 +
                1 + emojiBytes.size.coerceAtMost(255)

        val buffer = ByteBuffer.allocate(size).apply { order(ByteOrder.BIG_ENDIAN) }

        // Post ID
        buffer.put(postIdBytes.size.coerceAtMost(255).toByte())
        buffer.put(postIdBytes.take(255).toByteArray())

        // Reactor nickname
        buffer.put(nicknameBytes.size.coerceAtMost(255).toByte())
        buffer.put(nicknameBytes.take(255).toByteArray())

        // Timestamp
        buffer.putLong(timestamp)

        // Is removal
        buffer.put(if (isRemoval) 1.toByte() else 0.toByte())

        // Emoji
        buffer.put(emojiBytes.size.coerceAtMost(255).toByte())
        buffer.put(emojiBytes.take(255).toByteArray())

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    companion object {
        fun decode(data: ByteArray): FeedReactionPayload? {
            try {
                if (data.size < 12) return null
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                // Post ID
                val postIdLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < postIdLen) return null
                val postIdBytes = ByteArray(postIdLen)
                buffer.get(postIdBytes)
                val postId = String(postIdBytes, Charsets.UTF_8)

                // Reactor nickname
                if (buffer.remaining() < 1) return null
                val nicknameLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < nicknameLen) return null
                val nicknameBytes = ByteArray(nicknameLen)
                buffer.get(nicknameBytes)
                val reactorNickname = String(nicknameBytes, Charsets.UTF_8)

                // Timestamp
                if (buffer.remaining() < 8) return null
                val timestamp = buffer.getLong()

                // Is removal
                if (buffer.remaining() < 1) return null
                val isRemoval = buffer.get().toInt() != 0

                // Emoji
                if (buffer.remaining() < 1) return null
                val emojiLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < emojiLen) return null
                val emojiBytes = ByteArray(emojiLen)
                buffer.get(emojiBytes)
                val emoji = String(emojiBytes, Charsets.UTF_8)

                return FeedReactionPayload(postId, reactorNickname, timestamp, emoji, isRemoval)
            } catch (_: Exception) {
                return null
            }
        }
    }
}

data class FeedReplyPayload(
    val replyId: String,
    val parentPostId: String,
    val authorNickname: String,
    val timestamp: Long,
    val content: String
) {
    fun encode(): ByteArray {
        val replyIdBytes = replyId.toByteArray(Charsets.UTF_8)
        val parentIdBytes = parentPostId.toByteArray(Charsets.UTF_8)
        val nicknameBytes = authorNickname.toByteArray(Charsets.UTF_8)
        val contentBytes = content.toByteArray(Charsets.UTF_8)

        val size = 1 + replyIdBytes.size.coerceAtMost(255) +
                1 + parentIdBytes.size.coerceAtMost(255) +
                1 + nicknameBytes.size.coerceAtMost(255) +
                8 +
                2 + contentBytes.size.coerceAtMost(65535)

        val buffer = ByteBuffer.allocate(size).apply { order(ByteOrder.BIG_ENDIAN) }

        // Reply ID
        buffer.put(replyIdBytes.size.coerceAtMost(255).toByte())
        buffer.put(replyIdBytes.take(255).toByteArray())

        // Parent post ID
        buffer.put(parentIdBytes.size.coerceAtMost(255).toByte())
        buffer.put(parentIdBytes.take(255).toByteArray())

        // Author nickname
        buffer.put(nicknameBytes.size.coerceAtMost(255).toByte())
        buffer.put(nicknameBytes.take(255).toByteArray())

        // Timestamp
        buffer.putLong(timestamp)

        // Content
        buffer.putShort(contentBytes.size.coerceAtMost(65535).toShort())
        buffer.put(contentBytes.take(65535).toByteArray())

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    companion object {
        fun decode(data: ByteArray): FeedReplyPayload? {
            try {
                if (data.size < 14) return null
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                // Reply ID
                val replyIdLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < replyIdLen) return null
                val replyIdBytes = ByteArray(replyIdLen)
                buffer.get(replyIdBytes)
                val replyId = String(replyIdBytes, Charsets.UTF_8)

                // Parent post ID
                if (buffer.remaining() < 1) return null
                val parentIdLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < parentIdLen) return null
                val parentIdBytes = ByteArray(parentIdLen)
                buffer.get(parentIdBytes)
                val parentPostId = String(parentIdBytes, Charsets.UTF_8)

                // Author nickname
                if (buffer.remaining() < 1) return null
                val nicknameLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < nicknameLen) return null
                val nicknameBytes = ByteArray(nicknameLen)
                buffer.get(nicknameBytes)
                val authorNickname = String(nicknameBytes, Charsets.UTF_8)

                // Timestamp
                if (buffer.remaining() < 8) return null
                val timestamp = buffer.getLong()

                // Content
                if (buffer.remaining() < 2) return null
                val contentLen = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < contentLen) return null
                val contentBytes = ByteArray(contentLen)
                buffer.get(contentBytes)
                val content = String(contentBytes, Charsets.UTF_8)

                return FeedReplyPayload(replyId, parentPostId, authorNickname, timestamp, content)
            } catch (_: Exception) {
                return null
            }
        }
    }
}
