package com.bitchat.android.lending

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.data.local.TransactionDao
import com.bitchat.android.data.local.entities.EscrowTransferStatus
import com.bitchat.android.data.local.entities.LendingMemberStatus
import com.bitchat.android.data.local.SolanaDatabase
import com.bitchat.android.data.local.entities.BorrowerType
import com.bitchat.android.data.local.entities.LoanRequestStatus
import com.bitchat.android.data.local.entities.VoteChoice
import com.bitchat.android.lending.onchain.CastLoanVoteOnChainParams
import com.bitchat.android.lending.onchain.CreateLoanRequestOnChainParams
import com.bitchat.android.lending.onchain.FinalizeLoanRequestOnChainParams
import com.bitchat.android.lending.onchain.InitializeLendingChannelOnChainParams
import com.bitchat.android.lending.onchain.LendingOnChainService
import com.bitchat.android.lending.onchain.OnChainLoanRequestState
import com.bitchat.android.lending.onchain.OnChainSubmissionResult
import com.bitchat.android.lending.onchain.OnChainVoteRecord
import com.bitchat.android.lending.onchain.RecordLoanRepaymentOnChainParams
import com.bitchat.android.solana.LendingTransferGateway
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class LendingLifecycleServiceIntegrationTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var database: SolanaDatabase
    private lateinit var channelService: LendingChannelServiceImpl
    private lateinit var escrowService: SquadsLendingEscrowServiceImpl
    private lateinit var lifecycleService: LendingLifecycleServiceImpl
    private lateinit var transactionDao: TransactionDao
    private lateinit var treasuryKeyStore: LendingTreasuryKeyStore
    private lateinit var treasuryTransferService: LendingTreasuryTransferService

    private class FakeLendingOnChainService(
        private val enabled: Boolean
    ) : LendingOnChainService {
        private data class State(
            val channelPda: String,
            val loanRequestPda: String,
            val borrowerWallet: String,
            val principalAmount: Long,
            val durationDays: Int,
            val interestBps: Int,
            val requestedAt: Long,
            val dueAt: Long,
            val purposeHashHex: String = "00".repeat(32),
            val yesVotes: Int = 0,
            val noVotes: Int = 0,
            val approvedAt: Long? = null,
            val disbursedAt: Long? = null,
            val repaidAt: Long? = null,
            val totalRepaidAmount: Long = 0L,
            val status: String = LoanRequestStatus.PENDING,
            val slot: Long = 1L
        )

        private val states = linkedMapOf<String, State>()

        override fun isEnabled(): Boolean = enabled

        override suspend fun initializeChannelOnChain(params: InitializeLendingChannelOnChainParams): Result<OnChainSubmissionResult> {
            return Result.success(
                OnChainSubmissionResult(
                    channelPda = "channel-${params.lendingId}",
                    txSignature = "init-${params.lendingId}"
                )
            )
        }

        override suspend fun createLoanRequestOnChain(params: CreateLoanRequestOnChainParams): Result<OnChainSubmissionResult> {
            val channelPda = "channel-${params.lendingId}"
            val loanRequestPda = "loan-${params.requestId}"
            states[params.requestId] = State(
                channelPda = channelPda,
                loanRequestPda = loanRequestPda,
                borrowerWallet = params.borrowerWallet,
                principalAmount = params.principalAmount,
                durationDays = params.durationDays,
                interestBps = params.interestBps,
                requestedAt = params.requestedAt,
                dueAt = params.dueAt
            )
            return Result.success(
                OnChainSubmissionResult(
                    channelPda = channelPda,
                    loanRequestPda = loanRequestPda,
                    txSignature = "create-${params.requestId}"
                )
            )
        }

        override suspend fun castLoanVoteOnChain(params: CastLoanVoteOnChainParams): Result<OnChainSubmissionResult> {
            val current = states[params.requestId] ?: return Result.failure(IllegalStateException("loan_missing"))
            val updated = if (params.voteChoice.equals(VoteChoice.YES, ignoreCase = true)) {
                current.copy(yesVotes = current.yesVotes + 1, slot = current.slot + 1)
            } else {
                current.copy(noVotes = current.noVotes + 1, slot = current.slot + 1)
            }
            states[params.requestId] = updated
            return Result.success(
                OnChainSubmissionResult(
                    channelPda = updated.channelPda,
                    loanRequestPda = updated.loanRequestPda,
                    txSignature = "vote-${params.requestId}-${updated.yesVotes + updated.noVotes}"
                )
            )
        }

        override suspend fun finalizeLoanRequestOnChain(params: FinalizeLoanRequestOnChainParams): Result<OnChainSubmissionResult> {
            val current = states[params.requestId] ?: return Result.failure(IllegalStateException("loan_missing"))
            val totalVotes = current.yesVotes + current.noVotes
            val updated = if (totalVotes >= 2) {
                if (current.yesVotes > current.noVotes) {
                    current.copy(
                        status = LoanRequestStatus.APPROVED,
                        approvedAt = params.finalizedAt,
                        slot = current.slot + 1
                    )
                } else {
                    current.copy(status = LoanRequestStatus.REJECTED, slot = current.slot + 1)
                }
            } else {
                current
            }
            states[params.requestId] = updated
            return Result.success(
                OnChainSubmissionResult(
                    channelPda = updated.channelPda,
                    loanRequestPda = updated.loanRequestPda,
                    txSignature = "finalize-${params.requestId}"
                )
            )
        }

        override suspend fun recordLoanRepaymentOnChain(params: RecordLoanRepaymentOnChainParams): Result<OnChainSubmissionResult> {
            val current = states[params.requestId] ?: return Result.failure(IllegalStateException("loan_missing"))
            val total = current.totalRepaidAmount + params.amount
            val repaid = total >= current.principalAmount
            states[params.requestId] = current.copy(
                totalRepaidAmount = total,
                repaidAt = if (repaid) params.paidAt else current.repaidAt,
                status = if (repaid) LoanRequestStatus.REPAID else current.status,
                slot = current.slot + 1
            )
            return Result.success(
                OnChainSubmissionResult(
                    channelPda = current.channelPda,
                    loanRequestPda = current.loanRequestPda,
                    txSignature = "repay-${params.requestId}"
                )
            )
        }

        override suspend fun fetchLoanRequestState(lendingId: String, requestId: String): Result<OnChainLoanRequestState> {
            val current = states[requestId] ?: return Result.failure(IllegalStateException("loan_missing"))
            return Result.success(
                OnChainLoanRequestState(
                    channelPda = current.channelPda,
                    loanRequestPda = current.loanRequestPda,
                    borrowerWallet = current.borrowerWallet,
                    principalAmount = current.principalAmount,
                    durationDays = current.durationDays,
                    interestBps = current.interestBps,
                    purposeHashHex = current.purposeHashHex,
                    yesVotes = current.yesVotes,
                    noVotes = current.noVotes,
                    requestedAt = current.requestedAt,
                    dueAt = current.dueAt,
                    approvedAt = current.approvedAt,
                    disbursedAt = current.disbursedAt,
                    repaidAt = current.repaidAt,
                    totalRepaidAmount = current.totalRepaidAmount,
                    chainStatus = current.status,
                    txSignature = "state-$requestId",
                    slot = current.slot
                )
            )
        }

        override suspend fun fetchVoteRecords(lendingId: String, requestId: String): Result<List<OnChainVoteRecord>> {
            return Result.success(emptyList())
        }
    }

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
                ,
                SolanaDatabase.MIGRATION_13_14,
                SolanaDatabase.MIGRATION_14_15,
                SolanaDatabase.MIGRATION_15_16,
                SolanaDatabase.MIGRATION_16_17,
                SolanaDatabase.MIGRATION_17_18
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
            ),
            squadsService = object : SquadsService {
                override fun config(): SquadsConfig = SquadsConfig()
                override suspend fun resolveLendingSquad(lendingId: String): Result<SquadsVaultAccount> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun fetchMultisigState(multisigAddress: String): Result<SquadsMultisigState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun createLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun approveLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun executeLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun fetchLoanProposalState(lendingId: String, requestId: String): Result<SquadsProposalState?> {
                    return Result.success(null)
                }
            },
            lendingOnChainService = FakeLendingOnChainService(enabled = false)
        )
        transactionDao = database.transactionDao()
        treasuryKeyStore = mock()
        treasuryTransferService = mock()
        whenever(treasuryKeyStore.ensureTreasuryWallet(org.mockito.kotlin.any())).thenReturn(
            TreasuryWalletMaterial(
                publicKeyBase58 = "TreasuryWallet111111111111111111111111111",
                privateKey = ByteArray(32) { 7 },
                publicKey = ByteArray(32) { 9 }
            )
        )
        whenever(treasuryKeyStore.getTreasuryWallet(org.mockito.kotlin.any())).thenReturn(
            TreasuryWalletMaterial(
                publicKeyBase58 = "TreasuryWallet111111111111111111111111111",
                privateKey = ByteArray(32) { 7 },
                publicKey = ByteArray(32) { 9 }
            )
        )
        whenever(
            treasuryTransferService.sendSplFromTreasury(
                treasuryPrivateKey = org.mockito.kotlin.any(),
                treasuryOwnerPublicKey = org.mockito.kotlin.any(),
                sourceTokenAccount = org.mockito.kotlin.any(),
                recipientOwnerPublicKey = org.mockito.kotlin.any(),
                mintAddress = org.mockito.kotlin.any(),
                amountAtomic = org.mockito.kotlin.any(),
                decimals = org.mockito.kotlin.any()
            )
        ).thenReturn(Result.success("spl-disbursement-sig"))
        whenever(
            treasuryTransferService.sendSolFromTreasury(
                treasuryPrivateKey = org.mockito.kotlin.any(),
                treasuryOwnerPublicKey = org.mockito.kotlin.any(),
                recipientPublicKey = org.mockito.kotlin.any(),
                amountLamports = org.mockito.kotlin.any()
            )
        ).thenReturn(Result.success("sol-disbursement-sig"))
        escrowService = SquadsLendingEscrowServiceImpl(
            lendingDao = database.lendingDao(),
            transferGateway = object : LendingTransferGateway {
                override suspend fun queueNativeTransfer(
                    recipientPublicKey: String,
                    amountLamports: Long,
                    memo: String?
                ): Result<String> = Result.success("queued-native-$amountLamports")

                override suspend fun queueSplTransfer(
                    recipientPublicKey: String,
                    mintAddress: String,
                    amountAtomic: Long,
                    decimals: Int,
                    symbol: String,
                    memo: String?
                ): Result<String> = Result.success("queued-$amountAtomic")
            },
            transactionDao = transactionDao,
            treasuryKeyStore = treasuryKeyStore,
            treasuryTransferService = treasuryTransferService,
            squadsService = object : SquadsService {
                override fun config(): SquadsConfig = SquadsConfig()
                override suspend fun resolveLendingSquad(lendingId: String): Result<SquadsVaultAccount> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun fetchMultisigState(multisigAddress: String): Result<SquadsMultisigState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun createLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun approveLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun executeLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun fetchLoanProposalState(lendingId: String, requestId: String): Result<SquadsProposalState?> {
                    return Result.success(null)
                }
            }
        )
        lifecycleService = LendingLifecycleServiceImpl(
            lendingDao = database.lendingDao(),
            lendingChannelService = channelService,
            transferGateway = object : LendingTransferGateway {
                override suspend fun queueNativeTransfer(
                    recipientPublicKey: String,
                    amountLamports: Long,
                    memo: String?
                ): Result<String> = Result.success("queued-native-$amountLamports")

                override suspend fun queueSplTransfer(
                    recipientPublicKey: String,
                    mintAddress: String,
                    amountAtomic: Long,
                    decimals: Int,
                    symbol: String,
                    memo: String?
                ): Result<String> = Result.success("queued-$amountAtomic")
            },
            escrowService = escrowService,
            squadsService = object : SquadsService {
                override fun config(): SquadsConfig = SquadsConfig()
                override suspend fun resolveLendingSquad(lendingId: String): Result<SquadsVaultAccount> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun fetchMultisigState(multisigAddress: String): Result<SquadsMultisigState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun createLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun approveLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun executeLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun fetchLoanProposalState(lendingId: String, requestId: String): Result<SquadsProposalState?> {
                    return Result.success(null)
                }
            },
            lendingOnChainService = FakeLendingOnChainService(enabled = false)
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

    @Test
    fun nativeSolApproval_disbursesUsingTreasuryTransfer() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#solfund",
                displayName = "#solfund",
                creatorPeerId = "peer-a",
                creatorWalletAddress = "CreatorWallet1111111111111111111111111111",
                requiredStakeAmount = 2_000_000_000L,
                stakeTokenMint = NATIVE_SOL_ASSET,
                stakeTokenSymbol = NATIVE_SOL_ASSET,
                stakeTokenDecimals = 9
            )
        )

        database.lendingDao().upsertMembership(
            com.bitchat.android.data.local.entities.LendingMembershipEntity(
                lendingId = channel.lendingId,
                memberPeerId = "peer-a",
                walletAddress = "CreatorWallet1111111111111111111111111111",
                stakeAmount = channel.requiredStakeAmount,
                depositStatus = EscrowTransferStatus.CONFIRMED,
                joinStatus = LendingMemberStatus.ACTIVE
            )
        )
        database.lendingDao().upsertMembership(
            com.bitchat.android.data.local.entities.LendingMembershipEntity(
                lendingId = channel.lendingId,
                memberPeerId = "peer-b",
                walletAddress = "BorrowerWallet111111111111111111111111111",
                stakeAmount = channel.requiredStakeAmount,
                depositStatus = EscrowTransferStatus.CONFIRMED,
                joinStatus = LendingMemberStatus.ACTIVE
            )
        )
        database.lendingDao().upsertPoolSnapshot(
            com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity(
                lendingId = channel.lendingId,
                totalStakedAmount = channel.requiredStakeAmount * 2,
                availableLiquidityAmount = channel.requiredStakeAmount * 2
            )
        )

        val opened = lifecycleService.createLoanRequest(
            CreateLoanRequest(
                identifier = channel.lendingId,
                requesterPeerId = "peer-b",
                borrowerType = BorrowerType.INDIVIDUAL,
                principalAmount = 1_000_000_000L,
                durationDays = 7,
                purpose = "restock supplies"
            )
        )

        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-a",
                voteChoice = VoteChoice.YES
            )
        )
        val result = lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-b",
                voteChoice = VoteChoice.YES
            )
        )

        assertEquals(LoanRequestStatus.DISBURSED, result.request.status)
        assertTrue(escrowService.getEscrowProposalsForRequest(opened.requestId).isNotEmpty())
    }

    @Test
    fun onChainMode_keepsMeshVoteImportsFromOverridingChainTruth() = runBlocking {
        val onChainLifecycleService = LendingLifecycleServiceImpl(
            lendingDao = database.lendingDao(),
            lendingChannelService = channelService,
            transferGateway = object : LendingTransferGateway {
                override suspend fun queueNativeTransfer(
                    recipientPublicKey: String,
                    amountLamports: Long,
                    memo: String?
                ): Result<String> = Result.success("queued-native-$amountLamports")

                override suspend fun queueSplTransfer(
                    recipientPublicKey: String,
                    mintAddress: String,
                    amountAtomic: Long,
                    decimals: Int,
                    symbol: String,
                    memo: String?
                ): Result<String> = Result.success("queued-$amountAtomic")
            },
            escrowService = escrowService,
            squadsService = object : SquadsService {
                override fun config(): SquadsConfig = SquadsConfig()
                override suspend fun resolveLendingSquad(lendingId: String): Result<SquadsVaultAccount> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun fetchMultisigState(multisigAddress: String): Result<SquadsMultisigState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun createLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun approveLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun executeLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun fetchLoanProposalState(lendingId: String, requestId: String): Result<SquadsProposalState?> {
                    return Result.success(null)
                }
            },
            lendingOnChainService = FakeLendingOnChainService(enabled = true)
        )

        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#chainlend",
                displayName = "#chainlend",
                creatorPeerId = "peer-1",
                creatorWalletAddress = "CreatorWallet1111111111111111111111111111",
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        onChainLifecycleService.activateMembership(channel.lendingId, "peer-1")
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
        onChainLifecycleService.activateMembership(channel.lendingId, "peer-2")

        val opened = onChainLifecycleService.createLoanRequest(
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

        val imported = onChainLifecycleService.importDiscoveredLoanVote(
            LendingLoanVoteMessage(
                requestId = opened.requestId,
                lendingId = channel.lendingId,
                voterPeerId = "peer-remote",
                voteChoice = VoteChoice.YES,
                yesVotes = 99,
                noVotes = 0,
                requestStatus = LoanRequestStatus.DISBURSED,
                approvedAt = System.currentTimeMillis(),
                disbursedAt = System.currentTimeMillis()
            )
        )

        assertEquals(LoanRequestStatus.PENDING, imported?.status)

        onChainLifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-1",
                voteChoice = VoteChoice.YES
            )
        )
        val approved = onChainLifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-2",
                voteChoice = VoteChoice.YES
            )
        )

        assertEquals(LoanRequestStatus.DISBURSED, approved.request.status)
    }
}
