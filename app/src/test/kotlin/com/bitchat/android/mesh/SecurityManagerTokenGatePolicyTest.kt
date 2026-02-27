package com.bitchat.android.mesh

import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SecurityManagerTokenGatePolicyTest {
    private val encryptionService: EncryptionService = mock()
    private val delegate: SecurityManagerDelegate = mock()
    private lateinit var securityManager: SecurityManager

    @Before
    fun setUp() {
        securityManager = SecurityManager(encryptionService, myPeerID = "self-peer")
        securityManager.delegate = delegate
    }

    @Test
    fun validatePacket_staleTokenGatePolicy_isRejected() {
        val peerID = "peer-01"
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.TOKEN_GATE_POLICY.value,
            senderID = ByteArray(8) { 1 },
            recipientID = null,
            timestamp = (System.currentTimeMillis() - 6 * 60_000L).toULong(),
            payload = "stale-policy".toByteArray(),
            signature = ByteArray(64) { 9 },
            ttl = 7u
        )

        val accepted = securityManager.validatePacket(packet, peerID)

        assertFalse(accepted)
        verify(encryptionService, never()).verifyEd25519Signature(any(), any(), any())
    }

    @Test
    fun validatePacket_freshTokenGatePolicy_withValidSignature_isAccepted() {
        val peerID = "peer-02"
        val signingPublicKey = ByteArray(32) { 3 }
        val peerInfo = PeerInfo(
            id = peerID,
            nickname = "alice",
            isConnected = true,
            isDirectConnection = true,
            noisePublicKey = ByteArray(32) { 1 },
            signingPublicKey = signingPublicKey,
            isVerifiedNickname = true,
            lastSeen = System.currentTimeMillis()
        )
        whenever(delegate.getPeerInfo(peerID)).thenReturn(peerInfo)
        whenever(encryptionService.verifyEd25519Signature(any(), any(), any())).thenReturn(true)

        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.TOKEN_GATE_POLICY.value,
            senderID = ByteArray(8) { 2 },
            recipientID = null,
            timestamp = System.currentTimeMillis().toULong(),
            payload = "fresh-policy".toByteArray(),
            signature = ByteArray(64) { 7 },
            ttl = 7u
        )

        val accepted = securityManager.validatePacket(packet, peerID)

        assertTrue(accepted)
        verify(encryptionService, atLeastOnce()).verifyEd25519Signature(any(), any(), eq(signingPublicKey))
    }
}
