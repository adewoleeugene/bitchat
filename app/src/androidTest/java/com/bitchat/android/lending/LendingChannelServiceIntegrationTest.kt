package com.bitchat.android.lending

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.data.local.SolanaDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LendingChannelServiceIntegrationTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var database: SolanaDatabase
    private lateinit var service: LendingChannelServiceImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "solana_lending_service_${System.currentTimeMillis()}.db"
        database = Room.databaseBuilder(context, SolanaDatabase::class.java, dbName)
            .addMigrations(
                SolanaDatabase.MIGRATION_1_2,
                SolanaDatabase.MIGRATION_2_3,
                SolanaDatabase.MIGRATION_3_4,
                SolanaDatabase.MIGRATION_4_5,
                SolanaDatabase.MIGRATION_5_6,
                SolanaDatabase.MIGRATION_6_7,
                SolanaDatabase.MIGRATION_7_8,
                SolanaDatabase.MIGRATION_8_9,
                SolanaDatabase.MIGRATION_9_10,
                SolanaDatabase.MIGRATION_10_11,
                SolanaDatabase.MIGRATION_11_12,
                SolanaDatabase.MIGRATION_12_13
            )
            .build()
        database.openHelper.writableDatabase
        service = LendingChannelServiceImpl(
            lendingDao = database.lendingDao(),
            lendingIdGenerator = LendingIdGenerator(
                object : SecureRandom() {
                    private val values = intArrayOf(8, 9, 10, 11, 12, 13)
                    private var index = 0
                    override fun nextInt(bound: Int): Int {
                        val value = values[index % values.size]
                        index += 1
                        return value % bound
                    }
                }
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun createLocalChannel_generatesLendingIdAndResolvesByIdentifier() = kotlinx.coroutines.runBlocking {
        val created = service.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#villagefund",
                displayName = "#villagefund",
                creatorPeerId = "peer-1",
                creatorWalletAddress = "Wallet111111111111111111111111111111111",
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "USDC",
                stakeTokenSymbol = "USDC"
            )
        )

        val byId = service.getChannelByIdentifier(created.lendingId)
        val byChannel = service.getChannelByIdentifier("#villagefund", "mesh:#villagefund")
        val status = service.getStatus(created.lendingId)

        assertEquals(6, created.lendingId.length)
        assertTrue(created.lendingId.all { it in "23456789ABCDEFGHJKLMNPQRSTVWXYZ" })
        assertNotNull(byId)
        assertNotNull(byChannel)
        assertNotNull(status)
        assertEquals(created.lendingId, byId!!.lendingId)
        assertEquals(created.lendingId, byChannel!!.lendingId)
        assertEquals(created.lendingId, status!!.channel.lendingId)
    }
}
