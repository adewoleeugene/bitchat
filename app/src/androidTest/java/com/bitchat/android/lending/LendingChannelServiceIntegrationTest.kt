package com.bitchat.android.lending

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.data.local.SolanaDatabase
import com.bitchat.android.lending.onchain.CastLoanVoteOnChainParams
import com.bitchat.android.lending.onchain.CreateLoanRequestOnChainParams
import com.bitchat.android.lending.onchain.FinalizeLoanRequestOnChainParams
import com.bitchat.android.lending.onchain.InitializeLendingChannelOnChainParams
import com.bitchat.android.lending.onchain.LendingOnChainService
import com.bitchat.android.lending.onchain.OnChainLoanRequestState
import com.bitchat.android.lending.onchain.OnChainSubmissionResult
import com.bitchat.android.lending.onchain.OnChainVoteRecord
import com.bitchat.android.lending.onchain.RecordLoanRepaymentOnChainParams
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
                SolanaDatabase.MIGRATION_12_13,
                SolanaDatabase.MIGRATION_13_14,
                SolanaDatabase.MIGRATION_14_15,
                SolanaDatabase.MIGRATION_15_16
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
            ),
            squadsService = object : SquadsService {
                override fun config(): SquadsConfig = SquadsConfig()
                override suspend fun resolveLendingSquad(lendingId: String): Result<SquadsVaultAccount> =
                    Result.failure(IllegalStateException("squad_not_configured"))
                override suspend fun fetchMultisigState(multisigAddress: String): Result<SquadsMultisigState> =
                    Result.failure(IllegalStateException("squad_not_configured"))
                override suspend fun createLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> =
                    Result.failure(IllegalStateException("squad_not_configured"))
                override suspend fun approveLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> =
                    Result.failure(IllegalStateException("squad_not_configured"))
                override suspend fun executeLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> =
                    Result.failure(IllegalStateException("squad_not_configured"))
                override suspend fun fetchLoanProposalState(lendingId: String, requestId: String): Result<SquadsProposalState?> =
                    Result.success(null)
            },
            lendingOnChainService = object : LendingOnChainService {
                override fun isEnabled(): Boolean = false
                override suspend fun initializeChannelOnChain(params: InitializeLendingChannelOnChainParams) =
                    Result.success(OnChainSubmissionResult(channelPda = "", txSignature = ""))
                override suspend fun createLoanRequestOnChain(params: CreateLoanRequestOnChainParams) =
                    Result.failure<OnChainSubmissionResult>(IllegalStateException("disabled"))
                override suspend fun castLoanVoteOnChain(params: CastLoanVoteOnChainParams) =
                    Result.failure<OnChainSubmissionResult>(IllegalStateException("disabled"))
                override suspend fun finalizeLoanRequestOnChain(params: FinalizeLoanRequestOnChainParams) =
                    Result.failure<OnChainSubmissionResult>(IllegalStateException("disabled"))
                override suspend fun recordLoanRepaymentOnChain(params: RecordLoanRepaymentOnChainParams) =
                    Result.failure<OnChainSubmissionResult>(IllegalStateException("disabled"))
                override suspend fun fetchLoanRequestState(lendingId: String, requestId: String) =
                    Result.failure<OnChainLoanRequestState>(IllegalStateException("disabled"))
                override suspend fun fetchVoteRecords(lendingId: String, requestId: String) =
                    Result.success(emptyList<OnChainVoteRecord>())
            }
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
