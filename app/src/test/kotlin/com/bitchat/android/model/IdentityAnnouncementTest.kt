package com.bitchat.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun nftProfileMint_roundTripPreservesValue() {
        val mintAddress = "DRpbCBMxVnDK7maPMoGcfEaS3oxNQH6Aog6Hy7KVD8qv"

        val announcement = IdentityAnnouncement(
            nickname = "bob",
            noisePublicKey = ByteArray(32) { 0xAA.toByte() },
            signingPublicKey = ByteArray(32) { 0xBB.toByte() },
            solanaAddress = "7xKXtg2TwvkuqCdEcxEzNj9fZ3",
            nftProfileMint = mintAddress
        )

        val encoded = announcement.encode()
        assertNotNull(encoded)

        val decoded = IdentityAnnouncement.decode(encoded!!)
        assertNotNull(decoded)
        assertEquals(mintAddress, decoded!!.nftProfileMint)
        assertEquals("bob", decoded.nickname)
        assertEquals("7xKXtg2TwvkuqCdEcxEzNj9fZ3", decoded.solanaAddress)
    }

    @Test
    fun nftProfileMint_nullWhenNotPresent() {
        val announcement = IdentityAnnouncement(
            nickname = "carol",
            noisePublicKey = ByteArray(32) { 0x11 },
            signingPublicKey = ByteArray(32) { 0x22 }
        )

        val encoded = announcement.encode()
        assertNotNull(encoded)

        val decoded = IdentityAnnouncement.decode(encoded!!)
        assertNotNull(decoded)
        assertNull(decoded!!.nftProfileMint)
        assertNull(decoded.solanaAddress)
        assertEquals("carol", decoded.nickname)
    }

    @Test
    fun backwardCompatibility_decodeWithoutTlv08() {
        // Manually build TLV bytes with only nickname + noiseKey + signingKey (no 0x08)
        val nicknameBytes = "dave".toByteArray(Charsets.UTF_8)
        val noiseKey = ByteArray(32) { 0x33 }
        val signingKey = ByteArray(32) { 0x44 }

        val data = mutableListOf<Byte>()
        // TLV 0x01 NICKNAME
        data.add(0x01)
        data.add(nicknameBytes.size.toByte())
        data.addAll(nicknameBytes.toList())
        // TLV 0x02 NOISE_PUBLIC_KEY
        data.add(0x02)
        data.add(noiseKey.size.toByte())
        data.addAll(noiseKey.toList())
        // TLV 0x03 SIGNING_PUBLIC_KEY
        data.add(0x03)
        data.add(signingKey.size.toByte())
        data.addAll(signingKey.toList())

        val decoded = IdentityAnnouncement.decode(data.toByteArray())
        assertNotNull(decoded)
        assertEquals("dave", decoded!!.nickname)
        assertNull(decoded.nftProfileMint)
        assertNull(decoded.solanaAddress)
    }

    @Test
    fun nftProfileMint_withAllOptionalFields() {
        val mintAddress = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        val linkProof = ByteArray(64) { (it + 5).toByte() }
        val proofs = listOf(
            SolanaOwnershipProof(
                claimType = SolanaOwnershipProof.ClaimType.NFT_MINT,
                targetAddress = mintAddress,
                minRequired = 1L,
                observedBalance = 1L,
                validatedAtMs = 1_735_000_000_000L,
                expiresAtMs = 1_735_000_060_000L,
                signature = ByteArray(64) { 0x55 }
            )
        )

        val announcement = IdentityAnnouncement(
            nickname = "eve",
            noisePublicKey = ByteArray(32) { 0xCC.toByte() },
            signingPublicKey = ByteArray(32) { 0xDD.toByte() },
            solanaAddress = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            solanaLinkProofSignature = linkProof,
            solanaOwnershipProofs = proofs,
            nftProfileMint = mintAddress
        )

        val encoded = announcement.encode()
        assertNotNull(encoded)

        val decoded = IdentityAnnouncement.decode(encoded!!)
        assertNotNull(decoded)
        assertEquals("eve", decoded!!.nickname)
        assertEquals("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", decoded.solanaAddress)
        assertEquals(mintAddress, decoded.nftProfileMint)
        assertEquals(1, decoded.solanaOwnershipProofs.size)
        assertNotNull(decoded.solanaLinkProofSignature)
        assertEquals(64, decoded.solanaLinkProofSignature!!.size)
    }

    @Test
    fun unknownTlvType_skippedGracefully() {
        // Build TLV with known fields + an unknown type 0x09
        val nicknameBytes = "frank".toByteArray(Charsets.UTF_8)
        val noiseKey = ByteArray(32) { 0x55 }
        val signingKey = ByteArray(32) { 0x66 }
        val mintBytes = "SomeNftMintAddress12345678901234567890ab".toByteArray(Charsets.UTF_8)

        val data = mutableListOf<Byte>()
        // Nickname
        data.add(0x01); data.add(nicknameBytes.size.toByte()); data.addAll(nicknameBytes.toList())
        // Noise key
        data.add(0x02); data.add(noiseKey.size.toByte()); data.addAll(noiseKey.toList())
        // Signing key
        data.add(0x03); data.add(signingKey.size.toByte()); data.addAll(signingKey.toList())
        // Unknown TLV 0x09 with 4 bytes of data
        data.add(0x09); data.add(4); data.addAll(listOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        // NFT profile mint (should still parse after unknown)
        data.add(0x08); data.add(mintBytes.size.toByte()); data.addAll(mintBytes.toList())

        val decoded = IdentityAnnouncement.decode(data.toByteArray())
        assertNotNull(decoded)
        assertEquals("frank", decoded!!.nickname)
        assertEquals("SomeNftMintAddress12345678901234567890ab", decoded.nftProfileMint)
    }
}
