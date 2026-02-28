package com.bitchat.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.data.local.entities.TokenGateConfigEntity
import com.bitchat.android.data.local.entities.TokenGateEligibilityCacheEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SolanaDatabaseTokenGateIntegrationTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var database: SolanaDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "solana_test_${System.currentTimeMillis()}.db"
        database = Room.databaseBuilder(context, SolanaDatabase::class.java, dbName)
            .addMigrations(
                SolanaDatabase.MIGRATION_1_2,
                SolanaDatabase.MIGRATION_2_3,
                SolanaDatabase.MIGRATION_3_4,
                SolanaDatabase.MIGRATION_4_5
            )
            .build()
        // Force DB creation.
        database.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun tokenGateSchema_includesPhase1FieldsAndEligibilityCacheTable() {
        val sqliteDb = database.openHelper.writableDatabase

        val tokenGateColumns = mutableSetOf<String>()
        sqliteDb.query("PRAGMA table_info(`token_gate_configs`)").use { cursor ->
            while (cursor.moveToNext()) {
                tokenGateColumns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

        assertTrue(tokenGateColumns.contains("policyVersion"))
        assertTrue(tokenGateColumns.contains("gateHash"))

        var cacheTableSql: String? = null
        sqliteDb.query(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='token_gate_eligibility_cache'"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cacheTableSql = cursor.getString(0)
            }
        }

        assertNotNull(cacheTableSql)
        assertTrue(cacheTableSql!!.contains("PRIMARY KEY(`channelKey`, `walletAddress`, `gateHash`)"))
    }

    @Test
    fun eligibilityCache_isScopedByGateHash() {
        val dao = database.tokenGateDao()
        val channel = "mesh:#vip"
        val wallet = "Wallet111111111111111111111111111111111"

        runBlocking {
            dao.insertTokenGate(
                TokenGateConfigEntity(
                    channelKey = channel,
                    gateType = "SPL_TOKEN",
                    tokenMintAddress = "Mint1111111111111111111111111111111111111",
                    minBalance = 100,
                    tokenSymbol = "USDC",
                    tokenDecimals = 6,
                    creatorPublicKey = "Creator1111111111111111111111111111111111",
                    policyVersion = 1,
                    gateHash = "hash_v1"
                )
            )
            dao.upsertEligibilityCache(
                TokenGateEligibilityCacheEntity(
                    channelKey = channel,
                    walletAddress = wallet,
                    gateHash = "hash_v1",
                    isEligible = true,
                    observedBalance = 150,
                    validatedAt = 1_000,
                    expiresAt = 10_000,
                    source = "RPC"
                )
            )
            dao.upsertEligibilityCache(
                TokenGateEligibilityCacheEntity(
                    channelKey = channel,
                    walletAddress = wallet,
                    gateHash = "hash_v2",
                    isEligible = false,
                    observedBalance = 10,
                    validatedAt = 2_000,
                    expiresAt = 20_000,
                    source = "RPC"
                )
            )
        }

        val (v1, v2) = runBlocking {
            Pair(
                dao.getEligibilityCache(channel, wallet, "hash_v1"),
                dao.getEligibilityCache(channel, wallet, "hash_v2")
            )
        }

        assertNotNull(v1)
        assertNotNull(v2)
        assertEquals(true, v1!!.isEligible)
        assertEquals(false, v2!!.isEligible)
    }
}
