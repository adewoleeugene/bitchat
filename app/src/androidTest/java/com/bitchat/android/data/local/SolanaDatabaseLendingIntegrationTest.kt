package com.bitchat.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.data.local.entities.LendingChannelEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SolanaDatabaseLendingIntegrationTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var database: SolanaDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "solana_lending_test_${System.currentTimeMillis()}.db"
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
                SolanaDatabase.MIGRATION_10_11
            )
            .build()
        database.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun lendingSchema_includesUniqueChannelMappingAndFoundationTables() {
        val sqliteDb = database.openHelper.writableDatabase

        val tables = mutableSetOf<String>()
        sqliteDb.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            while (cursor.moveToNext()) {
                tables.add(cursor.getString(0))
            }
        }

        assertTrue("lending_channels" in tables)
        assertTrue("lending_memberships" in tables)
        assertTrue("lending_pool_snapshots" in tables)
        assertTrue("loan_requests" in tables)
        assertTrue("loan_votes" in tables)
        assertTrue("loan_repayments" in tables)
        assertTrue("credibility_profiles" in tables)

        var hasChannelKeyUniqueIndex = false
        sqliteDb.query("PRAGMA index_list(`lending_channels`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1
                if (name == "index_lending_channels_channelKey" && unique) {
                    hasChannelKeyUniqueIndex = true
                }
            }
        }

        assertTrue(hasChannelKeyUniqueIndex)
    }

    @Test
    fun lendingDao_resolvesChannelsByLendingIdAndChannelKey() = runBlocking {
        val dao = database.lendingDao()
        val channel = LendingChannelEntity(
            lendingId = "AB23CD",
            channelKey = "mesh:#villagefund",
            displayName = "#villagefund",
            creatorPeerId = "peer-1",
            creatorWalletAddress = "Wallet111111111111111111111111111111111",
            requiredStakeAmount = 50_000_000L,
            stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            stakeTokenSymbol = "USDC"
        )

        dao.insertLendingChannel(channel)
        dao.upsertPoolSnapshot(
            LendingPoolSnapshotEntity(
                lendingId = channel.lendingId,
                totalStakedAmount = 50_000_000L,
                availableLiquidityAmount = 50_000_000L
            )
        )

        val byId = dao.getLendingChannelById("AB23CD")
        val byChannelKey = dao.getLendingChannelByChannelKey("mesh:#villagefund")
        val snapshot = dao.getPoolSnapshot("AB23CD")

        assertNotNull(byId)
        assertNotNull(byChannelKey)
        assertNotNull(snapshot)
        assertEquals("mesh:#villagefund", byId!!.channelKey)
        assertEquals("AB23CD", byChannelKey!!.lendingId)
        assertEquals(50_000_000L, snapshot!!.availableLiquidityAmount)
    }
}
