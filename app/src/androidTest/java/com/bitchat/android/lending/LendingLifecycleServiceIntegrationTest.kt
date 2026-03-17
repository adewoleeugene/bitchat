package com.bitchat.android.lending

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.data.local.TransactionDao
import com.bitchat.android.data.local.entities.CustodyExecutionStatus
import com.bitchat.android.data.local.entities.EscrowTransferStatus
import com.bitchat.android.data.local.entities.LendingMemberStatus
import com.bitchat.android.data.local.SolanaDatabase
import com.bitchat.android.data.local.entities.BorrowerType
import com.bitchat.android.data.local.entities.QueuedTransactionEntity
import com.bitchat.android.data.local.entities.LoanRequestStatus
import com.bitchat.android.data.local.entities.VoteChoice
import com.bitchat.android.data.models.TransactionStatus
import com.bitchat.android.solana.LendingTransferGateway
import com.bitchat.android.solana.SolanaRpcService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class LendingLifecycleServiceIntegrationTest {
    companion object {
        private const val CREATOR_WALLET = "11111111111111111111111111111111"
        private const val BORROWER_WALLET = "So11111111111111111111111111111111111111112"
        private const val MEMBER_WALLET = "Stake11111111111111111111111111111111111111"
        private const val MEMBER_WALLET_ALT = "Vote111111111111111111111111111111111111111"
        private const val VAULT_WALLET = "SysvarRent111111111111111111111111111111111"
    }

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var database: SolanaDatabase
    private lateinit var channelService: LendingChannelServiceImpl
    private lateinit var escrowService: SquadsLendingEscrowServiceImpl
    private lateinit var lifecycleService: LendingLifecycleServiceImpl
    private lateinit var transactionDao: TransactionDao
    private lateinit var treasuryKeyStore: LendingTreasuryKeyStore
    private lateinit var treasuryTransferService: LendingTreasuryTransferService
    private lateinit var rpcService: SolanaRpcService

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
                SolanaDatabase.MIGRATION_17_18,
                SolanaDatabase.MIGRATION_18_19,
                SolanaDatabase.MIGRATION_19_20,
                SolanaDatabase.MIGRATION_20_21,
                SolanaDatabase.MIGRATION_21_22
            )
            .build()
        database.openHelper.writableDatabase
        rpcService = mock()
        runBlocking {
            whenever(rpcService.confirmTransaction(org.mockito.kotlin.any())).thenReturn(Result.success(true))
        }
        channelService = LendingChannelServiceImpl(
            lendingDao = database.lendingDao(),
            lendingIdGenerator = LendingIdGenerator(
                object : SecureRandom() {
                    private val values = intArrayOf(
                        8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
                        18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
                        28, 29, 30, 31, 0, 1, 2, 3, 4, 5, 6, 7
                    )
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
                override suspend fun fetchProgramConfigState(): Result<SquadsProgramConfigState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun createLendingMultisig(
                    memberWallets: List<String>,
                    threshold: Int
                ): Result<SquadsCreatedMultisig> {
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
        transactionDao = database.transactionDao()
        treasuryKeyStore = mock()
        treasuryTransferService = mock()
        whenever(treasuryKeyStore.ensureTreasuryWallet(org.mockito.kotlin.any())).thenReturn(
            TreasuryWalletMaterial(
                publicKeyBase58 = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                privateKey = ByteArray(32) { 7 },
                publicKey = ByteArray(32) { 9 }
            )
        )
        whenever(treasuryKeyStore.getTreasuryWallet(org.mockito.kotlin.any())).thenReturn(
            TreasuryWalletMaterial(
                publicKeyBase58 = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                privateKey = ByteArray(32) { 7 },
                publicKey = ByteArray(32) { 9 }
            )
        )
        runBlocking {
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
        }
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
                override suspend fun fetchProgramConfigState(): Result<SquadsProgramConfigState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun createLendingMultisig(
                    memberWallets: List<String>,
                    threshold: Int
                ): Result<SquadsCreatedMultisig> {
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
                override suspend fun fetchProgramConfigState(): Result<SquadsProgramConfigState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun createLendingMultisig(
                    memberWallets: List<String>,
                    threshold: Int
                ): Result<SquadsCreatedMultisig> {
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
            rpcService = rpcService
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(dbName)
    }

    private suspend fun activateMembershipForTest(lendingId: String, memberPeerId: String) {
        lifecycleService.activateMembership(lendingId, memberPeerId)
        val membership = database.lendingDao().getMembership(lendingId, memberPeerId) ?: return
        if (membership.joinStatus != LendingMemberStatus.ACTIVE ||
            membership.depositStatus != EscrowTransferStatus.CONFIRMED
        ) {
            database.lendingDao().upsertMembership(
                membership.copy(
                    joinStatus = LendingMemberStatus.ACTIVE,
                    depositStatus = EscrowTransferStatus.CONFIRMED,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun canonicalSignature(fill: Char = 'A'): String = fill.toString().repeat(88)

    private fun sharedCustodyService(): SquadsService = object : SquadsService {
        override fun config(): SquadsConfig = SquadsConfig()

        override suspend fun resolveLendingSquad(lendingId: String): Result<SquadsVaultAccount> =
            Result.success(
                SquadsVaultAccount(
                    multisigAddress = "squad-multisig",
                    vaultAddress = VAULT_WALLET,
                    requiredApprovalCount = 2,
                    targetMemberCount = 3,
                    cluster = "devnet"
                )
            )

        override suspend fun fetchMultisigState(multisigAddress: String): Result<SquadsMultisigState> =
            Result.success(
                SquadsMultisigState(
                    multisigAddress = multisigAddress,
                    threshold = 2,
                    transactionIndex = 1,
                    staleTransactionIndex = 0,
                    memberCount = 3
                )
            )

        override suspend fun fetchProgramConfigState(): Result<SquadsProgramConfigState> =
            Result.failure(IllegalStateException("not_needed"))

        override suspend fun createLendingMultisig(
            memberWallets: List<String>,
            threshold: Int
        ): Result<SquadsCreatedMultisig> =
            Result.failure(IllegalStateException("not_needed"))

        override suspend fun createLoanProposal(
            lendingId: String,
            requestId: String
        ): Result<SquadsProposalState> =
            Result.failure(IllegalStateException("not_needed"))

        override suspend fun approveLoanProposal(
            lendingId: String,
            requestId: String
        ): Result<SquadsProposalState> =
            Result.failure(IllegalStateException("not_needed"))

        override suspend fun executeLoanProposal(
            lendingId: String,
            requestId: String
        ): Result<SquadsProposalState> =
            Result.failure(IllegalStateException("not_needed"))

        override suspend fun fetchLoanProposalState(
            lendingId: String,
            requestId: String
        ): Result<SquadsProposalState?> = Result.success(null)
    }

    private fun sharedCustodyLifecycleService(
        transferGateway: LendingTransferGateway = object : LendingTransferGateway {
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
        }
    ): LendingLifecycleServiceImpl {
        return LendingLifecycleServiceImpl(
            lendingDao = database.lendingDao(),
            lendingChannelService = channelService,
            transferGateway = transferGateway,
            escrowService = escrowService,
            squadsService = sharedCustodyService(),
            rpcService = rpcService
        )
    }

    @Test
    fun lifecycle_flowUpdatesVotesRepaymentsAndLeaveRules() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#cooplend",
                displayName = "#cooplend",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-3")

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
                voterPeerId = "peer-3",
                voteChoice = VoteChoice.YES
            )
        )
        assertTrue(secondVote.approved)
        assertEquals(LoanRequestStatus.APPROVED, secondVote.request.status)
        val disbursed = lifecycleService.disburseApprovedLoan(
            DisburseApprovedLoanRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-1"
            )
        )
        assertEquals(LoanRequestStatus.DISBURSED, disbursed.status)
        assertTrue(escrowService.getEscrowProposalsForRequest(opened.requestId).isNotEmpty())

        val snapshotAfterVote = lifecycleService.getPoolSnapshot(channel.lendingId)!!
        assertEquals(110_000_000L, snapshotAfterVote.availableLiquidityAmount)

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
        assertEquals(LoanRequestStatus.DISBURSED, repayment.updatedRequest.status)
        assertEquals(42_000_000L, repayment.remainingBalance)
        assertEquals(EscrowTransferStatus.PENDING, repayment.repayment.txStatus)

        transactionDao.insertTransaction(
            QueuedTransactionEntity(
                id = repayment.repayment.txSignature!!,
                signedTransactionBase64 = "",
                senderPublicKey = BORROWER_WALLET,
                recipientPublicKey = VAULT_WALLET,
                amountLamports = repayment.repayment.amount,
                assetKind = "SPL_TOKEN",
                assetMintAddress = channel.stakeTokenMint,
                assetSymbol = channel.stakeTokenSymbol,
                assetDecimals = channel.stakeTokenDecimals,
                status = TransactionStatus.CONFIRMED.value,
                txSignature = "repayment-confirmed-sig",
                createdAt = System.currentTimeMillis(),
                ttlExpiresAt = System.currentTimeMillis() + 60_000L
            )
        )
        lifecycleService.repairRepaymentsForTransaction(
            queuedTransactionId = repayment.repayment.txSignature!!,
            transactionStatus = TransactionStatus.CONFIRMED.value,
            txSignature = "repayment-confirmed-sig"
        )

        val reconciledLoan = database.lendingDao().getLoanRequestById(opened.requestId)
        assertEquals(LoanRequestStatus.REPAID, reconciledLoan?.status)

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
    fun repayLoan_doesNotPersistRepaymentWhenTransferFails() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#repayfail",
                displayName = "#repayfail",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-3")

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
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-1",
                voteChoice = VoteChoice.YES
            )
        )
        val approved = lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-3",
                voteChoice = VoteChoice.YES
            )
        )
        assertEquals(LoanRequestStatus.APPROVED, approved.request.status)
        val disbursed = lifecycleService.disburseApprovedLoan(
            DisburseApprovedLoanRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-1"
            )
        )
        assertEquals(LoanRequestStatus.DISBURSED, disbursed.status)

        val failingLifecycleService = LendingLifecycleServiceImpl(
            lendingDao = database.lendingDao(),
            lendingChannelService = channelService,
            transferGateway = object : LendingTransferGateway {
                override suspend fun queueNativeTransfer(
                    recipientPublicKey: String,
                    amountLamports: Long,
                    memo: String?
                ): Result<String> = Result.failure(IllegalStateException("repayment_transfer_failed"))

                override suspend fun queueSplTransfer(
                    recipientPublicKey: String,
                    mintAddress: String,
                    amountAtomic: Long,
                    decimals: Int,
                    symbol: String,
                    memo: String?
                ): Result<String> = Result.failure(IllegalStateException("repayment_transfer_failed"))
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
                override suspend fun fetchProgramConfigState(): Result<SquadsProgramConfigState> {
                    return Result.failure(IllegalStateException("squad_not_configured"))
                }
                override suspend fun createLendingMultisig(
                    memberWallets: List<String>,
                    threshold: Int
                ): Result<SquadsCreatedMultisig> {
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
            rpcService = rpcService
        )

        try {
            failingLifecycleService.repayLoan(
                RecordLoanRepaymentRequest(
                    requestId = opened.requestId,
                    payerPeerId = "peer-2",
                    amount = 42_000_000L
                )
            )
            throw AssertionError("repayment should fail when transfer queueing fails")
        } catch (expected: IllegalStateException) {
            assertEquals("repayment_transfer_failed", expected.message)
        }

        val repayments = database.lendingDao().getRepaymentsForRequest(opened.requestId)
        assertTrue(repayments.isEmpty())
        val loanAfterFailure = database.lendingDao().getLoanRequestById(opened.requestId)
        assertEquals(LoanRequestStatus.DISBURSED, loanAfterFailure?.status)
    }

    @Test
    fun repayLoan_usesNativeTransferForSolChannelsAndWaitsForConfirmation() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#solrepay",
                displayName = "#solrepay",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 2_000_000_000L,
                stakeTokenMint = NATIVE_SOL_ASSET,
                stakeTokenSymbol = NATIVE_SOL_ASSET,
                stakeTokenDecimals = 9
            )
        )
        database.lendingDao().upsertMembership(
            com.bitchat.android.data.local.entities.LendingMembershipEntity(
                lendingId = channel.lendingId,
                memberPeerId = "peer-1",
                walletAddress = CREATOR_WALLET,
                stakeAmount = channel.requiredStakeAmount,
                depositStatus = EscrowTransferStatus.CONFIRMED,
                joinStatus = LendingMemberStatus.ACTIVE
            )
        )
        database.lendingDao().upsertMembership(
            com.bitchat.android.data.local.entities.LendingMembershipEntity(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = channel.requiredStakeAmount,
                depositStatus = EscrowTransferStatus.CONFIRMED,
                joinStatus = LendingMemberStatus.ACTIVE
            )
        )
        database.lendingDao().upsertMembership(
            com.bitchat.android.data.local.entities.LendingMembershipEntity(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = channel.requiredStakeAmount,
                depositStatus = EscrowTransferStatus.CONFIRMED,
                joinStatus = LendingMemberStatus.ACTIVE
            )
        )
        database.lendingDao().upsertPoolSnapshot(
            com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity(
                lendingId = channel.lendingId,
                totalStakedAmount = channel.requiredStakeAmount * 3,
                availableLiquidityAmount = channel.requiredStakeAmount * 3
            )
        )
        escrowService.provisionChannelEscrow(channel.lendingId)

        val opened = lifecycleService.createLoanRequest(
            CreateLoanRequest(
                identifier = channel.lendingId,
                requesterPeerId = "peer-2",
                borrowerType = BorrowerType.INDIVIDUAL,
                principalAmount = 400_000_000L,
                durationDays = 14,
                purpose = "restock"
            )
        )
        lifecycleService.castVote(CastLoanVoteRequest(opened.requestId, "peer-1", VoteChoice.YES))
        lifecycleService.castVote(CastLoanVoteRequest(opened.requestId, "peer-3", VoteChoice.YES))
        database.lendingDao().upsertLoanRequest(
            opened.copy(
                status = LoanRequestStatus.DISBURSED,
                disbursedAt = System.currentTimeMillis()
            )
        )

        var nativeTransferCount = 0
        var splTransferCount = 0
        val nativeLifecycleService = LendingLifecycleServiceImpl(
            lendingDao = database.lendingDao(),
            lendingChannelService = channelService,
            transferGateway = object : LendingTransferGateway {
                override suspend fun queueNativeTransfer(
                    recipientPublicKey: String,
                    amountLamports: Long,
                    memo: String?
                ): Result<String> {
                    nativeTransferCount += 1
                    return Result.success("queued-native-repayment")
                }

                override suspend fun queueSplTransfer(
                    recipientPublicKey: String,
                    mintAddress: String,
                    amountAtomic: Long,
                    decimals: Int,
                    symbol: String,
                    memo: String?
                ): Result<String> {
                    splTransferCount += 1
                    return Result.success("queued-spl-repayment")
                }
            },
            escrowService = escrowService,
            squadsService = object : SquadsService {
                override fun config(): SquadsConfig = SquadsConfig()
                override suspend fun resolveLendingSquad(lendingId: String): Result<SquadsVaultAccount> =
                    Result.failure(IllegalStateException("squad_not_configured"))
                override suspend fun fetchMultisigState(multisigAddress: String): Result<SquadsMultisigState> =
                    Result.failure(IllegalStateException("squad_not_configured"))
                override suspend fun fetchProgramConfigState(): Result<SquadsProgramConfigState> =
                    Result.failure(IllegalStateException("squad_not_configured"))
                override suspend fun createLendingMultisig(
                    memberWallets: List<String>,
                    threshold: Int
                ): Result<SquadsCreatedMultisig> =
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
            rpcService = rpcService
        )

        val repayment = nativeLifecycleService.repayLoan(
            RecordLoanRepaymentRequest(
                requestId = opened.requestId,
                payerPeerId = "peer-2",
                amount = 420_000_000L
            )
        )

        assertEquals(1, nativeTransferCount)
        assertEquals(0, splTransferCount)
        assertEquals(EscrowTransferStatus.PENDING, repayment.repayment.txStatus)
        assertEquals(LoanRequestStatus.DISBURSED, repayment.updatedRequest.status)
    }

    @Test
    fun repayLoan_requiresConfiguredVault() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#repayvault",
                displayName = "#repayvault",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-3")

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
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-1",
                voteChoice = VoteChoice.YES
            )
        )
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-3",
                voteChoice = VoteChoice.YES
            )
        )
        database.lendingDao().upsertEscrowAccount(
            com.bitchat.android.data.local.entities.LendingEscrowAccountEntity(
                lendingId = channel.lendingId,
                multisigAddress = "11111111111111111111111111111111",
                vaultAddress = "",
                vaultTokenAccountAddress = ""
            )
        )
        database.lendingDao().upsertLoanRequest(
            opened.copy(
                status = LoanRequestStatus.DISBURSED,
                disbursedAt = System.currentTimeMillis()
            )
        )

        try {
            lifecycleService.repayLoan(
                RecordLoanRepaymentRequest(
                    requestId = opened.requestId,
                    payerPeerId = "peer-2",
                    amount = 42_000_000L
                )
            )
            throw AssertionError("repayment should fail when no treasury vault is configured")
        } catch (expected: IllegalStateException) {
            assertEquals("repayment_vault_not_configured", expected.message)
        }

        val repayments = database.lendingDao().getRepaymentsForRequest(opened.requestId)
        assertTrue(repayments.isEmpty())
        val loanAfterFailure = database.lendingDao().getLoanRequestById(opened.requestId)
        assertEquals(LoanRequestStatus.DISBURSED, loanAfterFailure?.status)
    }

    @Test
    fun castVote_requiresChannelMinimumVoteCountBeforeApproval() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#minvotes",
                displayName = "#minvotes",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                minimumVoteCount = 3,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-4",
                walletAddress = MEMBER_WALLET_ALT,
                stakeAmount = 50_000_000L,
                credibilityScore = 74,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        activateMembershipForTest(channel.lendingId, "peer-3")
        activateMembershipForTest(channel.lendingId, "peer-4")

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

        val firstVote = lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-1",
                voteChoice = VoteChoice.YES
            )
        )
        assertEquals(LoanRequestStatus.PENDING, firstVote.request.status)

        val secondVote = lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-3",
                voteChoice = VoteChoice.YES
            )
        )
        assertEquals(LoanRequestStatus.PENDING, secondVote.request.status)

        val thirdVote = lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-4",
                voteChoice = VoteChoice.YES
            )
        )
        assertEquals(LoanRequestStatus.APPROVED, thirdVote.request.status)
    }

    @Test
    fun openSignerReview_promotesCommunityApprovedLoanToSignerReview() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#review",
                displayName = "#review",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-3")

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
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-1",
                voteChoice = VoteChoice.YES
            )
        )
        val approved = lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-3",
                voteChoice = VoteChoice.YES
            )
        )
        assertEquals(LoanRequestStatus.COMMUNITY_APPROVED, approved.request.status)

        val result = lifecycleService.openSignerReview(
            OpenSignerReviewRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-1",
                actorIsAdmin = true
            )
        )

        assertTrue(result.created)
        assertEquals(LoanRequestStatus.SIGNER_REVIEW, result.request.status)
        assertEquals(opened.requestId, result.review.requestId)
        assertEquals(result.review.reviewId, lifecycleService.getSignerReview(opened.requestId)?.reviewId)
    }

    @Test
    fun openSignerReview_requiresAdminActor() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#review-admin",
                displayName = "#review-admin",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-3")

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
        lifecycleService.castVote(CastLoanVoteRequest(opened.requestId, "peer-1", VoteChoice.YES))
        lifecycleService.castVote(CastLoanVoteRequest(opened.requestId, "peer-3", VoteChoice.YES))

        try {
            lifecycleService.openSignerReview(
                OpenSignerReviewRequest(
                    requestId = opened.requestId,
                    actorPeerId = "peer-2"
                )
            )
            throw AssertionError("signer review should fail for non-admins")
        } catch (expected: IllegalStateException) {
            assertEquals("admin_only_signer_review", expected.message)
        }
    }

    @Test
    fun authorizeSignerReview_marksLoanSignerApprovedWithoutSquadFallback() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#authorize",
                displayName = "#authorize",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-3")

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
        lifecycleService.castVote(CastLoanVoteRequest(opened.requestId, "peer-1", VoteChoice.YES))
        lifecycleService.castVote(CastLoanVoteRequest(opened.requestId, "peer-3", VoteChoice.YES))
        lifecycleService.openSignerReview(
            OpenSignerReviewRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-1",
                actorIsAdmin = true
            )
        )

        val result = lifecycleService.authorizeSignerReview(
            AuthorizeSignerReviewRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-3",
                actorIsApprover = true
            )
        )

        assertEquals(LoanRequestStatus.SIGNER_APPROVED, result.request.status)
        assertEquals(com.bitchat.android.data.local.entities.LendingSignerReviewStatus.APPROVED, result.review.status)
        assertEquals(result.review.reviewId, lifecycleService.getSignerReview(opened.requestId)?.reviewId)
    }

    @Test
    fun authorizeSignerReview_requiresApproverActor() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#authorize-role",
                displayName = "#authorize-role",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-3")

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
        lifecycleService.castVote(CastLoanVoteRequest(opened.requestId, "peer-1", VoteChoice.YES))
        lifecycleService.castVote(CastLoanVoteRequest(opened.requestId, "peer-3", VoteChoice.YES))
        lifecycleService.openSignerReview(
            OpenSignerReviewRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-1",
                actorIsAdmin = true
            )
        )

        try {
            lifecycleService.authorizeSignerReview(
                AuthorizeSignerReviewRequest(
                    requestId = opened.requestId,
                    actorPeerId = "peer-2"
                )
            )
            throw AssertionError("signer authorization should fail for non-approvers")
        } catch (expected: IllegalStateException) {
            assertEquals("approver_only_signer_authorization", expected.message)
        }
    }

    @Test
    fun disburseApprovedLoan_executesApprovedSquadProposal() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#squad-execute",
                displayName = "#squad-execute",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 2_000_000_000L,
                stakeTokenMint = NATIVE_SOL_ASSET,
                stakeTokenSymbol = NATIVE_SOL_ASSET,
                stakeTokenDecimals = 9
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 2_000_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 2_000_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-3")

        var proposalState: SquadsProposalState? = null
        var executeCalls = 0
        val squadLifecycleService = LendingLifecycleServiceImpl(
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
                override suspend fun resolveLendingSquad(lendingId: String): Result<SquadsVaultAccount> =
                    Result.success(
                        SquadsVaultAccount(
                            multisigAddress = "squad-multisig",
                            vaultAddress = "squad-vault",
                            requiredApprovalCount = 2,
                            targetMemberCount = 3,
                            cluster = "devnet"
                        )
                    )

                override suspend fun fetchMultisigState(multisigAddress: String): Result<SquadsMultisigState> =
                    Result.success(
                        SquadsMultisigState(
                            multisigAddress = multisigAddress,
                            threshold = 2,
                            transactionIndex = 1,
                            staleTransactionIndex = 0,
                            memberCount = 3
                        )
                    )
                override suspend fun fetchProgramConfigState(): Result<SquadsProgramConfigState> =
                    Result.failure(IllegalStateException("not_needed"))
                override suspend fun createLendingMultisig(
                    memberWallets: List<String>,
                    threshold: Int
                ): Result<SquadsCreatedMultisig> =
                    Result.failure(IllegalStateException("not_needed"))

                override suspend fun createLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    proposalState = SquadsProposalState(
                        multisigAddress = "squad-multisig",
                        vaultAddress = "squad-vault",
                        proposalAddress = "proposal-$requestId",
                        transactionIndex = 1,
                        approvedCount = 1,
                        threshold = 2,
                        status = SQUADS_PROPOSAL_STATUS_ACTIVE
                    )
                    return Result.success(proposalState!!)
                }

                override suspend fun approveLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    proposalState = (proposalState ?: return Result.failure(IllegalStateException("squad_proposal_not_created"))).copy(
                        approvedCount = 2,
                        status = SQUADS_PROPOSAL_STATUS_APPROVED,
                        approvedAt = System.currentTimeMillis(),
                        txSignature = "approve-$requestId"
                    )
                    return Result.success(proposalState!!)
                }

                override suspend fun executeLoanProposal(lendingId: String, requestId: String): Result<SquadsProposalState> {
                    executeCalls += 1
                    proposalState = (proposalState ?: return Result.failure(IllegalStateException("squad_proposal_not_created"))).copy(
                        status = SQUADS_PROPOSAL_STATUS_EXECUTED,
                        executedAt = System.currentTimeMillis(),
                        txSignature = "execute-$requestId"
                    )
                    return Result.success(proposalState!!)
                }

                override suspend fun fetchLoanProposalState(lendingId: String, requestId: String): Result<SquadsProposalState?> =
                    Result.success(proposalState)
            },
            rpcService = rpcService
        )

        val opened = squadLifecycleService.createLoanRequest(
            CreateLoanRequest(
                identifier = channel.lendingId,
                requesterPeerId = "peer-2",
                borrowerType = BorrowerType.INDIVIDUAL,
                principalAmount = 400_000_000L,
                durationDays = 14,
                purpose = "restock"
            )
        )
        squadLifecycleService.castVote(CastLoanVoteRequest(opened.requestId, "peer-1", VoteChoice.YES))
        squadLifecycleService.castVote(CastLoanVoteRequest(opened.requestId, "peer-3", VoteChoice.YES))
        squadLifecycleService.openSignerReview(
            OpenSignerReviewRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-1",
                actorIsAdmin = true
            )
        )
        squadLifecycleService.authorizeSignerReview(
            AuthorizeSignerReviewRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-3",
                actorIsApprover = true
            )
        )

        val disbursed = squadLifecycleService.disburseApprovedLoan(
            DisburseApprovedLoanRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-1",
                actorIsAdmin = true
            )
        )

        assertEquals(1, executeCalls)
        assertEquals(LoanRequestStatus.DISBURSED, disbursed.status)
        val mirroredProposal = database.lendingDao().getEscrowProposalsForRequest(opened.requestId).single()
        assertEquals("proposal-${opened.requestId}", mirroredProposal.proposalId)
        assertEquals(CustodyExecutionStatus.EXECUTED, mirroredProposal.custodyExecutionStatus)
    }

    @Test
    fun castVote_rejectsVotesAfterVotingWindowCloses() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#vote-window",
                displayName = "#vote-window",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
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
        database.lendingDao().upsertLoanRequest(
            opened.copy(
                requestedAt = System.currentTimeMillis() - (25L * 60L * 60L * 1000L),
                dueAt = System.currentTimeMillis() + (14L * 24L * 60L * 60L * 1000L)
            )
        )

        try {
            lifecycleService.castVote(
                CastLoanVoteRequest(
                    requestId = opened.requestId,
                    voterPeerId = "peer-1",
                    voteChoice = VoteChoice.YES
                )
            )
            throw AssertionError("vote should fail after the voting window closes")
        } catch (expected: IllegalStateException) {
            assertEquals("loan_request_voting_closed", expected.message)
        }

        val storedLoan = database.lendingDao().getLoanRequestById(opened.requestId)
        assertEquals(LoanRequestStatus.REJECTED, storedLoan?.status)
        val storedVotes = database.lendingDao().getVotesForRequest(opened.requestId)
        assertTrue(storedVotes.isEmpty())
    }

    @Test
    fun castVote_requiresActiveMembership() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#membersonly",
                displayName = "#membersonly",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )

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

        try {
            lifecycleService.castVote(
                CastLoanVoteRequest(
                    requestId = opened.requestId,
                    voterPeerId = "peer-3",
                    voteChoice = VoteChoice.YES
                )
            )
            throw AssertionError("vote should fail for non-members")
        } catch (expected: IllegalStateException) {
            assertEquals("membership_not_active", expected.message)
        }
    }

    @Test
    fun disburseApprovedLoan_requiresAdminActor() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#adminonly",
                displayName = "#adminonly",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-3")

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
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-1",
                voteChoice = VoteChoice.YES
            )
        )
        val approved = lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-3",
                voteChoice = VoteChoice.YES
            )
        )
        assertEquals(LoanRequestStatus.APPROVED, approved.request.status)

        try {
            lifecycleService.disburseApprovedLoan(
                DisburseApprovedLoanRequest(
                    requestId = opened.requestId,
                    actorPeerId = "peer-2"
                )
            )
            throw AssertionError("disbursement should fail for non-admins")
        } catch (expected: IllegalStateException) {
            assertEquals("admin_only_disbursement", expected.message)
        }
    }

    @Test
    fun forwardLoanRequest_isDisabledDuringPhaseOne() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#forward-src",
                displayName = "#forward-src",
                creatorPeerId = "peer-admin",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        val destination = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#forward-dst",
                displayName = "#forward-dst",
                creatorPeerId = "peer-dst-admin",
                creatorWalletAddress = "DstWallet111111111111111111111111111111",
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-admin")
        activateMembershipForTest(destination.lendingId, "peer-dst-admin")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-borrower",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-borrower")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = destination.lendingId,
                memberPeerId = "peer-borrower",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(destination.lendingId, "peer-borrower")

        val opened = lifecycleService.createLoanRequest(
            CreateLoanRequest(
                identifier = channel.lendingId,
                requesterPeerId = "peer-borrower",
                borrowerType = BorrowerType.INDIVIDUAL,
                principalAmount = 40_000_000L,
                durationDays = 14,
                purpose = "buy stock for the market stall"
            )
        )

        try {
            lifecycleService.forwardLoanRequest(
                ForwardLoanRequest(
                    requestId = opened.requestId,
                    destinationIdentifier = destination.lendingId,
                    actorPeerId = "peer-admin"
                )
            )
            throw AssertionError("forwarding should be disabled in phase one")
        } catch (expected: IllegalStateException) {
            assertEquals("forwarding_disabled_phase_one", expected.message)
        }
    }

    @Test
    fun importDiscoveredLoanRequest_rejectsUnknownBorrowerOrSenderMismatch() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#import-request",
                displayName = "#import-request",
                creatorPeerId = "peer-admin",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-admin")

        val rejectedForUnknownMember = lifecycleService.importDiscoveredLoanRequest(
            LendingLoanRequestMessage(
                requestId = "REQ-REMOTE-1",
                lendingId = channel.lendingId,
                actorPeerId = "peer-borrower",
                channelDisplayName = channel.displayName,
                principalAmount = 40_000_000L,
                assetSymbol = "USDC",
                assetDecimals = 6,
                durationDays = 14,
                interestBps = DEFAULT_INTEREST_BPS,
                purpose = "restock",
                borrowerPeerId = "peer-borrower",
                borrowerWalletAddress = BORROWER_WALLET
            ),
            senderPeerId = "peer-borrower"
        )
        assertNull(rejectedForUnknownMember)

        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-borrower",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-borrower")

        val rejectedForSenderMismatch = lifecycleService.importDiscoveredLoanRequest(
            LendingLoanRequestMessage(
                requestId = "REQ-REMOTE-2",
                lendingId = channel.lendingId,
                actorPeerId = "peer-borrower",
                channelDisplayName = channel.displayName,
                principalAmount = 40_000_000L,
                assetSymbol = "USDC",
                assetDecimals = 6,
                durationDays = 14,
                interestBps = DEFAULT_INTEREST_BPS,
                purpose = "restock",
                borrowerPeerId = "peer-borrower",
                borrowerWalletAddress = BORROWER_WALLET
            ),
            senderPeerId = "peer-impostor"
        )
        assertNull(rejectedForSenderMismatch)
    }

    @Test
    fun importDiscoveredLoanRequest_ignoresRemoteStatusClaims() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#import-status",
                displayName = "#import-status",
                creatorPeerId = "peer-admin",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-admin")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-borrower",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-borrower")

        val imported = lifecycleService.importDiscoveredLoanRequest(
            LendingLoanRequestMessage(
                requestId = "REQ-REMOTE-3",
                lendingId = channel.lendingId,
                actorPeerId = "peer-borrower",
                channelDisplayName = channel.displayName,
                principalAmount = 40_000_000L,
                assetSymbol = "USDC",
                assetDecimals = 6,
                durationDays = 14,
                interestBps = DEFAULT_INTEREST_BPS,
                purpose = "restock",
                borrowerPeerId = "peer-borrower",
                borrowerWalletAddress = BORROWER_WALLET,
                status = LoanRequestStatus.DISBURSED
            ),
            senderPeerId = "peer-borrower"
        )
        assertEquals(LoanRequestStatus.PENDING, imported?.status)

        val updated = lifecycleService.importDiscoveredLoanRequest(
            LendingLoanRequestMessage(
                requestId = "REQ-REMOTE-3",
                lendingId = channel.lendingId,
                actorPeerId = "peer-borrower",
                channelDisplayName = channel.displayName,
                principalAmount = 99_000_000L,
                assetSymbol = "USDC",
                assetDecimals = 6,
                durationDays = 30,
                interestBps = DEFAULT_INTEREST_BPS + 100,
                purpose = "restock urgently",
                borrowerPeerId = "peer-borrower",
                borrowerWalletAddress = BORROWER_WALLET,
                status = LoanRequestStatus.REPAID
            ),
            senderPeerId = "peer-borrower"
        )
        assertEquals(LoanRequestStatus.PENDING, updated?.status)
        assertEquals(40_000_000L, updated?.principalAmount)
        assertEquals(14, updated?.durationDays)
        assertEquals(DEFAULT_INTEREST_BPS, updated?.interestBps)
        assertEquals("restock", updated?.purpose)
    }

    @Test
    fun importDiscoveredLoanVote_rejectsNonMemberOrBorrowerVote() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#import-vote",
                displayName = "#import-vote",
                creatorPeerId = "peer-admin",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-admin")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-borrower",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-borrower")

        val opened = lifecycleService.createLoanRequest(
            CreateLoanRequest(
                identifier = channel.lendingId,
                requesterPeerId = "peer-borrower",
                borrowerType = BorrowerType.INDIVIDUAL,
                principalAmount = 40_000_000L,
                durationDays = 14,
                purpose = "buy stock for the market stall"
            )
        )

        val borrowerVote = lifecycleService.importDiscoveredLoanVote(
            LendingLoanVoteMessage(
                requestId = opened.requestId,
                lendingId = channel.lendingId,
                voterPeerId = "peer-borrower",
                voteChoice = VoteChoice.YES,
                yesVotes = 1,
                noVotes = 0,
                requestStatus = LoanRequestStatus.APPROVED
            ),
            senderPeerId = "peer-borrower"
        )
        assertNull(borrowerVote)

        val nonMemberVote = lifecycleService.importDiscoveredLoanVote(
            LendingLoanVoteMessage(
                requestId = opened.requestId,
                lendingId = channel.lendingId,
                voterPeerId = "peer-outsider",
                voteChoice = VoteChoice.YES,
                yesVotes = 1,
                noVotes = 0,
                requestStatus = LoanRequestStatus.APPROVED
            ),
            senderPeerId = "peer-outsider"
        )
        assertNull(nonMemberVote)
    }

    @Test
    fun importDiscoveredLoanVote_recomputesApprovalFromLocalVotes() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#import-vote-status",
                displayName = "#import-vote-status",
                creatorPeerId = "peer-admin",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-admin")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-borrower",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-borrower")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-voter",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-voter")

        val opened = lifecycleService.createLoanRequest(
            CreateLoanRequest(
                identifier = channel.lendingId,
                requesterPeerId = "peer-borrower",
                borrowerType = BorrowerType.INDIVIDUAL,
                principalAmount = 40_000_000L,
                durationDays = 14,
                purpose = "buy stock for the market stall"
            )
        )

        val firstImportedVote = lifecycleService.importDiscoveredLoanVote(
            LendingLoanVoteMessage(
                requestId = opened.requestId,
                lendingId = channel.lendingId,
                voterPeerId = "peer-admin",
                voteChoice = VoteChoice.YES,
                yesVotes = 1,
                noVotes = 0,
                requestStatus = LoanRequestStatus.APPROVED
            ),
            senderPeerId = "peer-admin"
        )
        assertEquals(LoanRequestStatus.PENDING, firstImportedVote?.status)

        val secondImportedVote = lifecycleService.importDiscoveredLoanVote(
            LendingLoanVoteMessage(
                requestId = opened.requestId,
                lendingId = channel.lendingId,
                voterPeerId = "peer-voter",
                voteChoice = VoteChoice.YES,
                yesVotes = 2,
                noVotes = 0,
                requestStatus = LoanRequestStatus.APPROVED
            ),
            senderPeerId = "peer-voter"
        )
        assertEquals(LoanRequestStatus.APPROVED, secondImportedVote?.status)
    }

    @Test
    fun importDiscoveredLoanVote_ignoresLateVotesAfterVotingCloses() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#late-vote",
                displayName = "#late-vote",
                creatorPeerId = "peer-admin",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-admin")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-borrower",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-borrower")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-voter",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-voter")

        val opened = lifecycleService.createLoanRequest(
            CreateLoanRequest(
                identifier = channel.lendingId,
                requesterPeerId = "peer-borrower",
                borrowerType = BorrowerType.INDIVIDUAL,
                principalAmount = 40_000_000L,
                durationDays = 14,
                purpose = "buy stock for the market stall"
            )
        )
        val expiredRequest = opened.copy(
            requestedAt = System.currentTimeMillis() - (25L * 60L * 60L * 1000L),
            dueAt = System.currentTimeMillis() + (14L * 24L * 60L * 60L * 1000L)
        )
        database.lendingDao().upsertLoanRequest(expiredRequest)

        val lateVote = lifecycleService.importDiscoveredLoanVote(
            LendingLoanVoteMessage(
                requestId = opened.requestId,
                lendingId = channel.lendingId,
                voterPeerId = "peer-admin",
                voteChoice = VoteChoice.YES,
                yesVotes = 1,
                noVotes = 0,
                requestStatus = LoanRequestStatus.APPROVED
            ),
            senderPeerId = "peer-admin"
        )
        assertEquals(LoanRequestStatus.REJECTED, lateVote?.status)
        val storedVotes = database.lendingDao().getVotesForRequest(opened.requestId)
        assertTrue(storedVotes.isEmpty())
    }

    @Test
    fun importDiscoveredLoanRepayment_rejectsSenderMismatchAndNonBorrower() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#import-repay",
                displayName = "#import-repay",
                creatorPeerId = "peer-admin",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-admin")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-borrower",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-borrower")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-voter",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-voter")

        val opened = lifecycleService.createLoanRequest(
            CreateLoanRequest(
                identifier = channel.lendingId,
                requesterPeerId = "peer-borrower",
                borrowerType = BorrowerType.INDIVIDUAL,
                principalAmount = 40_000_000L,
                durationDays = 14,
                purpose = "buy stock for the market stall"
            )
        )
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-admin",
                voteChoice = VoteChoice.YES
            )
        )
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-voter",
                voteChoice = VoteChoice.YES
            )
        )
        lifecycleService.disburseApprovedLoan(
            DisburseApprovedLoanRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-admin"
            )
        )

        val senderMismatch = lifecycleService.importDiscoveredLoanRepayment(
            LendingLoanRepaymentMessage(
                repaymentId = "REP-1",
                requestId = opened.requestId,
                lendingId = channel.lendingId,
                payerPeerId = "peer-borrower",
                amount = 42_000_000L,
                txSignature = "repay-sig",
                txStatus = EscrowTransferStatus.CONFIRMED,
                totalRepaidAmount = 42_000_000L,
                remainingBalance = 0L,
                requestStatus = LoanRequestStatus.REPAID,
                paidAt = System.currentTimeMillis()
            ),
            senderPeerId = "peer-impostor"
        )
        assertNull(senderMismatch)

        val nonBorrowerRepayment = lifecycleService.importDiscoveredLoanRepayment(
            LendingLoanRepaymentMessage(
                repaymentId = "REP-2",
                requestId = opened.requestId,
                lendingId = channel.lendingId,
                payerPeerId = "peer-voter",
                amount = 42_000_000L,
                txSignature = "repay-sig",
                txStatus = EscrowTransferStatus.CONFIRMED,
                totalRepaidAmount = 42_000_000L,
                remainingBalance = 0L,
                requestStatus = LoanRequestStatus.REPAID,
                paidAt = System.currentTimeMillis()
            ),
            senderPeerId = "peer-voter"
        )
        assertNull(nonBorrowerRepayment)
    }

    @Test
    fun importDiscoveredLoanRepayment_doesNotAdvanceStatusOrConfirmRepayment() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#import-repay-status",
                displayName = "#import-repay-status",
                creatorPeerId = "peer-admin",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-admin")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-borrower",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-borrower")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-voter",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-voter")

        val opened = lifecycleService.createLoanRequest(
            CreateLoanRequest(
                identifier = channel.lendingId,
                requesterPeerId = "peer-borrower",
                borrowerType = BorrowerType.INDIVIDUAL,
                principalAmount = 40_000_000L,
                durationDays = 14,
                purpose = "buy stock for the market stall"
            )
        )
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-admin",
                voteChoice = VoteChoice.YES
            )
        )
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-voter",
                voteChoice = VoteChoice.YES
            )
        )
        lifecycleService.disburseApprovedLoan(
            DisburseApprovedLoanRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-admin"
            )
        )

        val imported = lifecycleService.importDiscoveredLoanRepayment(
            LendingLoanRepaymentMessage(
                repaymentId = "REP-REMOTE-1",
                requestId = opened.requestId,
                lendingId = channel.lendingId,
                payerPeerId = "peer-borrower",
                amount = 42_000_000L,
                txSignature = "repay-sig",
                txStatus = EscrowTransferStatus.CONFIRMED,
                totalRepaidAmount = 42_000_000L,
                remainingBalance = 0L,
                requestStatus = LoanRequestStatus.REPAID,
                paidAt = System.currentTimeMillis()
            ),
            senderPeerId = "peer-borrower"
        )

        assertEquals(LoanRequestStatus.DISBURSED, imported?.updatedRequest?.status)
        assertEquals(EscrowTransferStatus.PENDING, imported?.repayment?.txStatus)
        assertEquals(0L, imported?.totalRepaidAmount)
        val storedLoan = database.lendingDao().getLoanRequestById(opened.requestId)
        assertEquals(LoanRequestStatus.DISBURSED, storedLoan?.status)
        val storedRepayment = database.lendingDao().getRepaymentsForRequest(opened.requestId).single()
        assertEquals(EscrowTransferStatus.PENDING, storedRepayment.txStatus)
    }

    @Test
    fun importDiscoveredLoanRepayment_confirmsCanonicalSignatureAndDeduplicatesByOnchainSignature() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#canonical-repay",
                displayName = "#canonical-repay",
                creatorPeerId = "peer-admin",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-admin")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-borrower",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-borrower")

        val opened = lifecycleService.createLoanRequest(
            CreateLoanRequest(
                identifier = channel.lendingId,
                requesterPeerId = "peer-borrower",
                borrowerType = BorrowerType.INDIVIDUAL,
                principalAmount = 40_000_000L,
                durationDays = 14,
                purpose = "buy stock for the market stall"
            )
        )
        database.lendingDao().upsertLoanRequest(
            opened.copy(
                status = LoanRequestStatus.DISBURSED,
                disbursedAt = System.currentTimeMillis()
            )
        )

        val onchainSignature = canonicalSignature()
        val firstImport = lifecycleService.importDiscoveredLoanRepayment(
            LendingLoanRepaymentMessage(
                repaymentId = "REP-CANON-1",
                requestId = opened.requestId,
                lendingId = channel.lendingId,
                payerPeerId = "peer-borrower",
                amount = 21_000_000L,
                txSignature = onchainSignature,
                txStatus = EscrowTransferStatus.CONFIRMED,
                totalRepaidAmount = 21_000_000L,
                remainingBalance = 21_000_000L,
                requestStatus = LoanRequestStatus.PARTIALLY_REPAID,
                paidAt = System.currentTimeMillis()
            ),
            senderPeerId = "peer-borrower"
        )

        assertEquals(EscrowTransferStatus.CONFIRMED, firstImport?.repayment?.txStatus)
        assertEquals(LoanRequestStatus.PARTIALLY_REPAID, firstImport?.updatedRequest?.status)
        assertEquals(21_000_000L, firstImport?.totalRepaidAmount)

        val duplicateImport = lifecycleService.importDiscoveredLoanRepayment(
            LendingLoanRepaymentMessage(
                repaymentId = "REP-CANON-2",
                requestId = opened.requestId,
                lendingId = channel.lendingId,
                payerPeerId = "peer-borrower",
                amount = 21_000_000L,
                txSignature = onchainSignature,
                txStatus = EscrowTransferStatus.CONFIRMED,
                totalRepaidAmount = 42_000_000L,
                remainingBalance = 0L,
                requestStatus = LoanRequestStatus.REPAID,
                paidAt = System.currentTimeMillis()
            ),
            senderPeerId = "peer-borrower"
        )

        assertEquals(21_000_000L, duplicateImport?.totalRepaidAmount)
        val repayments = database.lendingDao().getRepaymentsForRequest(opened.requestId)
        assertEquals(1, repayments.size)
        assertEquals(EscrowTransferStatus.CONFIRMED, repayments.single().txStatus)
        val storedLoan = database.lendingDao().getLoanRequestById(opened.requestId)
        assertEquals(LoanRequestStatus.PARTIALLY_REPAID, storedLoan?.status)
    }

    @Test
    fun importDiscoveredLoanRepayment_recoversDefaultedLoanWhenOutstandingIsFullyPaid() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#default-repay",
                displayName = "#default-repay",
                creatorPeerId = "peer-admin",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-admin")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-borrower",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-borrower")

        val opened = lifecycleService.createLoanRequest(
            CreateLoanRequest(
                identifier = channel.lendingId,
                requesterPeerId = "peer-borrower",
                borrowerType = BorrowerType.INDIVIDUAL,
                principalAmount = 40_000_000L,
                durationDays = 14,
                purpose = "buy stock for the market stall"
            )
        )
        val defaulted = opened.copy(
            status = LoanRequestStatus.DEFAULTED,
            disbursedAt = System.currentTimeMillis() - 20_000L,
            dueAt = System.currentTimeMillis() - 10_000L,
            defaultedAt = System.currentTimeMillis() - 5_000L
        )
        database.lendingDao().upsertLoanRequest(defaulted)

        val imported = lifecycleService.importDiscoveredLoanRepayment(
            LendingLoanRepaymentMessage(
                repaymentId = "REP-DEFAULT-CURE",
                requestId = opened.requestId,
                lendingId = channel.lendingId,
                payerPeerId = "peer-borrower",
                amount = 42_000_000L,
                txSignature = canonicalSignature('B'),
                txStatus = EscrowTransferStatus.CONFIRMED,
                totalRepaidAmount = 42_000_000L,
                remainingBalance = 0L,
                requestStatus = LoanRequestStatus.REPAID,
                paidAt = System.currentTimeMillis()
            ),
            senderPeerId = "peer-borrower"
        )

        assertEquals(LoanRequestStatus.REPAID, imported?.updatedRequest?.status)
        val storedLoan = database.lendingDao().getLoanRequestById(opened.requestId)
        assertEquals(LoanRequestStatus.REPAID, storedLoan?.status)
    }

    @Test
    fun repayLoan_requiresSharedCustodyAndRejectsOverpaymentBeyondOutstandingBalance() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#repay-guardrails",
                displayName = "#repay-guardrails",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")

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
        database.lendingDao().upsertLoanRequest(
            opened.copy(
                status = LoanRequestStatus.DISBURSED,
                disbursedAt = System.currentTimeMillis(),
                squadsMultisigAddress = "squad-multisig",
                squadsVaultAddress = VAULT_WALLET
            )
        )

        try {
            lifecycleService.repayLoan(
                RecordLoanRepaymentRequest(
                    requestId = opened.requestId,
                    payerPeerId = "peer-2",
                    amount = 1_000_000L
                )
            )
            throw AssertionError("repayment should require shared custody")
        } catch (expected: IllegalStateException) {
            assertEquals("shared_custody_required", expected.message)
        }

        val productionLifecycleService = sharedCustodyLifecycleService()

        try {
            productionLifecycleService.repayLoan(
                RecordLoanRepaymentRequest(
                    requestId = opened.requestId,
                    payerPeerId = "peer-2",
                    amount = 42_000_001L
                )
            )
            throw AssertionError("repayment should reject overpayment")
        } catch (expected: IllegalArgumentException) {
            assertEquals("repayment_amount_exceeds_outstanding_balance", expected.message)
        }
    }

    @Test
    fun disburseApprovedLoan_retriesAfterFailedProposal() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#retry-disburse",
                displayName = "#retry-disburse",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-3")

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
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-1",
                voteChoice = VoteChoice.YES
            )
        )
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-3",
                voteChoice = VoteChoice.YES
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
        ).thenReturn(Result.failure(IllegalStateException("temporary_disbursement_failure")))

        val failedAttempt = lifecycleService.disburseApprovedLoan(
            DisburseApprovedLoanRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-1"
            )
        )
        assertEquals(LoanRequestStatus.APPROVED, failedAttempt.status)
        val proposalsAfterFailure = escrowService.getEscrowProposalsForRequest(opened.requestId)
        assertEquals(1, proposalsAfterFailure.size)
        assertEquals(CustodyExecutionStatus.FAILED, proposalsAfterFailure.single().custodyExecutionStatus)

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
        ).thenReturn(Result.success("spl-disbursement-retry"))

        val successfulRetry = lifecycleService.disburseApprovedLoan(
            DisburseApprovedLoanRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-1"
            )
        )
        assertEquals(LoanRequestStatus.DISBURSED, successfulRetry.status)
        val proposalsAfterRetry = escrowService.getEscrowProposalsForRequest(opened.requestId)
        assertEquals(2, proposalsAfterRetry.size)
        assertTrue(proposalsAfterRetry.any { it.custodyExecutionStatus == CustodyExecutionStatus.FAILED })
        assertTrue(proposalsAfterRetry.any { it.custodyExecutionStatus == CustodyExecutionStatus.EXECUTED })
        assertNotEquals(proposalsAfterRetry[0].proposalId, proposalsAfterRetry[1].proposalId)
    }

    @Test
    fun pendingRepayment_doesNotIncreaseEscrowPoolLiquidity() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#pending-repay",
                displayName = "#pending-repay",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-3")

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
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-1",
                voteChoice = VoteChoice.YES
            )
        )
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-3",
                voteChoice = VoteChoice.YES
            )
        )
        lifecycleService.disburseApprovedLoan(
            DisburseApprovedLoanRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-1"
            )
        )

        database.lendingDao().insertLoanRepayment(
            com.bitchat.android.data.local.entities.LoanRepaymentEntity(
                repaymentId = "REP-PENDING-1",
                requestId = opened.requestId,
                lendingId = channel.lendingId,
                amount = 42_000_000L,
                txSignature = "pending-repay-sig",
                txStatus = EscrowTransferStatus.PENDING
            )
        )

        escrowService.releaseMembershipStake(channel.lendingId, "peer-3")

        val snapshot = database.lendingDao().getPoolSnapshot(channel.lendingId)
        assertEquals(60_000_000L, snapshot?.availableLiquidityAmount)
        assertEquals(40_000_000L, snapshot?.disbursedAmount)
    }

    @Test
    fun repayLoan_rejectsApprovedButUndisbursedLoan() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#undisbursed",
                displayName = "#undisbursed",
                creatorPeerId = "peer-1",
                creatorWalletAddress = CREATOR_WALLET,
                requiredStakeAmount = 50_000_000L,
                stakeTokenMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                stakeTokenSymbol = "USDC"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-1")
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-2",
                walletAddress = BORROWER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 82,
                credibilitySnapshotJson = "{}"
            )
        )
        channelService.recordPendingMembership(
            RecordPendingMembershipRequest(
                lendingId = channel.lendingId,
                memberPeerId = "peer-3",
                walletAddress = MEMBER_WALLET,
                stakeAmount = 50_000_000L,
                credibilityScore = 70,
                credibilitySnapshotJson = "{}"
            )
        )
        activateMembershipForTest(channel.lendingId, "peer-2")
        activateMembershipForTest(channel.lendingId, "peer-3")

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
        lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-1",
                voteChoice = VoteChoice.YES
            )
        )
        val approved = lifecycleService.castVote(
            CastLoanVoteRequest(
                requestId = opened.requestId,
                voterPeerId = "peer-3",
                voteChoice = VoteChoice.YES
            )
        )
        assertEquals(LoanRequestStatus.APPROVED, approved.request.status)

        try {
            lifecycleService.repayLoan(
                RecordLoanRepaymentRequest(
                    requestId = opened.requestId,
                    payerPeerId = "peer-2",
                    amount = 42_000_000L
                )
            )
            throw AssertionError("approved but undisbursed loan should not be repayable")
        } catch (expected: IllegalStateException) {
            assertEquals("loan_request_not_repayable", expected.message)
        }
    }

    @Test
    fun nativeSolApproval_disbursesUsingTreasuryTransfer() = runBlocking {
        val channel = channelService.createLocalChannel(
            CreateLendingChannelRequest(
                channelKey = "mesh:#solfund",
                displayName = "#solfund",
                creatorPeerId = "peer-a",
                creatorWalletAddress = CREATOR_WALLET,
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
                walletAddress = CREATOR_WALLET,
                stakeAmount = channel.requiredStakeAmount,
                depositStatus = EscrowTransferStatus.CONFIRMED,
                joinStatus = LendingMemberStatus.ACTIVE
            )
        )
        database.lendingDao().upsertMembership(
            com.bitchat.android.data.local.entities.LendingMembershipEntity(
                lendingId = channel.lendingId,
                memberPeerId = "peer-b",
                walletAddress = BORROWER_WALLET,
                stakeAmount = channel.requiredStakeAmount,
                depositStatus = EscrowTransferStatus.CONFIRMED,
                joinStatus = LendingMemberStatus.ACTIVE
            )
        )
        database.lendingDao().upsertMembership(
            com.bitchat.android.data.local.entities.LendingMembershipEntity(
                lendingId = channel.lendingId,
                memberPeerId = "peer-c",
                walletAddress = MEMBER_WALLET,
                stakeAmount = channel.requiredStakeAmount,
                depositStatus = EscrowTransferStatus.CONFIRMED,
                joinStatus = LendingMemberStatus.ACTIVE
            )
        )
        database.lendingDao().upsertPoolSnapshot(
            com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity(
                lendingId = channel.lendingId,
                totalStakedAmount = channel.requiredStakeAmount * 3,
                availableLiquidityAmount = channel.requiredStakeAmount * 3
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
                voterPeerId = "peer-c",
                voteChoice = VoteChoice.YES
            )
        )

        assertEquals(LoanRequestStatus.APPROVED, result.request.status)
        val disbursed = lifecycleService.disburseApprovedLoan(
            DisburseApprovedLoanRequest(
                requestId = opened.requestId,
                actorPeerId = "peer-a"
            )
        )
        assertEquals(LoanRequestStatus.DISBURSED, disbursed.status)
        assertTrue(escrowService.getEscrowProposalsForRequest(opened.requestId).isNotEmpty())
    }

}
