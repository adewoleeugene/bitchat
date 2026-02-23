package com.bitchat

import com.bitchat.android.solana.RelayAckType
import com.bitchat.android.solana.SolanaRelayAck
import com.bitchat.android.solana.SolanaRelayClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SolanaRelayPayloadTest {

    @Test
    fun relayClaimRoundTripPreservesFields() {
        val input = SolanaRelayClaim(
            requestId = "a2f6d6e0-1d29-47d0-b41e-d8f4a95f33b1",
            relayPeerId = "beefcafe12345678",
            claimExpiresAtMs = 1_735_689_123_456L
        )

        val decoded = SolanaRelayClaim.decode(input.encode())
        assertNotNull(decoded)
        assertEquals(input, decoded)
    }

    @Test
    fun relayAckRoundTripPreservesFields() {
        val input = SolanaRelayAck(
            requestId = "c4ee7756-d5f7-4f1f-9d57-1f4a1f6c7410",
            ackType = RelayAckType.REQUEST_SEEN,
            peerId = "0011223344556677",
            timestampMs = 1_735_689_123_999L
        )

        val decoded = SolanaRelayAck.decode(input.encode())
        assertNotNull(decoded)
        assertEquals(input, decoded)
    }
}
