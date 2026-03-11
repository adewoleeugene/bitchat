package com.bitchat.android.lending

import com.bitchat.android.data.local.LendingDao
import com.bitchat.android.data.local.entities.BorrowerType
import com.bitchat.android.data.local.entities.CustodyExecutionStatus
import com.bitchat.android.data.local.entities.EscrowTransferStatus
import com.bitchat.android.data.local.entities.LendingMemberStatus
import com.bitchat.android.data.local.entities.LendingMembershipEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import com.bitchat.android.data.local.entities.AppProposalStatus
import com.bitchat.android.data.local.entities.LoanChainStatus
import com.bitchat.android.data.local.entities.LoanRepaymentEntity
import com.bitchat.android.data.local.entities.LoanRequestEntity
import com.bitchat.android.data.local.entities.LoanRequestKind
import com.bitchat.android.data.local.entities.LoanRequestStatus
import com.bitchat.android.data.local.entities.LoanVoteEntity
import com.bitchat.android.data.local.entities.LendingEscrowProposalEntity
import com.bitchat.android.data.local.entities.EscrowProposalType
import com.bitchat.android.data.local.entities.VoteChoice
import com.bitchat.android.lending.onchain.CastLoanVoteOnChainParams
import com.bitchat.android.lending.onchain.CreateLoanRequestOnChainParams
import com.bitchat.android.lending.onchain.FinalizeLoanRequestOnChainParams
import com.bitchat.android.lending.onchain.LendingOnChainService
import com.bitchat.android.lending.onchain.OnChainLoanRequestState
import com.bitchat.android.lending.onchain.RecordLoanRepaymentOnChainParams
import com.bitchat.android.solana.LendingTransferGateway
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class LendingLifecycleServiceImpl @Inject constructor(
    private val lendingDao: LendingDao,
    private val lendingChannelService: LendingChannelService,
    private val transferGateway: LendingTransferGateway,
    private val escrowService: LendingEscrowService,
    private val squadsService: SquadsService,
    private val lendingOnChainService: LendingOnChainService
) : LendingLoanService, LendingEscrowService {
    private val gson = Gson()

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

    override suspend fun prepareStakeDeposit(lendingId: String, memberPeerId: String): LendingStakeApprovalRequest {
        return escrowService.prepareStakeDeposit(lendingId, memberPeerId)
    }

    override suspend fun submitStakeDeposit(lendingId: String, memberPeerId: String): LendingMembershipEntity {
        val updated = escrowService.submitStakeDeposit(lendingId, memberPeerId)
        refreshPoolSnapshot(lendingId)
        return updated
    }

    override suspend fun repairMembershipState(lendingId: String, memberPeerId: String): LendingMembershipEntity? {
        val updated = escrowService.repairMembershipState(lendingId, memberPeerId)
        if (updated != null) {
            refreshPoolSnapshot(lendingId)
        }
        return updated
    }

    override suspend fun repairMembershipsForTransaction(
        queuedTransactionId: String,
        txSignature: String?
    ): List<LendingMembershipEntity> {
        val updated = escrowService.repairMembershipsForTransaction(queuedTransactionId, txSignature)
        updated.map { it.lendingId }.distinct().forEach { lendingId ->
            refreshPoolSnapshot(lendingId)
        }
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

    override suspend fun getLinkedLoanRequests(requestId: String): List<LoanRequestEntity> {
        val request = lendingDao.getLoanRequestById(requestId) ?: return emptyList()
        return lendingDao.getLinkedLoanRequests(familyRootRequestId(request))
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
        val normalizedEndorsers = request.endorserPeerIds
            .map { it.trim() }
            .filter { it.isNotBlank() && it != request.requesterPeerId }
            .distinct()

        val now = System.currentTimeMillis()
        val entity = LoanRequestEntity(
            requestId = nextRequestId(),
            lendingId = channel.lendingId,
            borrowerType = request.borrowerType,
            borrowerPeerId = if (request.borrowerType == BorrowerType.INDIVIDUAL) membership.memberPeerId else null,
            borrowerWalletAddress = if (request.borrowerType == BorrowerType.INDIVIDUAL) membership.walletAddress else null,
            borrowerGroupKey = if (request.borrowerType == BorrowerType.GROUP) {
                request.borrowerGroupKey ?: channel.lendingId
            } else {
                null
            },
            principalAmount = request.principalAmount,
            interestBps = request.interestBps,
            durationDays = request.durationDays,
            purpose = request.purpose.trim(),
            endorserPeerIdsJson = gson.toJson(normalizedEndorsers),
            status = LoanRequestStatus.PENDING,
            requestedAt = now,
            dueAt = now + request.durationDays * 24L * 60L * 60L * 1000L,
            requestKind = LoanRequestKind.ORIGIN,
            originLendingId = channel.lendingId,
            chainStatus = if (lendingOnChainService.isEnabled()) {
                LoanChainStatus.PENDING_SUBMISSION
            } else {
                LoanChainStatus.LOCAL_ONLY
            }
        )
        val persisted = if (lendingOnChainService.isEnabled()) {
            val onChain = lendingOnChainService.createLoanRequestOnChain(
                CreateLoanRequestOnChainParams(
                    lendingId = channel.lendingId,
                    requestId = entity.requestId,
                    borrowerWallet = membership.walletAddress,
                    principalAmount = entity.principalAmount,
                    durationDays = entity.durationDays,
                    interestBps = entity.interestBps,
                    purpose = entity.purpose,
                    requestedAt = entity.requestedAt,
                    dueAt = entity.dueAt
                )
            ).getOrElse { throw it }
            entity.copy(
                channelPda = onChain.channelPda,
                loanRequestPda = onChain.loanRequestPda,
                lastChainSyncSignature = onChain.txSignature,
                lastChainSyncedSlot = onChain.slot,
                chainStatus = onChain.chainStatus
            )
        } else {
            entity
        }
        lendingDao.upsertLoanRequest(persisted)
        val authoritative = if (lendingOnChainService.isEnabled()) {
            syncLoanRequestFromChain(
                lendingId = channel.lendingId,
                requestId = persisted.requestId,
                fallback = persisted
            ) ?: persisted
        } else {
            persisted
        }
        refreshPoolSnapshot(channel.lendingId)
        return authoritative
    }

    override suspend fun forwardLoanRequest(request: ForwardLoanRequest): LoanRequestEntity {
        val source = lendingDao.getLoanRequestById(request.requestId)
            ?: throw IllegalArgumentException("loan_request_not_found")
        if (source.status !in setOf(LoanRequestStatus.PENDING, LoanRequestStatus.APPROVED)) {
            throw IllegalStateException("loan_request_not_forwardable")
        }

        val sourceChannel = getChannel(source.lendingId)
        if (sourceChannel.creatorPeerId != request.actorPeerId) {
            throw IllegalStateException("admin_only_loan_forward")
        }

        val destinationChannel = lendingChannelService.getChannelByIdentifier(
            request.destinationIdentifier,
            request.preferredChannelKey
        ) ?: throw IllegalArgumentException("lending_channel_not_found")
        if (destinationChannel.lendingId == source.lendingId) {
            throw IllegalArgumentException("cannot_forward_to_same_channel")
        }

        val borrowerPeerId = source.borrowerPeerId ?: throw IllegalStateException("only_individual_loans_can_be_forwarded")
        requireActiveMembership(destinationChannel.lendingId, borrowerPeerId)

        val familyRootId = familyRootRequestId(source)
        lendingDao.getLinkedLoanRequestForLending(familyRootId, destinationChannel.lendingId)?.let {
            return it
        }

        val forwarded = source.copy(
            requestId = nextRequestId(),
            lendingId = destinationChannel.lendingId,
            status = LoanRequestStatus.PENDING,
            approvedAt = null,
            disbursedAt = null,
            defaultedAt = null,
            parentRequestId = familyRootId,
            requestKind = LoanRequestKind.FORWARDED_COPY,
            originLendingId = source.originLendingId ?: source.lendingId,
            forwardedFromRequestId = source.requestId,
            fundingLendingId = null,
            squadsMultisigAddress = null,
            squadsVaultAddress = null,
            squadsProposalAddress = null,
            squadsTransactionIndex = null,
            channelPda = null,
            loanRequestPda = null,
            lastChainSyncSignature = null,
            lastChainSyncedSlot = null,
            chainStatus = LoanChainStatus.LOCAL_ONLY
        )
        lendingDao.upsertLoanRequest(forwarded)
        refreshPoolSnapshot(destinationChannel.lendingId)
        return forwarded
    }

    override suspend fun importDiscoveredLoanRequest(message: LendingLoanRequestMessage): LoanRequestEntity? {
        val channel = lendingChannelService.getChannelByLendingId(message.lendingId) ?: return null
        val existing = lendingDao.getLoanRequestById(message.requestId)
        val entity = (existing ?: LoanRequestEntity(
            requestId = message.requestId,
            lendingId = channel.lendingId,
            borrowerType = BorrowerType.INDIVIDUAL,
            borrowerPeerId = message.borrowerPeerId,
            borrowerWalletAddress = message.borrowerWalletAddress,
            principalAmount = message.principalAmount,
            interestBps = message.interestBps,
            durationDays = message.durationDays,
            purpose = message.purpose.trim(),
            status = message.status,
            requestedAt = message.requestedAt,
            dueAt = message.requestedAt + message.durationDays * 24L * 60L * 60L * 1000L,
            parentRequestId = message.parentRequestId,
            requestKind = message.requestKind,
            originLendingId = message.originLendingId ?: message.lendingId,
            forwardedFromRequestId = message.forwardedFromRequestId,
            fundingLendingId = message.fundingLendingId
        )).copy(
            lendingId = channel.lendingId,
            borrowerType = BorrowerType.INDIVIDUAL,
            borrowerPeerId = message.borrowerPeerId,
            borrowerWalletAddress = message.borrowerWalletAddress,
            principalAmount = message.principalAmount,
            interestBps = message.interestBps,
            durationDays = message.durationDays,
            purpose = message.purpose.trim(),
            status = message.status,
            requestedAt = message.requestedAt,
            dueAt = message.requestedAt + message.durationDays * 24L * 60L * 60L * 1000L,
            parentRequestId = message.parentRequestId,
            requestKind = message.requestKind,
            originLendingId = message.originLendingId ?: message.lendingId,
            forwardedFromRequestId = message.forwardedFromRequestId,
            fundingLendingId = message.fundingLendingId ?: existing?.fundingLendingId
        )
        val persisted = if (lendingOnChainService.isEnabled()) {
            entity.copy(
                status = existing?.status ?: LoanRequestStatus.PENDING,
                approvedAt = existing?.approvedAt,
                disbursedAt = existing?.disbursedAt,
                channelPda = existing?.channelPda,
                loanRequestPda = existing?.loanRequestPda,
                lastChainSyncSignature = existing?.lastChainSyncSignature,
                lastChainSyncedSlot = existing?.lastChainSyncedSlot,
                chainStatus = existing?.chainStatus ?: LoanChainStatus.PENDING_SUBMISSION
            )
        } else {
            entity
        }
        lendingDao.upsertLoanRequest(persisted)
        if (persisted.status in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.REPAID)) {
            markSiblingRequestsFundedElsewhere(persisted)
        }
        refreshPoolSnapshot(channel.lendingId)
        return if (lendingOnChainService.isEnabled()) {
            syncLoanRequestFromChain(channel.lendingId, message.requestId, persisted) ?: persisted
        } else {
            persisted
        }
    }

    override suspend fun importDiscoveredLoanVote(message: LendingLoanVoteMessage): LoanRequestEntity? {
        val channel = lendingChannelService.getChannelByLendingId(message.lendingId) ?: return null
        val existing = lendingDao.getLoanRequestById(message.requestId) ?: return null
        lendingDao.upsertLoanVote(
            LoanVoteEntity(
                requestId = message.requestId,
                voterPeerId = message.voterPeerId,
                lendingId = channel.lendingId,
                voteChoice = message.voteChoice
            )
        )
        val hydrated = existing.copy(
            squadsMultisigAddress = message.squadsMultisigAddress ?: existing.squadsMultisigAddress,
            squadsVaultAddress = message.squadsVaultAddress ?: existing.squadsVaultAddress,
            squadsProposalAddress = message.squadsProposalAddress ?: existing.squadsProposalAddress,
            squadsTransactionIndex = message.squadsTransactionIndex ?: existing.squadsTransactionIndex
        )
        if (hydrated != existing) {
            lendingDao.upsertLoanRequest(hydrated)
        }
        if (lendingOnChainService.isEnabled()) {
            refreshPoolSnapshot(channel.lendingId)
            return syncLoanRequestFromChain(channel.lendingId, message.requestId, hydrated) ?: hydrated
        }
        val updated = hydrated.copy(
            status = moreAdvancedLoanStatus(hydrated.status, message.requestStatus),
            approvedAt = message.approvedAt ?: hydrated.approvedAt,
            disbursedAt = message.disbursedAt ?: hydrated.disbursedAt
        )
        lendingDao.upsertLoanRequest(updated)
        if (updated.status in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.REPAID)) {
            markSiblingRequestsFundedElsewhere(updated)
        }
        val authoritative = if (!updated.squadsProposalAddress.isNullOrBlank()) {
            syncLoanRequestFromSquads(channel.lendingId, message.requestId, updated) ?: updated
        } else {
            updated
        }
        refreshPoolSnapshot(channel.lendingId)
        return authoritative
    }

    override suspend fun importDiscoveredLoanRepayment(message: LendingLoanRepaymentMessage): LoanRepaymentResult? {
        val channel = lendingChannelService.getChannelByLendingId(message.lendingId) ?: return null
        val loan = lendingDao.getLoanRequestById(message.requestId) ?: return null
        val repayment = LoanRepaymentEntity(
            repaymentId = message.repaymentId,
            requestId = message.requestId,
            lendingId = channel.lendingId,
            amount = message.amount,
            txSignature = message.txSignature,
            txStatus = message.txStatus,
            paidAt = message.paidAt
        )
        lendingDao.insertLoanRepayment(repayment)
        if (lendingOnChainService.isEnabled()) {
            val syncedLoan = syncLoanRequestFromChain(channel.lendingId, message.requestId, loan) ?: loan
            refreshPoolSnapshot(channel.lendingId)
            return LoanRepaymentResult(
                repayment = repayment,
                updatedRequest = syncedLoan,
                totalRepaidAmount = message.totalRepaidAmount,
                remainingBalance = message.remainingBalance
            )
        }
        val updatedLoan = loan.copy(status = moreAdvancedLoanStatus(loan.status, message.requestStatus))
        lendingDao.upsertLoanRequest(updatedLoan)
        refreshPoolSnapshot(channel.lendingId)
        return LoanRepaymentResult(
            repayment = repayment,
            updatedRequest = updatedLoan,
            totalRepaidAmount = message.totalRepaidAmount,
            remainingBalance = message.remainingBalance
        )
    }

    override suspend fun castVote(request: CastLoanVoteRequest): LoanVoteResult {
        val loan = lendingDao.getLoanRequestById(request.requestId)
            ?: throw IllegalArgumentException("loan_request_not_found")
        if (loan.status != LoanRequestStatus.PENDING) {
            throw IllegalStateException("loan_request_not_open_for_voting")
        }
        if (hasFundedSibling(loan)) {
            throw IllegalStateException("loan_request_already_funded_elsewhere")
        }
        if (loan.borrowerType == BorrowerType.INDIVIDUAL && loan.borrowerPeerId == request.voterPeerId) {
            throw IllegalStateException("borrower_cannot_vote_own_request")
        }

        val voteChoice = when (request.voteChoice.uppercase()) {
            VoteChoice.YES -> VoteChoice.YES
            else -> throw IllegalArgumentException("vote_must_be_upvote")
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
        if (!lendingOnChainService.isEnabled()) {
            val approvals = countLoanApprovals(votes)
            val approvalsReached = approvals >= REQUIRED_LOAN_APPROVAL_COUNT

            val updatedRequest = when {
                approvalsReached -> loan.copy(
                    status = LoanRequestStatus.APPROVED,
                    approvedAt = System.currentTimeMillis()
                )
                else -> loan
            }

            if (updatedRequest != loan) {
                lendingDao.upsertLoanRequest(updatedRequest)
            }
            refreshPoolSnapshot(loan.lendingId)

            return LoanVoteResult(
                request = updatedRequest,
                votes = votes,
                quorumReached = approvalsReached,
                approved = approvalsReached,
                rejected = false
            )
        }

        val onChainVote = lendingOnChainService.castLoanVoteOnChain(
            CastLoanVoteOnChainParams(
                lendingId = loan.lendingId,
                requestId = loan.requestId,
                voteChoice = voteChoice
            )
        ).getOrElse { throw it }
        val submitted = loan.copy(
            channelPda = loan.channelPda ?: onChainVote.channelPda,
            loanRequestPda = loan.loanRequestPda ?: onChainVote.loanRequestPda,
            lastChainSyncSignature = onChainVote.txSignature,
            lastChainSyncedSlot = onChainVote.slot,
            chainStatus = onChainVote.chainStatus
        )
        lendingDao.upsertLoanRequest(submitted)

        val approvals = countLoanApprovals(votes)
        if (approvals >= REQUIRED_LOAN_APPROVAL_COUNT) {
            lendingOnChainService.finalizeLoanRequestOnChain(
                FinalizeLoanRequestOnChainParams(
                    lendingId = loan.lendingId,
                    requestId = loan.requestId
                )
            )
        }
        val authoritative = syncLoanRequestFromChain(loan.lendingId, loan.requestId, submitted) ?: submitted
        refreshPoolSnapshot(loan.lendingId)

        return LoanVoteResult(
            request = authoritative,
            votes = votes,
            quorumReached = authoritative.status != LoanRequestStatus.PENDING ||
                approvals >= REQUIRED_LOAN_APPROVAL_COUNT,
            approved = authoritative.status in setOf(
                LoanRequestStatus.APPROVED,
                LoanRequestStatus.DISBURSED,
                LoanRequestStatus.REPAID,
                LoanRequestStatus.DEFAULTED
            ),
            rejected = false
        )
    }

    override suspend fun cancelLoanRequest(request: CancelLoanRequest): LoanCancellationResult {
        val loan = lendingDao.getLoanRequestById(request.requestId)
            ?: throw IllegalArgumentException("loan_request_not_found")
        if (loan.status !in setOf(LoanRequestStatus.PENDING, LoanRequestStatus.APPROVED, LoanRequestStatus.REJECTED)) {
            throw IllegalStateException("loan_request_not_cancellable")
        }
        if (hasFundedSibling(loan) || loan.status in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.REPAID)) {
            throw IllegalStateException("loan_request_already_funded_elsewhere")
        }

        val channel = getChannel(loan.lendingId)
        val actorIsBorrower = loan.borrowerType == BorrowerType.INDIVIDUAL && loan.borrowerPeerId == request.actorPeerId
        val actorIsAdmin = request.actorIsAdmin || channel.creatorPeerId == request.actorPeerId
        if (!actorIsBorrower && !actorIsAdmin) {
            throw IllegalStateException("borrower_or_admin_only_cancellation")
        }

        val familyRootId = familyRootRequestId(loan)
        val linkedRequests = lendingDao.getLinkedLoanRequests(familyRootId)
        if (linkedRequests.any { it.status in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.REPAID, LoanRequestStatus.DEFAULTED, LoanRequestStatus.FUNDED_ELSEWHERE) }) {
            throw IllegalStateException("loan_request_not_cancellable")
        }

        val cancelledRequests = linkedRequests.map { linked ->
            when (linked.status) {
                LoanRequestStatus.PENDING,
                LoanRequestStatus.APPROVED,
                LoanRequestStatus.REJECTED -> linked.copy(status = LoanRequestStatus.CANCELLED)
                else -> linked
            }
        }
        cancelledRequests.forEach { lendingDao.upsertLoanRequest(it) }
        cancelledRequests.map { it.lendingId }.distinct().forEach { refreshPoolSnapshot(it) }

        val updated = cancelledRequests.firstOrNull { it.requestId == loan.requestId } ?: loan.copy(status = LoanRequestStatus.CANCELLED)
        return LoanCancellationResult(
            request = updated,
            affectedRequests = cancelledRequests
        )
    }

    override suspend fun disburseApprovedLoan(requestId: String): LoanRequestEntity {
        val loan = lendingDao.getLoanRequestById(requestId)
            ?: throw IllegalArgumentException("loan_request_not_found")
        if (loan.status != LoanRequestStatus.APPROVED) {
            throw IllegalStateException("loan_request_not_ready_for_disbursement")
        }
        if (hasFundedSibling(loan)) {
            throw IllegalStateException("loan_request_already_funded_elsewhere")
        }
        val finalized = finalizeApprovedLoan(loan)
        if (finalized.status in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.REPAID)) {
            markSiblingRequestsFundedElsewhere(finalized)
        }
        refreshPoolSnapshot(loan.lendingId)
        return finalized
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
        val escrowAccount = escrowService.getEscrowAccount(loan.lendingId)
        val queueResult = if (escrowAccount?.vaultAddress?.isNotBlank() == true) {
            transferGateway.queueSplTransfer(
                recipientPublicKey = escrowAccount.vaultAddress,
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
        val updatedLoan = if (lendingOnChainService.isEnabled()) {
            val onChain = lendingOnChainService.recordLoanRepaymentOnChain(
                RecordLoanRepaymentOnChainParams(
                    lendingId = loan.lendingId,
                    requestId = loan.requestId,
                    amount = request.amount,
                    paidAt = repayment.paidAt
                )
            ).getOrElse { throw it }
            val submitted = loan.copy(
                channelPda = loan.channelPda ?: onChain.channelPda,
                loanRequestPda = loan.loanRequestPda ?: onChain.loanRequestPda,
                lastChainSyncSignature = onChain.txSignature,
                lastChainSyncedSlot = onChain.slot,
                chainStatus = onChain.chainStatus
            )
            lendingDao.upsertLoanRequest(submitted)
            syncLoanRequestFromChain(loan.lendingId, loan.requestId, submitted) ?: submitted
        } else {
            val repayments = lendingDao.getRepaymentsForRequest(loan.requestId)
            val totalRepaid = repayments.sumOf { it.amount }
            val totalDue = loan.principalAmount + calculateInterestAmount(loan)
            val remainingBalance = max(totalDue - totalRepaid, 0L)
            val localUpdatedLoan = if (remainingBalance == 0L) {
                loan.copy(status = LoanRequestStatus.REPAID)
            } else {
                loan
            }
            if (localUpdatedLoan != loan) {
                lendingDao.upsertLoanRequest(localUpdatedLoan)
            }
            localUpdatedLoan
        }

        val repayments = lendingDao.getRepaymentsForRequest(loan.requestId)
        val totalRepaid = repayments.sumOf { it.amount }
        val totalDue = updatedLoan.principalAmount + calculateInterestAmount(updatedLoan)
        val remainingBalance = max(totalDue - totalRepaid, 0L)
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
        val totalRepayments = repaymentsByRequest.values.sum()
        val reservedAmount = loanRequests
            .filter { it.status == LoanRequestStatus.APPROVED }
            .sumOf { request ->
                max(request.principalAmount - (repaymentsByRequest[request.requestId] ?: 0L), 0L)
            }
        val disbursedAmount = loanRequests
            .filter { it.status in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.REPAID, LoanRequestStatus.DEFAULTED) }
            .sumOf { request ->
                max(request.principalAmount - (repaymentsByRequest[request.requestId] ?: 0L), 0L)
            }

        val snapshot = LendingPoolSnapshotEntity(
            lendingId = lendingId,
            totalStakedAmount = activeStake,
            reservedAmount = reservedAmount,
            disbursedAmount = disbursedAmount,
            availableLiquidityAmount = max(activeStake + totalRepayments - reservedAmount - disbursedAmount, 0L),
            updatedAt = System.currentTimeMillis()
        )
        lendingDao.upsertPoolSnapshot(snapshot)
        return snapshot
    }

    private suspend fun getChannel(lendingId: String) =
        lendingDao.getLendingChannelById(lendingId) ?: throw IllegalStateException("lending_channel_not_found")

    private suspend fun syncLoanRequestFromChain(
        lendingId: String,
        requestId: String,
        fallback: LoanRequestEntity? = null
    ): LoanRequestEntity? {
        if (!lendingOnChainService.isEnabled()) return fallback ?: lendingDao.getLoanRequestById(requestId)
        val base = fallback ?: lendingDao.getLoanRequestById(requestId) ?: return null
        val chainState = lendingOnChainService.fetchLoanRequestState(lendingId, requestId).getOrNull() ?: return base
        val synced = mergeChainState(base, chainState)
        lendingDao.upsertLoanRequest(synced)
        return synced
    }

    private suspend fun hasConfiguredSquad(lendingId: String, loan: LoanRequestEntity? = null): Boolean {
        if (!loan?.squadsMultisigAddress.isNullOrBlank() && !loan?.squadsVaultAddress.isNullOrBlank()) {
            return true
        }
        return squadsService.resolveLendingSquad(lendingId).isSuccess
    }

    private suspend fun hasFundedSibling(loan: LoanRequestEntity): Boolean {
        return lendingDao.getFundedSiblingLoanRequest(
            familyRootRequestId = familyRootRequestId(loan),
            requestId = loan.requestId
        ) != null
    }

    private suspend fun markSiblingRequestsFundedElsewhere(fundedLoan: LoanRequestEntity) {
        val familyRootId = familyRootRequestId(fundedLoan)
        val siblings = lendingDao.getLinkedLoanRequests(familyRootId)
        val affectedLendingIds = linkedSetOf(fundedLoan.lendingId)
        siblings.forEach { sibling ->
            if (sibling.requestId == fundedLoan.requestId) {
                val updatedFunded = sibling.copy(fundingLendingId = fundedLoan.lendingId)
                if (updatedFunded != sibling) {
                    lendingDao.upsertLoanRequest(updatedFunded)
                    affectedLendingIds += updatedFunded.lendingId
                }
            } else if (sibling.status !in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.REPAID)) {
                lendingDao.upsertLoanRequest(
                    sibling.copy(
                        status = LoanRequestStatus.FUNDED_ELSEWHERE,
                        fundingLendingId = fundedLoan.lendingId
                    )
                )
                affectedLendingIds += sibling.lendingId
            }
        }
        affectedLendingIds.forEach { lendingId -> refreshPoolSnapshot(lendingId) }
    }

    private suspend fun finalizeApprovedLoan(loan: LoanRequestEntity): LoanRequestEntity {
        if (hasConfiguredSquad(loan.lendingId, loan)) {
            val squadResult = runCatching {
                val proposalState = squadsService.fetchLoanProposalState(loan.lendingId, loan.requestId).getOrThrow()
                    ?: squadsService.createLoanProposal(loan.lendingId, loan.requestId).getOrThrow()
                val synced = syncLoanRequestFromSquads(
                    loan.lendingId,
                    loan.requestId,
                    mergeSquadsProposalState(loan, proposalState)
                ) ?: loan
                when (synced.status) {
                    LoanRequestStatus.DISBURSED -> synced
                    else -> synced.copy(
                        status = LoanRequestStatus.APPROVED,
                        approvedAt = synced.approvedAt ?: loan.approvedAt ?: System.currentTimeMillis()
                    ).also { lendingDao.upsertLoanRequest(it) }
                }
            }
            if (squadResult.isSuccess) {
                return squadResult.getOrThrow()
            }
        }

        val proposal = escrowService.createLoanDisbursementProposal(loan.requestId)
        if (proposal.custodyExecutionStatus == CustodyExecutionStatus.EXECUTED) {
            val disbursed = loan.copy(
                status = LoanRequestStatus.DISBURSED,
                disbursedAt = System.currentTimeMillis()
            )
            lendingDao.upsertLoanRequest(disbursed)
            return disbursed
        }
        return loan
    }

    private suspend fun syncLoanRequestFromSquads(
        lendingId: String,
        requestId: String,
        fallback: LoanRequestEntity? = null
    ): LoanRequestEntity? {
        val base = fallback ?: lendingDao.getLoanRequestById(requestId) ?: return null
        val proposalState = squadsService.fetchLoanProposalState(lendingId, requestId).getOrNull() ?: return base
        val synced = mergeSquadsProposalState(base, proposalState)
        lendingDao.upsertLoanRequest(synced)
        mirrorSquadsProposal(synced, proposalState)
        return synced
    }

    private fun mergeSquadsProposalState(
        base: LoanRequestEntity,
        proposalState: SquadsProposalState
    ): LoanRequestEntity {
        val mappedStatus = when (proposalState.status) {
            SQUADS_PROPOSAL_STATUS_EXECUTED -> LoanRequestStatus.DISBURSED
            SQUADS_PROPOSAL_STATUS_APPROVED -> LoanRequestStatus.APPROVED
            SQUADS_PROPOSAL_STATUS_REJECTED,
            SQUADS_PROPOSAL_STATUS_CANCELLED -> LoanRequestStatus.REJECTED
            else -> LoanRequestStatus.PENDING
        }
        return base.copy(
            status = moreAdvancedLoanStatus(base.status, mappedStatus),
            approvedAt = proposalState.approvedAt ?: base.approvedAt,
            disbursedAt = proposalState.executedAt ?: base.disbursedAt,
            fundingLendingId = if (mappedStatus == LoanRequestStatus.DISBURSED) base.lendingId else base.fundingLendingId,
            squadsMultisigAddress = proposalState.multisigAddress,
            squadsVaultAddress = proposalState.vaultAddress,
            squadsProposalAddress = proposalState.proposalAddress,
            squadsTransactionIndex = proposalState.transactionIndex,
            lastChainSyncSignature = proposalState.txSignature ?: base.lastChainSyncSignature,
            chainStatus = LoanChainStatus.CONFIRMED
        )
    }

    private suspend fun mirrorSquadsProposal(
        loan: LoanRequestEntity,
        proposalState: SquadsProposalState
    ) {
        val channel = getChannel(loan.lendingId)
        val targetWallet = if (loan.borrowerType == BorrowerType.INDIVIDUAL) {
            loan.borrowerPeerId?.let { peerId -> lendingDao.getMembership(loan.lendingId, peerId)?.walletAddress }
                ?: channel.creatorWalletAddress
        } else {
            channel.creatorWalletAddress
        }
        val existing = lendingDao.getEscrowProposalsForRequest(loan.requestId)
            .firstOrNull { it.proposalType == EscrowProposalType.LOAN_DISBURSEMENT }
        val mirrored = (existing ?: LendingEscrowProposalEntity(
            proposalId = proposalState.proposalAddress,
            lendingId = loan.lendingId,
            requestId = loan.requestId,
            proposalType = EscrowProposalType.LOAN_DISBURSEMENT,
            appApprovalStatus = AppProposalStatus.PENDING,
            custodyExecutionStatus = CustodyExecutionStatus.CREATED,
            targetWalletAddress = targetWallet,
            mintAddress = channel.stakeTokenMint,
            amountAtomic = loan.principalAmount
        )).copy(
            proposalId = proposalState.proposalAddress,
            appApprovalStatus = when (proposalState.status) {
                SQUADS_PROPOSAL_STATUS_REJECTED,
                SQUADS_PROPOSAL_STATUS_CANCELLED -> AppProposalStatus.REJECTED
                SQUADS_PROPOSAL_STATUS_APPROVED,
                SQUADS_PROPOSAL_STATUS_EXECUTED -> AppProposalStatus.APPROVED
                else -> AppProposalStatus.PENDING
            },
            custodyExecutionStatus = when (proposalState.status) {
                SQUADS_PROPOSAL_STATUS_EXECUTED -> CustodyExecutionStatus.EXECUTED
                SQUADS_PROPOSAL_STATUS_REJECTED,
                SQUADS_PROPOSAL_STATUS_CANCELLED -> CustodyExecutionStatus.FAILED
                else -> CustodyExecutionStatus.CREATED
            },
            targetWalletAddress = targetWallet,
            mintAddress = channel.stakeTokenMint,
            amountAtomic = loan.principalAmount,
            txSignature = proposalState.txSignature,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
        lendingDao.upsertEscrowProposal(mirrored)
    }

    private fun mergeChainState(
        base: LoanRequestEntity,
        chainState: OnChainLoanRequestState
    ): LoanRequestEntity {
        return base.copy(
            status = chainState.chainStatus,
            approvedAt = chainState.approvedAt ?: base.approvedAt,
            disbursedAt = chainState.disbursedAt ?: base.disbursedAt,
            fundingLendingId = if (chainState.chainStatus == LoanRequestStatus.DISBURSED) base.lendingId else base.fundingLendingId,
            channelPda = chainState.channelPda,
            loanRequestPda = chainState.loanRequestPda,
            lastChainSyncSignature = chainState.txSignature ?: base.lastChainSyncSignature,
            lastChainSyncedSlot = chainState.slot ?: base.lastChainSyncedSlot,
            chainStatus = if (chainState.slot != null) LoanChainStatus.CONFIRMED else LoanChainStatus.SUBMITTED
        )
    }

    private fun calculateInterestAmount(request: LoanRequestEntity): Long {
        return (request.principalAmount * request.interestBps) / 10_000L
    }

    private fun moreAdvancedLoanStatus(current: String, incoming: String): String {
        val rank = mapOf(
            LoanRequestStatus.PENDING to 0,
            LoanRequestStatus.APPROVED to 1,
            LoanRequestStatus.FUNDED_ELSEWHERE to 2,
            LoanRequestStatus.DISBURSED to 3,
            LoanRequestStatus.REPAID to 4,
            LoanRequestStatus.REJECTED to 5,
            LoanRequestStatus.DEFAULTED to 6,
            LoanRequestStatus.CANCELLED to 7
        )
        return if ((rank[incoming] ?: 0) >= (rank[current] ?: 0)) incoming else current
    }

    private fun nextRequestId(): String = "LR-${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"

    private fun nextRepaymentId(): String = "RP-${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"

    private fun getEndorserPeerIds(request: LoanRequestEntity): List<String> {
        val listType = object : TypeToken<List<String>>() {}.type
        return runCatching {
            gson.fromJson<List<String>>(request.endorserPeerIdsJson, listType)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
