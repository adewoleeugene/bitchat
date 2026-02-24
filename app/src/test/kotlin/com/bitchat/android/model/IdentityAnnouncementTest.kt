package com.bitchat.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IdentityAnnouncementTest {

    @Test
    fun encode_capsOwnershipProofsAtTwelve() {
        val proofs = (0 until 15).map { idx ->
            SolanaOwnershipProof(
                claimType = SolanaOwnershipProof.ClaimType.SPL_TOKEN,
                targetAddress = "Mint$idx",
                minRequired = 1L,
                observedBalance = 10L + idx,
                validatedAtMs = 1_735_000_000_000L,
                expiresAtMs = 1_735_000_060_000L,
                signature = ByteArray(64) { idx.toByte() }
            )
        }

        val announcement = IdentityAnnouncement(
            nickname = "alice",
            noisePublicKey = ByteArray(32) { 1 },
            signingPublicKey = ByteArray(32) { 2 },
            solanaAddress = "7xKXtg2TwvkuqCdEcxEzNj9fZ3",
            solanaOwnershipProofs = proofs
        )

        val encoded = announcement.encode()
        assertNotNull(encoded)

        val decoded = IdentityAnnouncement.decode(encoded!!)
        assertNotNull(decoded)
        assertEquals(12, decoded!!.solanaOwnershipProofs.size)
        assertEquals("Mint0", decoded.solanaOwnershipProofs.first().targetAddress)
        assertEquals("Mint11", decoded.solanaOwnershipProofs.last().targetAddress)
    }
}
