package com.bitchat.android.mesh

import android.app.Application
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.model.SolanaOwnershipProof
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.solana.SolanaIdentityProofUtil
import com.bitchat.android.solana.SolanaKeyDerivation
import com.bitchat.android.solana.SolanaOwnershipProofUtil
import kotlinx.coroutines.runBlocking
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageHandlerOwnershipProofTest {

    @Test
    fun handleAnnounce_capsVerifiedOwnershipProofsAtTwelve() = runBlocking {
        val signingPrivateKey = ByteArray(32) { (it + 40).toByte() }
        val signingPublicKey = SolanaKeyDerivation.derivePublicKey(signingPrivateKey)
        val walletPrivateKey = ByteArray(32) { (it + 70).toByte() }
        val walletPublicKey = SolanaKeyDerivation.derivePublicKey(walletPrivateKey)
        val walletAddress = SolanaKeyDerivation.encodeBase58(walletPublicKey)

        val nickname = "alice"
        val now = System.currentTimeMillis()
        val linkProof = sign(
            privateKey = walletPrivateKey,
            data = SolanaIdentityProofUtil.buildLinkMessage(
                nickname = nickname,
                solanaAddress = walletAddress,
                signingPublicKey = signingPublicKey
            )
        )
        val ownershipProofs = (0 until 15).map { idx ->
            buildSignedOwnershipProof(
                nickname = nickname,
                solanaAddress = walletAddress,
                signingPublicKey = signingPublicKey,
                walletPrivateKey = walletPrivateKey,
                claimType = SolanaOwnershipProof.ClaimType.SPL_TOKEN,
                targetAddress = "Mint$idx",
                minRequired = 1,
                observedBalance = 1L + idx.toLong(),
                validatedAtMs = now,
                expiresAtMs = now + 60_000L
            )
        }

        val announcement = IdentityAnnouncement(
            nickname = nickname,
            noisePublicKey = ByteArray(32) { (it + 2).toByte() },
            signingPublicKey = signingPublicKey,
            solanaAddress = walletAddress,
            solanaLinkProofSignature = linkProof,
            solanaOwnershipProofs = ownershipProofs
        )

        val announcePacket = BitchatPacket(
            version = 1u,
            type = MessageType.ANNOUNCE.value,
            senderID = ByteArray(8) { (it + 1).toByte() },
            recipientID = null,
            timestamp = System.currentTimeMillis().toULong(),
            payload = announcement.encode()!!,
            signature = null,
            ttl = 7u
        )
        announcePacket.signature = sign(signingPrivateKey, announcePacket.toBinaryDataForSigning()!!)

        val delegate = CapturingDelegate()
        val handler = MessageHandler(
            myPeerID = "self-peer",
            appContext = Application()
        )
        handler.delegate = delegate

        val isFirst = handler.handleAnnounce(
            RoutedPacket(packet = announcePacket, peerID = "peer-01")
        )

        assertTrue(isFirst)
        assertEquals(12, delegate.lastSolanaOwnershipProofs.size)
        assertEquals(walletAddress, delegate.lastSolanaAddress)
        assertFalse(delegate.lastSolanaOwnershipProofs.any { it.expiresAtMs <= now })
    }

    private fun buildSignedOwnershipProof(
        nickname: String,
        solanaAddress: String,
        signingPublicKey: ByteArray,
        walletPrivateKey: ByteArray,
        claimType: SolanaOwnershipProof.ClaimType,
        targetAddress: String,
        minRequired: Long,
        observedBalance: Long,
        validatedAtMs: Long,
        expiresAtMs: Long
    ): SolanaOwnershipProof {
        val unsigned = SolanaOwnershipProof(
            claimType = claimType,
            targetAddress = targetAddress,
            minRequired = minRequired,
            observedBalance = observedBalance,
            validatedAtMs = validatedAtMs,
            expiresAtMs = expiresAtMs,
            signature = ByteArray(64)
        )
        val signature = sign(
            privateKey = walletPrivateKey,
            data = SolanaOwnershipProofUtil.buildProofMessage(
                nickname = nickname,
                solanaAddress = solanaAddress,
                signingPublicKey = signingPublicKey,
                proof = unsigned
            )
        )
        return unsigned.copy(signature = signature)
    }

    private fun sign(privateKey: ByteArray, data: ByteArray): ByteArray {
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val key = EdDSAPrivateKey(EdDSAPrivateKeySpec(privateKey, spec))
        val signer = EdDSAEngine()
        signer.initSign(key)
        signer.update(data)
        return signer.sign()
    }

    private fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
            val key = EdDSAPublicKey(EdDSAPublicKeySpec(publicKey, spec))
            val verifier = EdDSAEngine()
            verifier.initVerify(key)
            verifier.update(data)
            verifier.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    private inner class CapturingDelegate : MessageHandlerDelegate {
        var lastSolanaAddress: String? = null
        var lastSolanaOwnershipProofs: List<SolanaOwnershipProof> = emptyList()

        override fun addOrUpdatePeer(peerID: String, nickname: String): Boolean = false
        override fun removePeer(peerID: String) = Unit
        override fun updatePeerNickname(peerID: String, nickname: String) = Unit
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize(): Int = 0
        override fun getMyNickname(): String? = null
        override fun getPeerInfo(peerID: String): PeerInfo? = null

        override fun updatePeerInfo(
            peerID: String,
            nickname: String,
            noisePublicKey: ByteArray,
            signingPublicKey: ByteArray,
            isVerified: Boolean,
            solanaAddress: String?,
            solanaOwnershipProofs: List<SolanaOwnershipProof>
        ): Boolean {
            lastSolanaAddress = solanaAddress
            lastSolanaOwnershipProofs = solanaOwnershipProofs
            return true
        }

        override fun sendPacket(packet: BitchatPacket) = Unit
        override fun relayPacket(routed: RoutedPacket) = Unit
        override fun getBroadcastRecipient(): ByteArray = ByteArray(8) { 0xFF.toByte() }
        override fun verifySignature(packet: BitchatPacket, peerID: String): Boolean = true
        override fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? = null
        override fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? = null

        override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
            return verify(publicKey = publicKey, data = data, signature = signature)
        }

        override fun hasNoiseSession(peerID: String): Boolean = false
        override fun initiateNoiseHandshake(peerID: String) = Unit
        override fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray? = null
        override fun updatePeerIDBinding(
            newPeerID: String,
            nickname: String,
            publicKey: ByteArray,
            previousPeerID: String?
        ) = Unit

        override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
        override fun onMessageReceived(message: BitchatMessage) = Unit
        override fun onChannelLeave(channel: String, fromPeer: String) = Unit
        override fun onDeliveryAckReceived(messageID: String, peerID: String) = Unit
        override fun onReadReceiptReceived(messageID: String, peerID: String) = Unit
    }
}
