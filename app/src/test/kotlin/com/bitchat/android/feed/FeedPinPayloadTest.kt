package com.bitchat.android.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPinPayloadTest {

    @Test
    fun encodeDecode_roundTripsPinnedPayload() {
        val payload = FeedPinPayload(
            postId = "POST_123",
            actorNickname = "alice",
            timestamp = 1_700_000_000_000L,
            pinVersion = 1_700_000_000_001L,
            isPinned = true
        )

        val decoded = FeedPinPayload.decode(payload.encode())
        assertNotNull(decoded)
        decoded ?: return

        assertEquals(payload.postId, decoded.postId)
        assertEquals(payload.actorNickname, decoded.actorNickname)
        assertEquals(payload.timestamp, decoded.timestamp)
        assertEquals(payload.pinVersion, decoded.pinVersion)
        assertTrue(decoded.isPinned)
    }

    @Test
    fun decode_rejectsTruncatedPayload() {
        val payload = FeedPinPayload(
            postId = "POST_123",
            actorNickname = "bob",
            timestamp = 1234L,
            pinVersion = 1235L,
            isPinned = false
        )
        val encoded = payload.encode()
        val truncated = encoded.copyOf(encoded.size - 1)

        val decoded = FeedPinPayload.decode(truncated)
        assertFalse(decoded != null)
    }
}
