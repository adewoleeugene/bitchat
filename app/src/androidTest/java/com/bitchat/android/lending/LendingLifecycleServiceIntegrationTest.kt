package com.bitchat.android.lending

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.data.local.SolanaDatabase
import com.bitchat.android.data.local.entities.BorrowerType
import com.bitchat.android.data.local.entities.LoanRequestStatus
import com.bitchat.android.data.local.entities.VoteChoice
import com.bitchat.android.solana.LendingTransferGateway
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class LendingLifecycleServiceIntegrationTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var database: SolanaDatabase
    private lateinit var channelService: LendingChannelServiceImpl
    private lateinit var escrowService: SquadsLendingEscrowServiceImpl
    private lateinit var lifecycleService: LendingLifecycleServiceImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "solana_lending_lifecycle_${System.currentTimeMillis()}.db"
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
        channelService = LendingChannelServiceImpl(
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
        escrowService = SquadsLendingEscrowServiceImpl(
            lendingDao = database.lendingDao(),
            transferGateway = object : LendingTransferGateway {
                override suspend fun queueSplTransfer(
                    recipientPublicKey: String,
                    mintAddress: String,
                    amountAtomic: Long,
                    decimals: Int,
                    symbol: String,
                    memo: String?
                ): Result<String> = Result.success("queued-$amountAtomic")
            }
        )
        lifecycleService = LendingLifecycleServiceImpl(
            lendingDao = database.lendingDao(),
            lendingChannelService = channelService,
            transferGateway = object : LendingTransferGateway {
                override suspend fun queueSplTransfer(
                    recipientPublicKey: String,
                    mintAddress: String,
                    amountAtomic: Long,
                    decimals: Int,
                    symbol: String,
                    memo: String?
                ): Result<String> = Result.success("queued-$amountAtomic")
            },
            escrowService = escrowService
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun lifecycle_flowUpdatesVotesRepaymentsAndLeaveRules() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#cooplend",
                displayName = "#cooplend",
                creatorPeerId = "peer-1",
                creatorWalletAddress = "CreatorWallet1111111111111111111111111111",
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        lifecycleService.activateMembership(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = "BorrowerWallet111111111111111111111111111",
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        lifecycleService.activateMembership(channel.lendingId, "peer-2")

        val opened = lifecycleService.createLoanRequest(
            CreateLoanRequest(
                identifier = channel.lendingId,
                requesterPeerId = "peer-2",
                borrowerType = BorrowerType.INDIVIDUAL,
                principalAmount = 40_000_000L,
                durationDays = 14,
                purpose = "buy stock for the market stall"
            )
        )
        assertEquals(LoanRequestStatus.PENDING, opened.status)

        val firstVote = lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-1",
                voteChoice = VoteChoice.YES
            )
        )
        assertTrue(!firstVote.approved)

        val secondVote = lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-2",
                voteChoice = VoteChoice.YES
            )
        )
        assertTrue(secondVote.approved)
        assertEquals(LoanRequestStatus.DISBURSED, secondVote.request.status)
        assertTrue(escrowService.getEscrowProposalsForRequest(opened.requestId).isNotEmpty())

        val snapshotAfterVote = lifecycleService.getPoolSnapshot(channel.lendingId)!!
        assertEquals(60_000_000L, snapshotAfterVote.availableLiquidityAmount)

        try {
            lifecycleService.leaveChannel(
                LeaveLendingChannelRequest(
                    identifier = channel.lendingId,
                    memberPeerId = "peer-2"
                )
            )
            throw AssertionError("leave should have been blocked by active loan")
        } catch (expected: IllegalStateException) {
            assertEquals("active_loan_blocks_exit", expected.message)
        }

        val repayment = lifecycleService.repayLoan(
            RecordLoanRepaymentRequest(
                requestId = opened.requestId,
                payerPeerId = "peer-2",
                amount = 42_000_000L
            )
        )
        assertEquals(LoanRequestStatus.REPAID, repayment.updatedRequest.status)
        assertEquals(0L, repayment.remainingBalance)

        val left = lifecycleService.leaveChannel(
            LeaveLendingChannelRequest(
                identifier = channel.lendingId,
                memberPeerId = "peer-2"
            )
        )
        assertEquals("EXITED", left.membership.joinStatus)
        assertTrue(left.escrowProposalId?.startsWith("SQP-") == true)
    }
}
