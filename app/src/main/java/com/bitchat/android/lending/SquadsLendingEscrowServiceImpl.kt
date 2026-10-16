package com.bitchat.android.lending

import com.bitchat.android.data.local.LendingDao
import com.bitchat.android.data.local.TransactionDao
import com.bitchat.android.data.local.entities.AppProposalStatus
import com.bitchat.android.data.local.entities.BorrowerType
import com.bitchat.android.data.local.entities.CustodyExecutionStatus
import com.bitchat.android.data.local.entities.EscrowCustodyState
import com.bitchat.android.data.local.entities.EscrowProposalType
import com.bitchat.android.data.local.entities.EscrowProvider
import com.bitchat.android.data.local.entities.EscrowTransferStatus
import com.bitchat.android.data.local.entities.LendingChannelEntity
import com.bitchat.android.data.local.entities.LendingEscrowAccountEntity
import com.bitchat.android.data.local.entities.LendingEscrowProposalEntity
import com.bitchat.android.data.local.entities.LendingMemberStatus
import com.bitchat.android.data.local.entities.LendingMembershipEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import com.bitchat.android.data.local.entities.LoanRequestEntity
import com.bitchat.android.solana.LendingTransferGateway
import com.bitchat.android.data.models.TransactionStatus
import com.bitchat.android.solana.SolanaTokenAccountUtils
import kotlin.math.pow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SquadsLendingEscrowServiceImpl @Inject constructor(
    private val lendingDao: LendingDao,
    private val transferGateway: LendingTransferGateway,
    private val transactionDao: TransactionDao,
    private val treasuryKeyStore: LendingTreasuryKeyStore,
    private val treasuryTransferService: LendingTreasuryTransferService,
    private val squadsService: SquadsService
) : LendingEscrowService {

    override suspend fun getMemberships(lendingId: String): List<LendingMembershipEntity> {
        return lendingDao.getMembershipsForLendingChannel(lendingId)
    }

    override suspend fun getPoolSnapshot(lendingId: String): LendingPoolSnapshotEntity? {
        return lendingDao.getPoolSnapshot(lendingId)
    }

    override suspend fun activateMembership(lendingId: String, memberPeerId: String): LendingMembershipEntity {
        if (isNativeSolChannel(getChannel(lendingId))) {
            prepareStakeDeposit(lendingId, memberPeerId)
            return lendingDao.getMembership(lendingId, memberPeerId)
                ?: throw IllegalArgumentException("membership_not_found")
        }

        val account = provisionChannelEscrow(lendingId)
        val channel = getChannel(lendingId)
        val existing = lendingDao.getMembership(lendingId, memberPeerId)
            ?: throw IllegalArgumentException("membership_not_found")
        if (existing.joinStatus == LendingMemberStatus.ACTIVE &&
            existing.depositStatus == EscrowTransferStatus.CONFIRMED
        ) {
            return existing
        }

        val proposal = lendingDao.getEscrowProposalsForLendingChannel(lendingId)
            .firstOrNull {
                it.memberPeerId == memberPeerId && it.proposalType == EscrowProposalType.STAKE_DEPOSIT
            } ?: LendingEscrowProposalEntity(
                proposalId = nextProposalId(),
                lendingId = lendingId,
                memberPeerId = memberPeerId,
                proposalType = EscrowProposalType.STAKE_DEPOSIT,
                appApprovalStatus = AppProposalStatus.APPROVED,
                custodyExecutionStatus = CustodyExecutionStatus.CREATED,
                targetWalletAddress = account.vaultAddress,
                mintAddress = channel.stakeTokenMint,
                amountAtomic = existing.stakeAmount
            )
        val executedProposal = executeProposal(proposal, channel, account)
        val updated = existing.copy(
            depositStatus = when (executedProposal.custodyExecutionStatus) {
                CustodyExecutionStatus.EXECUTED -> EscrowTransferStatus.CONFIRMED
                CustodyExecutionStatus.FAILED -> EscrowTransferStatus.FAILED
                else -> EscrowTransferStatus.PENDING
            },
            joinStatus = when (executedProposal.custodyExecutionStatus) {
                CustodyExecutionStatus.EXECUTED -> LendingMemberStatus.ACTIVE
                CustodyExecutionStatus.FAILED -> LendingMemberStatus.PENDING
                else -> LendingMemberStatus.PENDING
            },
            updatedAt = System.currentTimeMillis()
        )
        lendingDao.upsertMembership(updated)
        if (executedProposal.custodyExecutionStatus == CustodyExecutionStatus.FAILED) {
            throw IllegalStateException(executedProposal.errorMessage ?: "stake_transfer_failed")
        }
        if (updated.joinStatus == LendingMemberStatus.ACTIVE &&
            updated.depositStatus == EscrowTransferStatus.CONFIRMED
        ) {
            refreshPoolSnapshot(lendingId)
        }
        return updated
    }

    override suspend fun prepareStakeDeposit(lendingId: String, memberPeerId: String): LendingStakeApprovalRequest {
        val channel = getChannel(lendingId)
        val account = provisionChannelEscrow(lendingId)
        val membership = lendingDao.getMembership(lendingId, memberPeerId)
            ?: throw IllegalArgumentException("membership_not_found")
        if (membership.joinStatus == LendingMemberStatus.ACTIVE &&
            membership.depositStatus == EscrowTransferStatus.CONFIRMED
        ) {
            throw IllegalStateException("stake_already_confirmed")
        }

        val proposal = getOrCreateStakeDepositProposal(lendingId, memberPeerId, channel, account, membership)
        if (proposal.custodyExecutionStatus == CustodyExecutionStatus.FAILED) {
            lendingDao.upsertEscrowProposal(
                proposal.copy(
                    custodyExecutionStatus = CustodyExecutionStatus.CREATED,
                    errorMessage = null,
                    txSignature = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        val pendingMembership = membership.copy(
            depositStatus = EscrowTransferStatus.PENDING,
            joinStatus = LendingMemberStatus.PENDING,
            updatedAt = System.currentTimeMillis()
        )
        lendingDao.upsertMembership(pendingMembership)

        return LendingStakeApprovalRequest(
            lendingId = lendingId,
            memberPeerId = memberPeerId,
            channelDisplayName = channel.displayName,
            actionLabel = if (channel.creatorPeerId == memberPeerId) "Create lending channel stake" else "Join lending channel stake",
            treasuryAddress = account.vaultAddress,
            amountAtomic = requiredJoinDebitAmount(channel),
            decimals = channel.stakeTokenDecimals,
            symbol = channel.stakeTokenSymbol.ifBlank { if (isNativeSolChannel(channel)) NATIVE_SOL_ASSET else "TOKEN" },
            assetDescriptor = if (isNativeSolChannel(channel)) "Devnet SOL" else channel.stakeTokenMint
        )
    }

    override suspend fun submitStakeDeposit(lendingId: String, memberPeerId: String): LendingMembershipEntity {
        val account = provisionChannelEscrow(lendingId)
        val channel = getChannel(lendingId)
        val membership = lendingDao.getMembership(lendingId, memberPeerId)
            ?: throw IllegalArgumentException("membership_not_found")
        val proposal = getOrCreateStakeDepositProposal(lendingId, memberPeerId, channel, account, membership)
        if (proposal.txSignature != null && proposal.custodyExecutionStatus != CustodyExecutionStatus.FAILED) {
            return membership.copy(
                depositStatus = EscrowTransferStatus.PENDING,
                joinStatus = LendingMemberStatus.PENDING
            )
        }

        val executedProposal = executeProposal(
            proposal.copy(
                custodyExecutionStatus = CustodyExecutionStatus.CREATED,
                txSignature = null,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            ),
            channel,
            account
        )
        val updatedMembership = membership.copy(
            depositStatus = when (executedProposal.custodyExecutionStatus) {
                CustodyExecutionStatus.EXECUTED -> EscrowTransferStatus.CONFIRMED
                CustodyExecutionStatus.FAILED -> EscrowTransferStatus.FAILED
                else -> EscrowTransferStatus.PENDING
            },
            joinStatus = when (executedProposal.custodyExecutionStatus) {
                CustodyExecutionStatus.EXECUTED -> LendingMemberStatus.ACTIVE
                CustodyExecutionStatus.FAILED -> LendingMemberStatus.PENDING
                else -> LendingMemberStatus.PENDING
            },
            updatedAt = System.currentTimeMillis()
        )
        lendingDao.upsertMembership(updatedMembership)
        if (executedProposal.custodyExecutionStatus == CustodyExecutionStatus.FAILED) {
            throw IllegalStateException(executedProposal.errorMessage ?: "stake_transfer_failed")
        }
        if (updatedMembership.joinStatus == LendingMemberStatus.ACTIVE &&
            updatedMembership.depositStatus == EscrowTransferStatus.CONFIRMED
        ) {
            refreshPoolSnapshot(lendingId)
        }
        return updatedMembership
    }

    override suspend fun repairMembershipState(lendingId: String, memberPeerId: String): LendingMembershipEntity? {
        val membership = lendingDao.getMembership(lendingId, memberPeerId) ?: return null
        val proposal = lendingDao.getEscrowProposalsForLendingChannel(lendingId)
            .firstOrNull {
                it.memberPeerId == memberPeerId && it.proposalType == EscrowProposalType.STAKE_DEPOSIT
            } ?: return membership

        val repairedProposal = repairStakeProposalFromTransaction(proposal)
        val nextMembership = when (repairedProposal.custodyExecutionStatus) {
            CustodyExecutionStatus.EXECUTED -> membership.copy(
                depositStatus = EscrowTransferStatus.CONFIRMED,
                joinStatus = LendingMemberStatus.ACTIVE,
                updatedAt = System.currentTimeMillis()
            )
            CustodyExecutionStatus.FAILED -> membership.copy(
                depositStatus = EscrowTransferStatus.FAILED,
                joinStatus = LendingMemberStatus.PENDING,
                updatedAt = System.currentTimeMillis()
            )
            else -> membership.copy(
                depositStatus = EscrowTransferStatus.PENDING,
                joinStatus = LendingMemberStatus.PENDING,
                updatedAt = membership.updatedAt
            )
        }
        if (nextMembership != membership) {
            lendingDao.upsertMembership(nextMembership)
        }
        if (nextMembership.joinStatus == LendingMemberStatus.ACTIVE &&
            nextMembership.depositStatus == EscrowTransferStatus.CONFIRMED
        ) {
            refreshPoolSnapshot(lendingId)
        }
        return nextMembership
    }

    override suspend fun repairMembershipsForTransaction(
        queuedTransactionId: String,
        txSignature: String?
    ): List<LendingMembershipEntity> {
        val txReferences = buildList {
            add(queuedTransactionId)
            if (!txSignature.isNullOrBlank()) add(txSignature)
        }.distinct()
        if (txReferences.isEmpty()) return emptyList()

        val repairedMemberships = mutableListOf<LendingMembershipEntity>()
        val seenMemberships = mutableSetOf<String>()
        val proposals = txReferences
            .flatMap { lendingDao.getEscrowProposalsByTxReference(it) }
            .distinctBy { it.proposalId }

        for (proposal in proposals) {
            val memberPeerId = proposal.memberPeerId ?: continue
            val repaired = repairMembershipState(proposal.lendingId, memberPeerId) ?: continue
            val membershipKey = "${repaired.lendingId}:${repaired.memberPeerId}"
            if (seenMemberships.add(membershipKey)) {
                repairedMemberships += repaired
            }
        }
        return repairedMemberships
    }

    override suspend fun releaseMembershipStake(lendingId: String, memberPeerId: String): LendingMembershipEntity {
        val existing = lendingDao.getMembership(lendingId, memberPeerId)
            ?: throw IllegalArgumentException("membership_not_found")
        val updated = existing.copy(
            depositStatus = EscrowTransferStatus.RELEASED,
            joinStatus = LendingMemberStatus.EXITED,
            updatedAt = System.currentTimeMillis()
        )
        lendingDao.upsertMembership(updated)
        refreshPoolSnapshot(lendingId)
        return updated
    }

    override suspend fun provisionChannelEscrow(lendingId: String): LendingEscrowAccountEntity {
        lendingDao.getEscrowAccount(lendingId)?.let { return it }
        val channel = getChannel(lendingId)
        val account = squadsService.resolveLendingSquad(lendingId)
            .map { squad ->
                LendingEscrowAccountEntity(
                    lendingId = lendingId,
                    multisigAddress = squad.multisigAddress,
                    vaultAddress = squad.vaultAddress,
                    vaultTokenAccountAddress = squad.vaultTokenAccountAddress,
                    provider = EscrowProvider.SQUADS,
                    custodyState = EscrowCustodyState.ACTIVE
                )
            }
            .getOrElse {
                val treasuryWallet = treasuryKeyStore.ensureTreasuryWallet(lendingId)
                val isNativeSol = isNativeSolChannel(channel)
                LendingEscrowAccountEntity(
                    lendingId = lendingId,
                    multisigAddress = channel.escrowMultisigAddress,
                    vaultAddress = treasuryWallet.publicKeyBase58,
                    vaultTokenAccountAddress = if (isNativeSol) {
                        ""
                    } else {
                        SolanaTokenAccountUtils.findAssociatedTokenAddress(
                            treasuryWallet.publicKeyBase58,
                            channel.stakeTokenMint
                        )
                    },
                    provider = EscrowProvider.APP_TREASURY,
                    custodyState = if (channel.escrowMultisigAddress.isBlank()) {
                        EscrowCustodyState.PENDING_MULTISIG
                    } else {
                        EscrowCustodyState.ACTIVE
                    },
                    pendingMigrationMultisigAddress = channel.escrowMultisigAddress
                )
            }
        lendingDao.upsertEscrowAccount(account)
        if (channel.escrowMultisigAddress != account.multisigAddress) {
            lendingDao.insertLendingChannel(
                channel.copy(
                    escrowMultisigAddress = account.multisigAddress,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        return account
    }

    override suspend fun getEscrowAccount(lendingId: String): LendingEscrowAccountEntity? {
        return lendingDao.getEscrowAccount(lendingId)
    }

    override suspend fun getEscrowProposalsForRequest(requestId: String): List<LendingEscrowProposalEntity> {
        return lendingDao.getEscrowProposalsForRequest(requestId)
    }

    override suspend fun createLoanDisbursementProposal(requestId: String): LendingEscrowProposalEntity {
        lendingDao.getEscrowProposalsForRequest(requestId)
            .firstOrNull {
                it.proposalType == EscrowProposalType.LOAN_DISBURSEMENT &&
                    it.custodyExecutionStatus != CustodyExecutionStatus.FAILED
            }
            ?.let { return it }

        val loan = lendingDao.getLoanRequestById(requestId)
            ?: throw IllegalArgumentException("loan_request_not_found")
        val channel = getChannel(loan.lendingId)
        val account = provisionChannelEscrow(loan.lendingId)
        val targetWallet = resolveLoanRecipientWallet(loan, channel)
        val proposal = LendingEscrowProposalEntity(
            proposalId = nextProposalId(),
            lendingId = loan.lendingId,
            requestId = loan.requestId,
            proposalType = EscrowProposalType.LOAN_DISBURSEMENT,
            appApprovalStatus = AppProposalStatus.APPROVED,
            custodyExecutionStatus = CustodyExecutionStatus.CREATED,
            targetWalletAddress = targetWallet,
            mintAddress = channel.stakeTokenMint,
            amountAtomic = loan.principalAmount
        )
        return executeProposal(proposal, channel, account)
    }

    override suspend fun createStakeReleaseProposal(
        lendingId: String,
        memberPeerId: String
    ): LendingEscrowProposalEntity {
        lendingDao.getEscrowProposalsForLendingChannel(lendingId)
            .firstOrNull {
                it.memberPeerId == memberPeerId && it.proposalType == EscrowProposalType.STAKE_RELEASE &&
                    it.custodyExecutionStatus != CustodyExecutionStatus.FAILED
            }?.let { return it }

        val channel = getChannel(lendingId)
        val membership = lendingDao.getMembership(lendingId, memberPeerId)
            ?: throw IllegalArgumentException("membership_not_found")
        val account = provisionChannelEscrow(lendingId)
        val proposal = LendingEscrowProposalEntity(
            proposalId = nextProposalId(),
            lendingId = lendingId,
            memberPeerId = memberPeerId,
            proposalType = EscrowProposalType.STAKE_RELEASE,
            appApprovalStatus = AppProposalStatus.APPROVED,
            custodyExecutionStatus = CustodyExecutionStatus.CREATED,
            targetWalletAddress = membership.walletAddress,
            mintAddress = channel.stakeTokenMint,
            amountAtomic = membership.stakeAmount
        )
        return executeProposal(proposal, channel, account)
    }

    override suspend fun reconcilePendingEscrowOperations(): List<LendingEscrowProposalEntity> {
        val pending = lendingDao.getPendingEscrowProposals()
        val reconciled = mutableListOf<LendingEscrowProposalEntity>()
        for (proposal in pending) {
            val channel = getChannel(proposal.lendingId)
            val account = provisionChannelEscrow(proposal.lendingId)
            val updated = executeProposal(proposal, channel, account)
            reconciled += updated
            if (updated.proposalType == EscrowProposalType.STAKE_DEPOSIT && !updated.memberPeerId.isNullOrBlank()) {
                val membership = lendingDao.getMembership(updated.lendingId, updated.memberPeerId)
                if (membership != null) {
                    val nextMembership = when (updated.custodyExecutionStatus) {
                        CustodyExecutionStatus.EXECUTED -> membership.copy(
                            depositStatus = EscrowTransferStatus.CONFIRMED,
                            joinStatus = LendingMemberStatus.ACTIVE,
                            updatedAt = System.currentTimeMillis()
                        )
                        CustodyExecutionStatus.FAILED -> membership.copy(
                            depositStatus = EscrowTransferStatus.FAILED,
                            joinStatus = LendingMemberStatus.PENDING,
                            updatedAt = System.currentTimeMillis()
                        )
                        else -> membership
                    }
                    if (nextMembership != membership) {
                        lendingDao.upsertMembership(nextMembership)
                        refreshPoolSnapshot(updated.lendingId)
                    }
                }
            }
        }
        return reconciled
    }

    private suspend fun executeProposal(
        proposal: LendingEscrowProposalEntity,
        channel: LendingChannelEntity,
        account: LendingEscrowAccountEntity
    ): LendingEscrowProposalEntity {
        if (proposal.custodyExecutionStatus == CustodyExecutionStatus.EXECUTED ||
            proposal.custodyExecutionStatus == CustodyExecutionStatus.FAILED
        ) {
            return proposal
        }

        proposal.txSignature?.let { queuedTransactionId ->
            val queuedTx = transactionDao.getTransaction(queuedTransactionId)
            if (queuedTx != null) {
                val reconciled = when (queuedTx.status) {
                    TransactionStatus.CONFIRMED.value -> proposal.copy(
                        custodyExecutionStatus = CustodyExecutionStatus.EXECUTED,
                        txSignature = queuedTx.txSignature ?: queuedTransactionId,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    TransactionStatus.FAILED.value -> proposal.copy(
                        custodyExecutionStatus = CustodyExecutionStatus.FAILED,
                        errorMessage = queuedTx.errorMessage ?: "stake_transfer_failed",
                        updatedAt = System.currentTimeMillis()
                    )
                    else -> proposal
                }
                if (reconciled != proposal) {
                    lendingDao.upsertEscrowProposal(reconciled)
                }
                return reconciled
            }
        }

        val transferResult = if (proposal.proposalType == EscrowProposalType.STAKE_DEPOSIT) {
            if (isNativeSolChannel(channel)) {
                transferGateway.queueNativeTransfer(
                    recipientPublicKey = account.vaultAddress,
                    amountLamports = proposal.amountAtomic,
                    memo = "lending ${proposal.proposalType.lowercase()} ${proposal.proposalId}"
                )
            } else {
                transferGateway.queueSplTransfer(
                    recipientPublicKey = account.vaultAddress,
                    mintAddress = proposal.mintAddress,
                    amountAtomic = proposal.amountAtomic,
                    decimals = channel.stakeTokenDecimals,
                    symbol = channel.stakeTokenSymbol.ifBlank { "TOKEN" },
                    memo = "lending ${proposal.proposalType.lowercase()} ${proposal.proposalId}"
                )
            }
        } else {
            val treasuryWallet = treasuryKeyStore.getTreasuryWallet(proposal.lendingId)
                ?: return proposal.copy(
                    custodyExecutionStatus = CustodyExecutionStatus.FAILED,
                    errorMessage = "treasury_wallet_not_found",
                    updatedAt = System.currentTimeMillis()
                ).also { lendingDao.upsertEscrowProposal(it) }
            if (isNativeSolChannel(channel)) {
                treasuryTransferService.sendSolFromTreasury(
                    treasuryPrivateKey = treasuryWallet.privateKey,
                    treasuryOwnerPublicKey = account.vaultAddress,
                    recipientPublicKey = proposal.targetWalletAddress,
                    amountLamports = proposal.amountAtomic
                )
            } else {
                treasuryTransferService.sendSplFromTreasury(
                    treasuryPrivateKey = treasuryWallet.privateKey,
                    treasuryOwnerPublicKey = account.vaultAddress,
                    sourceTokenAccount = account.vaultTokenAccountAddress,
                    recipientOwnerPublicKey = proposal.targetWalletAddress,
                    mintAddress = proposal.mintAddress,
                    amountAtomic = proposal.amountAtomic,
                    decimals = channel.stakeTokenDecimals
                )
            }
        }
        val updated = if (transferResult.isSuccess) {
            proposal.copy(
                custodyExecutionStatus = if (proposal.proposalType == EscrowProposalType.STAKE_DEPOSIT) {
                    CustodyExecutionStatus.CREATED
                } else {
                    CustodyExecutionStatus.EXECUTED
                },
                txSignature = transferResult.getOrNull(),
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            proposal.copy(
                custodyExecutionStatus = CustodyExecutionStatus.FAILED,
                txSignature = null,
                errorMessage = transferResult.exceptionOrNull()?.message,
                updatedAt = System.currentTimeMillis()
            )
        }
        lendingDao.upsertEscrowProposal(updated)
        lendingDao.upsertEscrowAccount(account.copy(updatedAt = System.currentTimeMillis()))
        return updated
    }

    private suspend fun resolveLoanRecipientWallet(
        loan: LoanRequestEntity,
        channel: LendingChannelEntity
    ): String {
        return if (loan.borrowerType == BorrowerType.INDIVIDUAL) {
            loan.borrowerWalletAddress?.takeIf { it.isNotBlank() }?.let { return it }
            val borrowerWallet = loan.borrowerPeerId?.let { peerId ->
                lendingDao.getMembership(loan.lendingId, peerId)?.walletAddress
            }
            borrowerWallet ?: throw IllegalStateException("borrower_wallet_not_found")
        } else {
            channel.creatorWalletAddress
        }
    }

    private suspend fun getChannel(lendingId: String): LendingChannelEntity {
        return lendingDao.getLendingChannelById(lendingId)
            ?: throw IllegalArgumentException("lending_channel_not_found")
    }

    private fun nextProposalId(): String {
        return "SQP-${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"
    }

    private suspend fun refreshPoolSnapshot(lendingId: String) {
        val memberships = lendingDao.getMembershipsForLendingChannel(lendingId)
        val totalStakedAmount = memberships
            .filter {
                it.joinStatus == LendingMemberStatus.ACTIVE &&
                    it.depositStatus == EscrowTransferStatus.CONFIRMED
            }
            .sumOf { it.stakeAmount }
        val loanRequests = lendingDao.getLoanRequestsForLendingChannel(lendingId)
        val repaymentsByRequest = loanRequests.associate { request ->
            request.requestId to lendingDao.getRepaymentsForRequest(request.requestId)
                .filter { it.txStatus == EscrowTransferStatus.CONFIRMED }
                .sumOf { it.amount }
        }
        val totalRepayments = repaymentsByRequest.values.sum()
        val reservedAmount = loanRequests
            .filter { it.status == com.bitchat.android.data.local.entities.LoanRequestStatus.APPROVED }
            .sumOf { request ->
                kotlin.math.max(request.principalAmount - (repaymentsByRequest[request.requestId] ?: 0L), 0L)
            }
        val disbursedAmount = loanRequests
            .filter {
                it.status in setOf(
                    com.bitchat.android.data.local.entities.LoanRequestStatus.DISBURSED,
                    com.bitchat.android.data.local.entities.LoanRequestStatus.REPAID,
                    com.bitchat.android.data.local.entities.LoanRequestStatus.DEFAULTED
                )
            }
            .sumOf { request ->
                kotlin.math.max(request.principalAmount - (repaymentsByRequest[request.requestId] ?: 0L), 0L)
            }
        val availableLiquidityAmount = (totalStakedAmount + totalRepayments - reservedAmount - disbursedAmount)
            .coerceAtLeast(0L)
        lendingDao.upsertPoolSnapshot(
            LendingPoolSnapshotEntity(
                lendingId = lendingId,
                totalStakedAmount = totalStakedAmount,
                reservedAmount = reservedAmount,
                disbursedAmount = disbursedAmount,
                availableLiquidityAmount = availableLiquidityAmount,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun getOrCreateStakeDepositProposal(
        lendingId: String,
        memberPeerId: String,
        channel: LendingChannelEntity,
        account: LendingEscrowAccountEntity,
        membership: LendingMembershipEntity
    ): LendingEscrowProposalEntity {
        return lendingDao.getEscrowProposalsForLendingChannel(lendingId)
            .firstOrNull {
                it.memberPeerId == memberPeerId && it.proposalType == EscrowProposalType.STAKE_DEPOSIT
            } ?: LendingEscrowProposalEntity(
                proposalId = nextProposalId(),
                lendingId = lendingId,
                memberPeerId = memberPeerId,
                proposalType = EscrowProposalType.STAKE_DEPOSIT,
                appApprovalStatus = AppProposalStatus.APPROVED,
                custodyExecutionStatus = CustodyExecutionStatus.CREATED,
                targetWalletAddress = account.vaultAddress,
                mintAddress = channel.stakeTokenMint,
                amountAtomic = requiredJoinDebitAmount(channel)
            ).also { lendingDao.upsertEscrowProposal(it) }
    }

    private suspend fun repairStakeProposalFromTransaction(
        proposal: LendingEscrowProposalEntity
    ): LendingEscrowProposalEntity {
        if (proposal.custodyExecutionStatus == CustodyExecutionStatus.EXECUTED ||
            proposal.custodyExecutionStatus == CustodyExecutionStatus.FAILED
        ) {
            return proposal
        }

        val queuedId = proposal.txSignature ?: return proposal
        val queuedTx = transactionDao.getTransaction(queuedId) ?: return proposal
        val repaired = when (queuedTx.status) {
            TransactionStatus.CONFIRMED.value -> proposal.copy(
                custodyExecutionStatus = CustodyExecutionStatus.EXECUTED,
                txSignature = queuedTx.txSignature ?: queuedId,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
            TransactionStatus.FAILED.value -> proposal.copy(
                custodyExecutionStatus = CustodyExecutionStatus.FAILED,
                errorMessage = queuedTx.errorMessage ?: "stake_transfer_failed",
                updatedAt = System.currentTimeMillis()
            )
            else -> proposal
        }
        if (repaired != proposal) {
            lendingDao.upsertEscrowProposal(repaired)
        }
        return repaired
    }

    private fun isNativeSolChannel(channel: LendingChannelEntity): Boolean {
        return isNativeSolStakeAsset(channel.stakeTokenMint, channel.stakeTokenSymbol)
    }
}
