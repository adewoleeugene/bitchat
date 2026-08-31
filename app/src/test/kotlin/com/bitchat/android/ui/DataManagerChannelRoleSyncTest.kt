package com.bitchat.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class DataManagerChannelRoleSyncTest {
    private lateinit var dataManager: DataManager
    private val channel = "mesh:#ops"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        dataManager = DataManager(context)
    }

    @Test
    fun applySyncedRolePolicy_rejectsStaleAndDuplicateVersions() {
        dataManager.addChannelCreator(channel, "owner")
        dataManager.addChannelMember(channel, "owner")
        val v1 = dataManager.nextChannelRoleVersion(channel)
        assertEquals(1L, v1)

        val duplicate = dataManager.applySyncedRolePolicy(
            senderPeerID = "owner",
            channel = channel,
            ownerPeerID = "owner",
            adminPeerIDs = listOf("admin1"),
            endorserPeerIDs = emptyList(),
            roleVersion = 1L
        )
        assertFalse(duplicate)

        val stale = dataManager.applySyncedRolePolicy(
            senderPeerID = "owner",
            channel = channel,
            ownerPeerID = "owner",
            adminPeerIDs = listOf("admin1"),
            endorserPeerIDs = emptyList(),
            roleVersion = 0L
        )
        assertFalse(stale)
    }

    @Test
    fun applySyncedRolePolicy_appliesNewVersionAndDemotesMissingAdmins() {
        dataManager.addChannelCreator(channel, "owner")
        dataManager.addChannelMember(channel, "owner")
        dataManager.addChannelMember(channel, "admin1")
        dataManager.setChannelAdmin(channel, "owner", "admin1")
        dataManager.nextChannelRoleVersion(channel) // v1 baseline

        val applied = dataManager.applySyncedRolePolicy(
            senderPeerID = "owner",
            channel = channel,
            ownerPeerID = "owner",
            adminPeerIDs = listOf("admin2"),
            endorserPeerIDs = emptyList(),
            roleVersion = 2L
        )

        assertTrue(applied)
        assertEquals("OWNER", dataManager.getChannelRole(channel, "owner"))
        assertEquals("MEMBER", dataManager.getChannelRole(channel, "admin1"))
        assertEquals("ADMIN", dataManager.getChannelRole(channel, "admin2"))
        assertEquals(2L, dataManager.getChannelRoleVersion(channel))
    }

    @Test
    fun applySyncedRolePolicy_rejectsUnauthorizedSender() {
        dataManager.addChannelCreator(channel, "owner")
        dataManager.addChannelMember(channel, "owner")
        dataManager.addChannelMember(channel, "member1")
        dataManager.nextChannelRoleVersion(channel) // v1 baseline

        val applied = dataManager.applySyncedRolePolicy(
            senderPeerID = "member1",
            channel = channel,
            ownerPeerID = "owner",
            adminPeerIDs = listOf("member1"),
            endorserPeerIDs = emptyList(),
            roleVersion = 2L
        )

        assertFalse(applied)
        assertEquals("MEMBER", dataManager.getChannelRole(channel, "member1"))
        assertEquals(1L, dataManager.getChannelRoleVersion(channel))
    }

    @Test
    fun channelMessages_roundTripEvenWithDeliveryStatus() {
        val message = BitchatMessage(
            sender = "anon1234",
            content = "hello channel",
            timestamp = Date(),
            senderPeerID = "abcd1234efgh5678",
            channel = channel,
            deliveryStatus = DeliveryStatus.PartiallyDelivered(reached = 1, total = 2)
        )
        dataManager.saveChannelMessages(mapOf(channel to listOf(message)))

        val loaded = dataManager.loadChannelMessages()
        val restored = loaded[channel]?.firstOrNull()

        assertNotNull(restored)
        assertEquals("hello channel", restored?.content)
        assertEquals("anon1234", restored?.sender)
        assertEquals(channel, restored?.channel)
        // Delivery state is intentionally transient and should not break persistence.
        assertEquals(null, restored?.deliveryStatus)
    }

    @Test
    fun channelMessages_dropEntriesOlderThan72Hours() {
        val now = System.currentTimeMillis()
        val expired = BitchatMessage(
            sender = "anon1111",
            content = "expired",
            timestamp = Date(now - (73L * 60L * 60L * 1000L)),
            senderPeerID = "peer-expired",
            channel = channel
        )
        val fresh = BitchatMessage(
            sender = "anon2222",
            content = "fresh",
            timestamp = Date(now - (2L * 60L * 60L * 1000L)),
            senderPeerID = "peer-fresh",
            channel = channel
        )
        dataManager.saveChannelMessages(mapOf(channel to listOf(expired, fresh)))

        val loaded = dataManager.loadChannelMessages()[channel].orEmpty()
        assertEquals(1, loaded.size)
        assertEquals("fresh", loaded.first().content)
    }
}
