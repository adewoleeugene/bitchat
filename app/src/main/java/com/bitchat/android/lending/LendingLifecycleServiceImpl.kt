package com.bitchat.android.lending

import com.bitchat.android.data.local.LendingDao
import com.bitchat.android.data.local.entities.BorrowerType
import com.bitchat.android.data.local.entities.CustodyExecutionStatus
import com.bitchat.android.data.local.entities.EscrowTransferStatus
import com.bitchat.android.data.local.entities.LendingMemberStatus
import com.bitchat.android.data.local.entities.LendingMembershipEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import com.bitchat.android.data.local.entities.LoanRepaymentEntity
import com.bitchat.android.data.local.entities.LoanRequestEntity
import com.bitchat.android.data.local.entities.LoanRequestStatus
import com.bitchat.android.data.local.entities.LoanVoteEntity
import com.bitchat.android.data.local.entities.VoteChoice
import com.bitchat.android.solana.LendingTransferGateway
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max

@Singleton
class LendingLifecycleServiceImpl @Inject constructor(
    private val lendingDao: LendingDao,
    private val lendingChannelService: LendingChannelService,
    private val transferGateway: LendingTransferGateway,
    private val escrowService: LendingEscrowService
) : LendingLoanService, LendingEscrowService {

    override suspend fun getMemberships(lendingId: String): List<LendingMembershipEntity> {
        return lendingDao.getMembershipsForLendingChannel(lendingId)
    }

    override suspend fun getPoolSnapshot(lendingId: String): LendingPoolSnapshotEntity? {
        return lendingDao.getPoolSnapshot(lendingId)
    }

    override suspend fun activateMembership(lendingId: String, memberPeerId: String): LendingMembershipEntity {
        val updated = escrowService.activateMembership(lendingId, memberPeerId)
        refreshPoolSnapshot(lendingId)
        return updated
    }

    override suspend fun releaseMembershipStake(lendingId: String, memberPeerId: String): LendingMembershipEntity {
        val updated = escrowService.releaseMembershipStake(lendingId, memberPeerId)
        refreshPoolSnapshot(lendingId)
        return updated
    }

    override suspend fun provisionChannelEscrow(lendingId: String) = escrowService.provisionChannelEscrow(lendingId)

    override suspend fun getEscrowAccount(lendingId: String) = escrowService.getEscrowAccount(lendingId)

    override suspend fun getEscrowProposalsForRequest(requestId: String) =
        escrowService.getEscrowProposalsForRequest(requestId)

    override suspend fun createLoanDisbursementProposal(requestId: String) =
        escrowService.createLoanDisbursementProposal(requestId)

    override suspend fun createStakeReleaseProposal(lendingId: String, memberPeerId: String) =
        escrowService.createStakeReleaseProposal(lendingId, memberPeerId)

    override suspend fun reconcilePendingEscrowOperations() =
        escrowService.reconcilePendingEscrowOperations()

    override suspend fun getLoanRequests(lendingId: String): List<LoanRequestEntity> {
        return lendingDao.getLoanRequestsForLendingChannel(lendingId)
    }

    override suspend fun getLoanRequest(requestId: String): LoanRequestEntity? {
        return lendingDao.getLoanRequestById(requestId)
    }

    override suspend fun getVotes(requestId: String): List<LoanVoteEntity> {
        return lendingDao.getVotesForRequest(requestId)
    }

    override suspend fun getRepayments(requestId: String): List<LoanRepaymentEntity> {
        return lendingDao.getRepaymentsForRequest(requestId)
    }

    override suspend fun createLoanRequest(request: CreateLoanRequest): LoanRequestEntity {
        val channel = lendingChannelService.getChannelByIdentifier(request.identifier, request.preferredChannelKey)
            ?: throw IllegalArgumentException("lending_channel_not_found")
        val membership = requireActiveMembership(channel.lendingId, request.requesterPeerId)
        if (request.principalAmount <= 0L) throw IllegalArgumentException("amount_must_be_positive")
        if (request.durationDays <= 0) throw IllegalArgumentException("duration_must_be_positive")
        if (request.purpose.isBlank()) throw IllegalArgumentException("purpose_required")

        if (request.borrowerType == BorrowerType.INDIVIDUAL) {
            lendingDao.getActiveIndividualLoanForBorrower(channel.lendingId, request.requesterPeerId)?.let {
                throw IllegalStateException("active_individual_loan_exists")
            }
        }

        val pool = refreshPoolSnapshot(channel.lendingId)
        val cap = (pool.availableLiquidityAmount * DEFAULT_BORROW_CAP_PERCENT) / 100
        if (request.principalAmount > cap) {
            throw IllegalArgumentException("request_exceeds_pool_cap")
        }

        val now = System.currentTimeMillis()
        val entity = LoanRequestEntity(
            requestId = nextRequestId(),
            lendingId = channel.lendingId,
            borrowerType = request.borrowerType,
            borrowerPeerId = if (request.borrowerType == BorrowerType.INDIVIDUAL) membership.memberPeerId else null,
            borrowerGroupKey = if (request.borrowerType == BorrowerType.GROUP) {
                request.borrowerGroupKey ?: channel.lendingId
            } else {
                null
            },
            principalAmount = request.principalAmount,
            interestBps = request.interestBps,
            durationDays = request.durationDays,
            purpose = request.purpose.trim(),
            status = LoanRequestStatus.PENDING,
            requestedAt = now,
            dueAt = now + request.durationDays * 24L * 60L * 60L * 1000L
        )
        lendingDao.upsertLoanRequest(entity)
        refreshPoolSnapshot(channel.lendingId)
        return entity
    }

    override suspend fun castVote(request: CastLoanVoteRequest): LoanVoteResult {
        val loan = lendingDao.getLoanRequestById(request.requestId)
            ?: throw IllegalArgumentException("loan_request_not_found")
        if (loan.status !in setOf(LoanRequestStatus.PENDING, LoanRequestStatus.APPROVED)) {
            throw IllegalStateException("loan_request_not_open_for_voting")
        }

        requireActiveMembership(loan.lendingId, request.voterPeerId)

        val voteChoice = when (request.voteChoice.uppercase()) {
            VoteChoice.YES -> VoteChoice.YES
            VoteChoice.NO -> VoteChoice.NO
            else -> throw IllegalArgumentException("vote_must_be_yes_or_no")
        }

        lendingDao.upsertLoanVote(
            LoanVoteEntity(
                requestId = loan.requestId,
                voterPeerId = request.voterPeerId,
                lendingId = loan.lendingId,
                voteChoice = voteChoice
            )
        )

        val votes = lendingDao.getVotesForRequest(loan.requestId)
        val activeMembers = lendingDao.getMembershipsForLendingChannel(loan.lendingId)
            .count { it.joinStatus == LendingMemberStatus.ACTIVE && it.depositStatus == EscrowTransferStatus.CONFIRMED }
        val quorumNeeded = max(1, ceil(activeMembers * (getChannel(loan.lendingId).quorumThresholdPercent / 100.0)).toInt())
        val yesVotes = votes.count { it.voteChoice == VoteChoice.YES }
        val noVotes = votes.count { it.voteChoice == VoteChoice.NO }
        val quorumReached = votes.size >= quorumNeeded
        val approved = quorumReached && yesVotes > noVotes
        val rejected = quorumReached && !approved

        val updatedRequest = when {
            approved -> loan.copy(
                status = LoanRequestStatus.APPROVED,
                approvedAt = System.currentTimeMillis()
            )
            rejected -> loan.copy(status = LoanRequestStatus.REJECTED)
            else -> loan
        }

        if (updatedRequest != loan) {
            lendingDao.upsertLoanRequest(updatedRequest)
        }
        val finalizedRequest = if (approved) {
            val proposal = escrowService.createLoanDisbursementProposal(updatedRequest.requestId)
            if (proposal.custodyExecutionStatus == CustodyExecutionStatus.EXECUTED) {
                val disbursed = updatedRequest.copy(
                    status = LoanRequestStatus.DISBURSED,
                    disbursedAt = System.currentTimeMillis()
                )
                lendingDao.upsertLoanRequest(disbursed)
                disbursed
            } else {
                updatedRequest
            }
        } else {
            updatedRequest
        }
        refreshPoolSnapshot(loan.lendingId)

        return LoanVoteResult(
            request = finalizedRequest,
            votes = votes,
            quorumReached = quorumReached,
            approved = approved,
            rejected = rejected
        )
    }

    override suspend fun repayLoan(request: RecordLoanRepaymentRequest): LoanRepaymentResult {
        val loan = lendingDao.getLoanRequestById(request.requestId)
            ?: throw IllegalArgumentException("loan_request_not_found")
        if (request.amount <= 0L) throw IllegalArgumentException("repayment_amount_must_be_positive")
        if (loan.status !in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.DEFAULTED, LoanRequestStatus.APPROVED)) {
            throw IllegalStateException("loan_request_not_repayable")
        }

        if (loan.borrowerType == BorrowerType.INDIVIDUAL && loan.borrowerPeerId != request.payerPeerId) {
            throw IllegalStateException("only_borrower_can_repay_individual_loan")
        }

        val channel = getChannel(loan.lendingId)
        val queueResult = if (channel.escrowMultisigAddress.isNotBlank()) {
            transferGateway.queueSplTransfer(
                recipientPublicKey = channel.escrowMultisigAddress,
                mintAddress = channel.stakeTokenMint,
                amountAtomic = request.amount,
                decimals = channel.stakeTokenDecimals,
                symbol = channel.stakeTokenSymbol.ifBlank { "TOKEN" },
                memo = "loan repayment ${loan.requestId}"
            )
        } else {
            Result.success("local-repayment")
        }

        val repayment = LoanRepaymentEntity(
            repaymentId = nextRepaymentId(),
            requestId = loan.requestId,
            lendingId = loan.lendingId,
            amount = request.amount,
            txSignature = queueResult.getOrNull(),
            txStatus = if (queueResult.isSuccess) EscrowTransferStatus.CONFIRMED else EscrowTransferStatus.FAILED
        )
        lendingDao.insertLoanRepayment(repayment)

        val repayments = lendingDao.getRepaymentsForRequest(loan.requestId)
        val totalRepaid = repayments.sumOf { it.amount }
        val totalDue = loan.principalAmount + calculateInterestAmount(loan)
        val remainingBalance = max(totalDue - totalRepaid, 0L)
        val updatedLoan = if (remainingBalance == 0L) {
            loan.copy(status = LoanRequestStatus.REPAID)
        } else {
            loan
        }
        if (updatedLoan != loan) {
            lendingDao.upsertLoanRequest(updatedLoan)
        }
        refreshPoolSnapshot(loan.lendingId)

        return LoanRepaymentResult(
            repayment = repayment,
            updatedRequest = updatedLoan,
            totalRepaidAmount = totalRepaid,
            remainingBalance = remainingBalance
        )
    }

    override suspend fun leaveChannel(request: LeaveLendingChannelRequest): LendingLeaveResult {
        val channel = lendingChannelService.getChannelByIdentifier(request.identifier, request.preferredChannelKey)
            ?: throw IllegalArgumentException("lending_channel_not_found")
        val membership = requireActiveMembership(channel.lendingId, request.memberPeerId)

        val blockingLoan = lendingDao.getLoanRequestsForLendingChannel(channel.lendingId).firstOrNull { loan ->
            loan.borrowerType == BorrowerType.INDIVIDUAL &&
                loan.borrowerPeerId == request.memberPeerId &&
                loan.status in setOf(LoanRequestStatus.PENDING, LoanRequestStatus.APPROVED, LoanRequestStatus.DISBURSED, LoanRequestStatus.DEFAULTED)
        }
        if (blockingLoan != null) {
            throw IllegalStateException("active_loan_blocks_exit")
        }

        val proposal = escrowService.createStakeReleaseProposal(channel.lendingId, request.memberPeerId)
        if (proposal.custodyExecutionStatus != CustodyExecutionStatus.EXECUTED) {
            throw IllegalStateException("stake_release_pending_execution")
        }

        val updated = releaseMembershipStake(channel.lendingId, request.memberPeerId)
        return LendingLeaveResult(
            membership = updated,
            queuedTransferId = proposal.txSignature,
            escrowProposalId = proposal.proposalId,
            escrowExecutionStatus = proposal.custodyExecutionStatus
        )
    }

    private suspend fun requireActiveMembership(
        lendingId: String,
        memberPeerId: String
    ): LendingMembershipEntity {
        val membership = lendingDao.getMembership(lendingId, memberPeerId)
            ?: throw IllegalStateException("membership_not_found")
        if (membership.joinStatus != LendingMemberStatus.ACTIVE || membership.depositStatus != EscrowTransferStatus.CONFIRMED) {
            throw IllegalStateException("membership_not_active")
        }
        return membership
    }

    private suspend fun refreshPoolSnapshot(lendingId: String): LendingPoolSnapshotEntity {
        val memberships = lendingDao.getMembershipsForLendingChannel(lendingId)
        val activeStake = memberships
            .filter { it.joinStatus == LendingMemberStatus.ACTIVE && it.depositStatus == EscrowTransferStatus.CONFIRMED }
            .sumOf { it.stakeAmount }
        val loanRequests = lendingDao.getLoanRequestsForLendingChannel(lendingId)
        val repaymentsByRequest = loanRequests.associate { request ->
            request.requestId to lendingDao.getRepaymentsForRequest(request.requestId).sumOf { it.amount }
        }
        val principalDisbursed = loanRequests
            .filter { it.status in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.REPAID, LoanRequestStatus.DEFAULTED) }
            .sumOf { it.principalAmount }
        val totalRepayments = repaymentsByRequest.values.sum()
        val outstandingPrincipal = loanRequests
            .filter { it.status in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.DEFAULTED, LoanRequestStatus.APPROVED) }
            .sumOf { request ->
                max(request.principalAmount - (repaymentsByRequest[request.requestId] ?: 0L), 0L)
            }

        val snapshot = LendingPoolSnapshotEntity(
            lendingId = lendingId,
            totalStakedAmount = activeStake,
            reservedAmount = 0L,
            disbursedAmount = outstandingPrincipal,
            availableLiquidityAmount = max(activeStake + totalRepayments - principalDisbursed, 0L),
            updatedAt = System.currentTimeMillis()
        )
        lendingDao.upsertPoolSnapshot(snapshot)
        return snapshot
    }

    private suspend fun getChannel(lendingId: String) =
        lendingDao.getLendingChannelById(lendingId) ?: throw IllegalStateException("lending_channel_not_found")

    private fun calculateInterestAmount(request: LoanRequestEntity): Long {
        return (request.principalAmount * request.interestBps) / 10_000L
    }

    private fun nextRequestId(): String = "LR-${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"

    private fun nextRepaymentId(): String = "RP-${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"
}
