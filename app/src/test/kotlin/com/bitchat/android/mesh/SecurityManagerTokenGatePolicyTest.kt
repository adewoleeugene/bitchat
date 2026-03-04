package com.bitchat.android.mesh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecurityManagerTokenGatePolicyTest {
    private lateinit var securityManager: SecurityManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val encryptionService = EncryptionService(context)
        securityManager = SecurityManager(encryptionService, myPeerID = "self-peer")
    }

    @Test
    fun validatePacket_staleTokenGatePolicy_isRejected() {
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

        val accepted = securityManager.validatePacket(packet, peerID = "peer-01")
        assertFalse(accepted)
    }

    @Test
    fun validatePacket_staleChannelRolePolicy_isRejected() {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.CHANNEL_ROLE_POLICY.value,
            senderID = ByteArray(8) { 2 },
            recipientID = null,
            timestamp = (System.currentTimeMillis() - 6 * 60_000L).toULong(),
            payload = "stale-role-policy".toByteArray(),
            signature = ByteArray(64) { 7 },
            ttl = 7u
        )

        val accepted = securityManager.validatePacket(packet, peerID = "peer-02")
        assertFalse(accepted)
    }

    @Test
    fun validatePacket_unsignedChannelRolePolicy_isRejected() {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.CHANNEL_ROLE_POLICY.value,
            senderID = ByteArray(8) { 3 },
            recipientID = null,
            timestamp = System.currentTimeMillis().toULong(),
            payload = "fresh-role-policy".toByteArray(),
            signature = null,
            ttl = 7u
        )

        val accepted = securityManager.validatePacket(packet, peerID = "peer-03")
        assertFalse(accepted)
    }
}
