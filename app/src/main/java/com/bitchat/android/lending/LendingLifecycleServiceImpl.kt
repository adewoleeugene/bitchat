package com.bitchat.android.lending

import com.bitchat.android.data.local.LendingDao
import com.bitchat.android.data.local.entities.BorrowerType
import com.bitchat.android.data.local.entities.CustodyExecutionStatus
import com.bitchat.android.data.local.entities.EscrowTransferStatus
import com.bitchat.android.data.local.entities.LendingMemberStatus
import com.bitchat.android.data.local.entities.LendingChannelEntity
import com.bitchat.android.data.local.entities.LendingMembershipEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import com.bitchat.android.data.local.entities.LendingSignerReviewEntity
import com.bitchat.android.data.local.entities.LendingSignerReviewStatus
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
import com.bitchat.android.data.models.TransactionStatus
import com.bitchat.android.solana.LendingTransferGateway
import com.bitchat.android.solana.SolanaRpcService
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
    private val rpcService: SolanaRpcService
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

    override suspend fun reconcileLoanRequestState(requestId: String): LoanRequestEntity? {
        val loan = lendingDao.getLoanRequestById(requestId) ?: return null
        val reconciled = if (hasConfiguredSquad(loan.lendingId, loan) || !loan.squadsProposalAddress.isNullOrBlank()) {
            syncLoanRequestFromSquads(loan.lendingId, loan.requestId, loan)
        } else {
            loan
        } ?: loan
        if (reconciled.status in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.REPAID)) {
            markSiblingRequestsFundedElsewhere(reconciled)
        }
        refreshPoolSnapshot(reconciled.lendingId)
        return lendingDao.getLoanRequestById(requestId) ?: reconciled
    }

    override suspend fun getSignerReview(requestId: String): LendingSignerReviewEntity? {
        return lendingDao.getSignerReviewForRequest(requestId)
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
            chainStatus = LoanChainStatus.LOCAL_ONLY
        )
        lendingDao.upsertLoanRequest(entity)
        refreshPoolSnapshot(channel.lendingId)
        return entity
    }

    override suspend fun openSignerReview(request: OpenSignerReviewRequest): SignerReviewResult {
        val loan = lendingDao.getLoanRequestById(request.requestId)
            ?: throw IllegalArgumentException("loan_request_not_found")
        val channel = getChannel(loan.lendingId)
        val actorIsAdmin = request.actorIsAdmin || channel.creatorPeerId == request.actorPeerId
        if (!actorIsAdmin) {
            throw IllegalStateException("admin_only_signer_review")
        }
        if (loan.status !in setOf(
                LoanRequestStatus.COMMUNITY_APPROVED,
                LoanRequestStatus.SIGNER_REVIEW,
                LoanRequestStatus.SIGNER_APPROVED
            )
        ) {
            throw IllegalStateException("loan_request_not_ready_for_signer_review")
        }
        requireProductionCustody(loan.lendingId)

        var workingLoan = if (loan.status == LoanRequestStatus.COMMUNITY_APPROVED) {
            loan.copy(status = LoanRequestStatus.SIGNER_REVIEW)
                .also { lendingDao.upsertLoanRequest(it) }
        } else {
            loan
        }

        val existingReview = lendingDao.getSignerReviewForRequest(loan.requestId)
        if (existingReview != null) {
            return SignerReviewResult(
                request = workingLoan,
                review = existingReview,
                created = false
            )
        }

        syncLoanRequestFromSquads(loan.lendingId, loan.requestId, workingLoan)?.let { synced ->
            workingLoan = synced
        }
        if (workingLoan.squadsProposalAddress.isNullOrBlank()) {
            val proposalState = squadsService.createLoanProposal(loan.lendingId, loan.requestId).getOrThrow()
            val merged = mergeSquadsProposalState(workingLoan, proposalState)
            lendingDao.upsertLoanRequest(merged)
            mirrorSquadsProposal(merged, proposalState)
            workingLoan = merged
        }

        val review = buildSignerReviewEntity(
            existing = null,
            loan = workingLoan,
            createdByPeerId = request.actorPeerId
        )
        lendingDao.upsertSignerReview(review)
        return SignerReviewResult(
            request = workingLoan,
            review = review,
            created = true
        )
    }

    override suspend fun authorizeSignerReview(request: AuthorizeSignerReviewRequest): SignerReviewResult {
        val loan = lendingDao.getLoanRequestById(request.requestId)
            ?: throw IllegalArgumentException("loan_request_not_found")
        val channel = getChannel(loan.lendingId)
        val actorIsApprover = request.actorIsApprover || channel.creatorPeerId == request.actorPeerId
        if (!actorIsApprover) {
            throw IllegalStateException("approver_only_signer_authorization")
        }
        if (loan.status !in setOf(LoanRequestStatus.SIGNER_REVIEW, LoanRequestStatus.SIGNER_APPROVED)) {
            throw IllegalStateException("loan_request_not_ready_for_signer_authorization")
        }
        requireProductionCustody(loan.lendingId)

        val existingReview = lendingDao.getSignerReviewForRequest(loan.requestId)
            ?: throw IllegalStateException("signer_review_not_open")

        var workingLoan = loan
        if (workingLoan.squadsProposalAddress.isNullOrBlank()) {
            workingLoan = openSignerReview(
                OpenSignerReviewRequest(
                    requestId = request.requestId,
                    actorPeerId = request.actorPeerId,
                    actorIsAdmin = true
                )
            ).request
        }
        val proposalState = squadsService.approveLoanProposal(loan.lendingId, loan.requestId).getOrThrow()
        workingLoan = mergeSquadsProposalState(workingLoan, proposalState)
        lendingDao.upsertLoanRequest(workingLoan)
        mirrorSquadsProposal(workingLoan, proposalState)

        val review = buildSignerReviewEntity(
            existing = existingReview,
            loan = workingLoan,
            createdByPeerId = existingReview.createdByPeerId
        )
        lendingDao.upsertSignerReview(review)
        refreshPoolSnapshot(workingLoan.lendingId)
        return SignerReviewResult(
            request = workingLoan,
            review = review,
            created = false
        )
    }

    override suspend fun forwardLoanRequest(request: ForwardLoanRequest): LoanRequestEntity {
        throw IllegalStateException("forwarding_disabled_phase_one")
    }

    override suspend fun importDiscoveredLoanRequest(message: LendingLoanRequestMessage, senderPeerId: String?): LoanRequestEntity? {
        val channel = lendingChannelService.getChannelByLendingId(message.lendingId) ?: return null
        val borrowerPeerId = message.borrowerPeerId ?: return null
        val borrowerMembership = getConfirmedActiveMembership(channel.lendingId, borrowerPeerId) ?: return null
        val actorPeerId = message.actorPeerId?.trim().takeUnless { it.isNullOrBlank() } ?: borrowerPeerId
        if (senderPeerId.isNullOrBlank() || senderPeerId != actorPeerId) return null
        val existing = lendingDao.getLoanRequestById(message.requestId)
        if (!canImportLoanRequestMessage(channel, existing, message, actorPeerId, borrowerMembership)) return null
        val entity = (existing ?: LoanRequestEntity(
            requestId = message.requestId,
            lendingId = channel.lendingId,
            borrowerType = BorrowerType.INDIVIDUAL,
            borrowerPeerId = borrowerPeerId,
            borrowerWalletAddress = borrowerMembership.walletAddress,
            principalAmount = message.principalAmount,
            interestBps = message.interestBps,
            durationDays = message.durationDays,
            purpose = message.purpose.trim(),
            status = LoanRequestStatus.PENDING,
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
            borrowerPeerId = borrowerPeerId,
            borrowerWalletAddress = borrowerMembership.walletAddress,
            principalAmount = existing?.principalAmount ?: message.principalAmount,
            interestBps = existing?.interestBps ?: message.interestBps,
            durationDays = existing?.durationDays ?: message.durationDays,
            purpose = existing?.purpose ?: message.purpose.trim(),
            status = existing?.status ?: LoanRequestStatus.PENDING,
            requestedAt = existing?.requestedAt ?: message.requestedAt,
            dueAt = existing?.dueAt ?: (message.requestedAt + message.durationDays * 24L * 60L * 60L * 1000L),
            parentRequestId = existing?.parentRequestId ?: message.parentRequestId,
            requestKind = existing?.requestKind ?: message.requestKind,
            originLendingId = existing?.originLendingId ?: message.originLendingId ?: message.lendingId,
            forwardedFromRequestId = existing?.forwardedFromRequestId ?: message.forwardedFromRequestId,
            fundingLendingId = existing?.fundingLendingId
        )
        val persisted = entity
        lendingDao.upsertLoanRequest(persisted)
        if (persisted.status in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.REPAID)) {
            markSiblingRequestsFundedElsewhere(persisted)
        }
        refreshPoolSnapshot(channel.lendingId)
        return persisted
    }

    override suspend fun importDiscoveredLoanVote(message: LendingLoanVoteMessage, senderPeerId: String?): LoanRequestEntity? {
        val channel = lendingChannelService.getChannelByLendingId(message.lendingId) ?: return null
        val existing = lendingDao.getLoanRequestById(message.requestId) ?: return null
        if (senderPeerId.isNullOrBlank() || senderPeerId != message.voterPeerId) return null
        getConfirmedActiveMembership(channel.lendingId, message.voterPeerId) ?: return null
        if (existing.borrowerType == BorrowerType.INDIVIDUAL && existing.borrowerPeerId == message.voterPeerId) return null
        if (existing.status != LoanRequestStatus.PENDING) return existing
        val currentVotes = lendingDao.getVotesForRequest(message.requestId)
        val currentVoteSummary = summarizeVotes(channel, existing, currentVotes)
        if (currentVoteSummary.votingClosed) {
            val settled = applyVoteSummary(existing, currentVoteSummary)
            if (settled != existing) {
                lendingDao.upsertLoanRequest(settled)
                refreshPoolSnapshot(channel.lendingId)
            }
            return settled
        }
        val voteChoice = normalizeVoteChoice(message.voteChoice) ?: return null
        lendingDao.upsertLoanVote(
            LoanVoteEntity(
                requestId = message.requestId,
                voterPeerId = message.voterPeerId,
                lendingId = channel.lendingId,
                voteChoice = voteChoice
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
        val votes = lendingDao.getVotesForRequest(message.requestId)
        val voteSummary = summarizeVotes(channel, hydrated, votes)
        val updated = applyVoteSummary(hydrated, voteSummary)
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

    override suspend fun importDiscoveredLoanRepayment(message: LendingLoanRepaymentMessage, senderPeerId: String?): LoanRepaymentResult? {
        val channel = lendingChannelService.getChannelByLendingId(message.lendingId) ?: return null
        val loan = lendingDao.getLoanRequestById(message.requestId) ?: return null
        if (senderPeerId.isNullOrBlank() || senderPeerId != message.payerPeerId) return null
        if (loan.borrowerType == BorrowerType.INDIVIDUAL && loan.borrowerPeerId != message.payerPeerId) return null
        getConfirmedActiveMembership(channel.lendingId, message.payerPeerId) ?: return null
        if (loan.status !in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.DEFAULTED, LoanRequestStatus.REPAID)) return null
        val canonicalStatus = canonicalRepaymentStatus(
            txSignature = message.txSignature,
            announcedStatus = message.txStatus
        )
        val repayment = upsertRepaymentWithSignatureDedup(
            LoanRepaymentEntity(
                repaymentId = message.repaymentId,
                requestId = message.requestId,
                lendingId = channel.lendingId,
                amount = message.amount,
                txSignature = message.txSignature,
                txStatus = canonicalStatus,
                paidAt = message.paidAt
            )
        )
        val repayments = lendingDao.getRepaymentsForRequest(loan.requestId)
        val totalRepaid = confirmedRepaymentTotal(repayments)
        val totalDue = loan.principalAmount + calculateInterestAmount(loan)
        val remainingBalance = max(totalDue - totalRepaid, 0L)
        reconcileRepaymentBackedLoan(loan.requestId)
        val updatedLoan = lendingDao.getLoanRequestById(loan.requestId) ?: loan
        refreshPoolSnapshot(channel.lendingId)
        return LoanRepaymentResult(
            repayment = repayment,
            updatedRequest = updatedLoan,
            totalRepaidAmount = totalRepaid,
            remainingBalance = remainingBalance
        )
    }

    override suspend fun castVote(request: CastLoanVoteRequest): LoanVoteResult {
        val loan = lendingDao.getLoanRequestById(request.requestId)
            ?: throw IllegalArgumentException("loan_request_not_found")
        if (loan.status != LoanRequestStatus.PENDING) {
            throw IllegalStateException("loan_request_not_open_for_voting")
        }
        requireActiveMembership(loan.lendingId, request.voterPeerId)
        if (hasFundedSibling(loan)) {
            throw IllegalStateException("loan_request_already_funded_elsewhere")
        }
        if (loan.borrowerType == BorrowerType.INDIVIDUAL && loan.borrowerPeerId == request.voterPeerId) {
            throw IllegalStateException("borrower_cannot_vote_own_request")
        }
        val channel = getChannel(loan.lendingId)
        val currentVotes = lendingDao.getVotesForRequest(loan.requestId)
        val currentVoteSummary = summarizeVotes(channel, loan, currentVotes)
        if (currentVoteSummary.votingClosed) {
            val settled = applyVoteSummary(loan, currentVoteSummary)
            if (settled != loan) {
                lendingDao.upsertLoanRequest(settled)
                refreshPoolSnapshot(loan.lendingId)
            }
            throw IllegalStateException("loan_request_voting_closed")
        }

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
        val voteSummary = summarizeVotes(channel, loan, votes)
        val updatedRequest = applyVoteSummary(loan, voteSummary)
        if (updatedRequest != loan) {
            lendingDao.upsertLoanRequest(updatedRequest)
        }
        refreshPoolSnapshot(loan.lendingId)

        return LoanVoteResult(
            request = updatedRequest,
            votes = votes,
            quorumReached = voteSummary.quorumReached,
            approved = voteSummary.approved,
            rejected = voteSummary.rejected
        )
    }

    override suspend fun cancelLoanRequest(request: CancelLoanRequest): LoanCancellationResult {
        val loan = lendingDao.getLoanRequestById(request.requestId)
            ?: throw IllegalArgumentException("loan_request_not_found")
        if (loan.status !in setOf(
                LoanRequestStatus.PENDING,
                LoanRequestStatus.COMMUNITY_APPROVED,
                LoanRequestStatus.COMMUNITY_REJECTED,
                LoanRequestStatus.SIGNER_REVIEW,
                LoanRequestStatus.SIGNER_APPROVED,
                LoanRequestStatus.SIGNER_REJECTED
            )
        ) {
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
                LoanRequestStatus.COMMUNITY_APPROVED,
                LoanRequestStatus.COMMUNITY_REJECTED,
                LoanRequestStatus.SIGNER_REVIEW,
                LoanRequestStatus.SIGNER_APPROVED,
                LoanRequestStatus.SIGNER_REJECTED -> linked.copy(status = LoanRequestStatus.CANCELLED)
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

    override suspend fun disburseApprovedLoan(request: DisburseApprovedLoanRequest): LoanRequestEntity {
        var loan = lendingDao.getLoanRequestById(request.requestId)
            ?: throw IllegalArgumentException("loan_request_not_found")
        requireProductionCustody(loan.lendingId)
        syncLoanRequestFromSquads(loan.lendingId, loan.requestId, loan)?.let { synced ->
            loan = synced
        }
        if (loan.status != LoanRequestStatus.SIGNER_APPROVED) {
            throw IllegalStateException("loan_request_not_ready_for_disbursement")
        }
        if (hasFundedSibling(loan)) {
            throw IllegalStateException("loan_request_already_funded_elsewhere")
        }
        val channel = getChannel(loan.lendingId)
        val actorIsAdmin = request.actorIsAdmin || channel.creatorPeerId == request.actorPeerId
        if (!actorIsAdmin) {
            throw IllegalStateException("admin_only_disbursement")
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
        requireProductionCustody(loan.lendingId)
        if (request.amount <= 0L) throw IllegalArgumentException("repayment_amount_must_be_positive")
        if (loan.status !in setOf(
                LoanRequestStatus.DISBURSED,
                LoanRequestStatus.PARTIALLY_REPAID,
                LoanRequestStatus.OVERDUE,
                LoanRequestStatus.DEFAULTED
            )
        ) {
            throw IllegalStateException("loan_request_not_repayable")
        }

        if (loan.borrowerType == BorrowerType.INDIVIDUAL && loan.borrowerPeerId != request.payerPeerId) {
            throw IllegalStateException("only_borrower_can_repay_individual_loan")
        }

        val channel = getChannel(loan.lendingId)
        val outstandingBeforeQueue = outstandingBalanceForQueueing(loan)
        if (outstandingBeforeQueue <= 0L) {
            throw IllegalStateException("loan_already_repaid")
        }
        if (request.amount > outstandingBeforeQueue) {
            throw IllegalArgumentException("repayment_amount_exceeds_outstanding_balance")
        }
        val escrowAccount = escrowService.getEscrowAccount(loan.lendingId)
        val vaultAddress = escrowAccount?.vaultAddress?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("repayment_vault_not_configured")
        val queueResult = if (isNativeSolStakeAsset(channel.stakeTokenMint, channel.stakeTokenSymbol)) {
            transferGateway.queueNativeTransfer(
                recipientPublicKey = vaultAddress,
                amountLamports = request.amount,
                memo = "loan repayment ${loan.requestId}"
            )
        } else {
            transferGateway.queueSplTransfer(
                recipientPublicKey = vaultAddress,
                mintAddress = channel.stakeTokenMint,
                amountAtomic = request.amount,
                decimals = channel.stakeTokenDecimals,
                symbol = channel.stakeTokenSymbol.ifBlank { "TOKEN" },
                memo = "loan repayment ${loan.requestId}"
            )
        }
        queueResult.exceptionOrNull()?.let { throw it }

        val repayment = upsertRepaymentWithSignatureDedup(
            LoanRepaymentEntity(
            repaymentId = nextRepaymentId(),
            requestId = loan.requestId,
            lendingId = loan.lendingId,
            amount = request.amount,
            txSignature = queueResult.getOrThrow(),
            txStatus = EscrowTransferStatus.PENDING
            )
        )
        val updatedLoan = loan

        val repayments = lendingDao.getRepaymentsForRequest(loan.requestId)
        val totalRepaid = confirmedRepaymentTotal(repayments)
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

    override suspend fun repairRepaymentsForTransaction(
        queuedTransactionId: String,
        transactionStatus: String,
        txSignature: String?
    ): List<LoanRepaymentEntity> {
        val repayments = lendingDao.getRepaymentsByTxReference(queuedTransactionId)
        if (repayments.isEmpty()) return emptyList()

        val mappedStatus = when (transactionStatus) {
            TransactionStatus.CONFIRMED.value -> EscrowTransferStatus.CONFIRMED
            TransactionStatus.FAILED.value -> EscrowTransferStatus.FAILED
            else -> return emptyList()
        }

        val updatedRepayments = repayments.map { repayment ->
            upsertRepaymentWithSignatureDedup(
                repayment.copy(
                txSignature = txSignature ?: repayment.txSignature,
                txStatus = mappedStatus,
                paidAt = System.currentTimeMillis()
            )
            )
        }

        updatedRepayments
            .map { it.requestId }
            .distinct()
            .forEach { requestId -> reconcileRepaymentBackedLoan(requestId) }

        updatedRepayments
            .map { it.lendingId }
            .distinct()
            .forEach { lendingId -> refreshPoolSnapshot(lendingId) }

        return updatedRepayments
    }

    override suspend fun leaveChannel(request: LeaveLendingChannelRequest): LendingLeaveResult {
        val channel = lendingChannelService.getChannelByIdentifier(request.identifier, request.preferredChannelKey)
            ?: throw IllegalArgumentException("lending_channel_not_found")
        val membership = requireActiveMembership(channel.lendingId, request.memberPeerId)

        val blockingLoan = lendingDao.getLoanRequestsForLendingChannel(channel.lendingId).firstOrNull { loan ->
            loan.borrowerType == BorrowerType.INDIVIDUAL &&
                loan.borrowerPeerId == request.memberPeerId &&
                loan.status in setOf(
                    LoanRequestStatus.PENDING,
                    LoanRequestStatus.COMMUNITY_APPROVED,
                    LoanRequestStatus.SIGNER_REVIEW,
                    LoanRequestStatus.SIGNER_APPROVED,
                    LoanRequestStatus.DISBURSED,
                    LoanRequestStatus.DEFAULTED
                )
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

    private suspend fun canImportLoanRequestMessage(
        channel: LendingChannelEntity,
        existing: LoanRequestEntity?,
        message: LendingLoanRequestMessage,
        actorPeerId: String,
        borrowerMembership: LendingMembershipEntity
    ): Boolean {
        if (message.requestKind == LoanRequestKind.FORWARDED_COPY) {
            val sourceRequestId = message.forwardedFromRequestId ?: return false
            val sourceRequest = lendingDao.getLoanRequestById(sourceRequestId) ?: return false
            if (sourceRequest.borrowerPeerId != borrowerMembership.memberPeerId) return false
            val sourceChannel = lendingChannelService.getChannelByLendingId(sourceRequest.lendingId) ?: return false
            if (sourceChannel.creatorPeerId != actorPeerId) return false
            return true
        }

        if (existing == null) {
            return actorPeerId == borrowerMembership.memberPeerId
        }

        val actorIsBorrower = actorPeerId == existing.borrowerPeerId
        val actorIsAdmin = channel.creatorPeerId == actorPeerId
        return actorIsBorrower || actorIsAdmin
    }

    private suspend fun requireActiveMembership(
        lendingId: String,
        memberPeerId: String
    ): LendingMembershipEntity {
        return getConfirmedActiveMembership(lendingId, memberPeerId)
            ?: throw IllegalStateException(
                if (lendingDao.getMembership(lendingId, memberPeerId) == null) "membership_not_found" else "membership_not_active"
            )
    }

    private suspend fun getConfirmedActiveMembership(
        lendingId: String,
        memberPeerId: String
    ): LendingMembershipEntity? {
        val membership = lendingDao.getMembership(lendingId, memberPeerId) ?: return null
        return membership.takeIf {
            it.joinStatus == LendingMemberStatus.ACTIVE && it.depositStatus == EscrowTransferStatus.CONFIRMED
        }
    }

    private suspend fun refreshPoolSnapshot(lendingId: String): LendingPoolSnapshotEntity {
        val memberships = lendingDao.getMembershipsForLendingChannel(lendingId)
        val activeStake = memberships
            .filter { it.joinStatus == LendingMemberStatus.ACTIVE && it.depositStatus == EscrowTransferStatus.CONFIRMED }
            .sumOf { it.stakeAmount }
        val loanRequests = lendingDao.getLoanRequestsForLendingChannel(lendingId)
        val repaymentsByRequest = loanRequests.associate { request ->
            request.requestId to confirmedRepaymentTotal(lendingDao.getRepaymentsForRequest(request.requestId))
        }
        val totalRepayments = repaymentsByRequest.values.sum()
        val reservedAmount = loanRequests
            .filter { it.status == LoanRequestStatus.SIGNER_APPROVED }
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

    private suspend fun hasConfiguredSquad(lendingId: String, loan: LoanRequestEntity? = null): Boolean {
        if (!loan?.squadsMultisigAddress.isNullOrBlank() && !loan?.squadsVaultAddress.isNullOrBlank()) {
            return true
        }
        return squadsService.resolveLendingSquad(lendingId).isSuccess
    }

    private suspend fun requireProductionCustody(lendingId: String) {
        squadsService.resolveLendingSquad(lendingId).getOrElse { error ->
            when (error.message) {
                "squad_not_configured", "squad_vault_not_configured" ->
                    throw IllegalStateException("shared_custody_required")
                else -> throw error
            }
        }
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
        requireProductionCustody(loan.lendingId)
        val proposalState = squadsService.fetchLoanProposalState(loan.lendingId, loan.requestId).getOrThrow()
            ?: squadsService.createLoanProposal(loan.lendingId, loan.requestId).getOrThrow()
        val synced = syncLoanRequestFromSquads(
            loan.lendingId,
            loan.requestId,
            mergeSquadsProposalState(loan, proposalState)
        ) ?: loan
        return when (synced.status) {
            LoanRequestStatus.DISBURSED -> synced
            LoanRequestStatus.SIGNER_APPROVED -> {
                val executedState = squadsService.executeLoanProposal(loan.lendingId, loan.requestId).getOrThrow()
                val executedLoan = mergeSquadsProposalState(synced, executedState)
                lendingDao.upsertLoanRequest(executedLoan)
                mirrorSquadsProposal(executedLoan, executedState)
                syncSignerReviewForLoan(executedLoan)
                executedLoan
            }
            else -> synced.copy(
                status = LoanRequestStatus.SIGNER_REVIEW,
                approvedAt = synced.approvedAt ?: loan.approvedAt ?: System.currentTimeMillis()
            ).also {
                lendingDao.upsertLoanRequest(it)
                syncSignerReviewForLoan(it)
            }
        }
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
        syncSignerReviewForLoan(synced)
        return synced
    }

    private fun mergeSquadsProposalState(
        base: LoanRequestEntity,
        proposalState: SquadsProposalState
    ): LoanRequestEntity {
        val mappedStatus = when (proposalState.status) {
            SQUADS_PROPOSAL_STATUS_EXECUTED -> LoanRequestStatus.DISBURSED
            SQUADS_PROPOSAL_STATUS_APPROVED -> LoanRequestStatus.SIGNER_APPROVED
            SQUADS_PROPOSAL_STATUS_REJECTED,
            SQUADS_PROPOSAL_STATUS_CANCELLED -> LoanRequestStatus.SIGNER_REJECTED
            else -> LoanRequestStatus.SIGNER_REVIEW
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

    private suspend fun syncSignerReviewForLoan(loan: LoanRequestEntity) {
        val existing = lendingDao.getSignerReviewForRequest(loan.requestId) ?: return
        lendingDao.upsertSignerReview(
            buildSignerReviewEntity(
                existing = existing,
                loan = loan,
                createdByPeerId = existing.createdByPeerId
            )
        )
    }

    private fun buildSignerReviewEntity(
        existing: LendingSignerReviewEntity?,
        loan: LoanRequestEntity,
        createdByPeerId: String
    ): LendingSignerReviewEntity {
        val status = when (loan.status) {
            LoanRequestStatus.SIGNER_APPROVED,
            LoanRequestStatus.DISBURSED,
            LoanRequestStatus.PARTIALLY_REPAID,
            LoanRequestStatus.REPAID,
            LoanRequestStatus.OVERDUE,
            LoanRequestStatus.DEFAULTED -> LendingSignerReviewStatus.APPROVED
            LoanRequestStatus.SIGNER_REJECTED,
            LoanRequestStatus.CANCELLED -> LendingSignerReviewStatus.REJECTED
            else -> LendingSignerReviewStatus.PENDING
        }
        return (existing ?: LendingSignerReviewEntity(
            reviewId = nextSignerReviewId(),
            lendingId = loan.lendingId,
            requestId = loan.requestId,
            createdByPeerId = createdByPeerId
        )).copy(
            lendingId = loan.lendingId,
            requestId = loan.requestId,
            createdByPeerId = createdByPeerId,
            status = status,
            squadsProposalAddress = loan.squadsProposalAddress,
            approvedAt = when (status) {
                LendingSignerReviewStatus.APPROVED -> loan.approvedAt ?: existing?.approvedAt ?: System.currentTimeMillis()
                else -> existing?.approvedAt
            },
            rejectedAt = when (status) {
                LendingSignerReviewStatus.REJECTED -> existing?.rejectedAt ?: System.currentTimeMillis()
                else -> existing?.rejectedAt
            },
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun calculateInterestAmount(request: LoanRequestEntity): Long {
        return (request.principalAmount * request.interestBps) / 10_000L
    }

    private fun confirmedRepaymentTotal(repayments: List<LoanRepaymentEntity>): Long {
        return repayments
            .filter { it.txStatus == EscrowTransferStatus.CONFIRMED }
            .distinctBy { canonicalRepaymentIdentity(it) }
            .sumOf { it.amount }
    }

    private suspend fun reconcileRepaymentBackedLoan(requestId: String) {
        val loan = lendingDao.getLoanRequestById(requestId) ?: return
        val repayments = lendingDao.getRepaymentsForRequest(requestId)
        val totalRepaid = confirmedRepaymentTotal(repayments)
        val totalDue = loan.principalAmount + calculateInterestAmount(loan)
        val now = System.currentTimeMillis()
        val nextStatus = when {
            totalRepaid >= totalDue -> LoanRequestStatus.REPAID
            loan.defaultedAt != null -> LoanRequestStatus.DEFAULTED
            totalRepaid > 0L && loan.dueAt > 0L && now > loan.dueAt -> LoanRequestStatus.OVERDUE
            totalRepaid > 0L -> LoanRequestStatus.PARTIALLY_REPAID
            loan.dueAt > 0L && now > loan.dueAt -> LoanRequestStatus.OVERDUE
            loan.status in setOf(LoanRequestStatus.PARTIALLY_REPAID, LoanRequestStatus.OVERDUE, LoanRequestStatus.REPAID) -> LoanRequestStatus.DISBURSED
            else -> loan.status
        }
        if (nextStatus != loan.status) {
            lendingDao.upsertLoanRequest(loan.copy(status = nextStatus))
        }
    }

    private suspend fun outstandingBalanceForQueueing(loan: LoanRequestEntity): Long {
        val repayments = lendingDao.getRepaymentsForRequest(loan.requestId)
        val queuedOrConfirmed = repayments
            .filter { it.txStatus != EscrowTransferStatus.FAILED }
            .distinctBy { canonicalRepaymentIdentity(it) }
            .sumOf { it.amount }
        val totalDue = loan.principalAmount + calculateInterestAmount(loan)
        return max(totalDue - queuedOrConfirmed, 0L)
    }

    private suspend fun upsertRepaymentWithSignatureDedup(repayment: LoanRepaymentEntity): LoanRepaymentEntity {
        val canonicalSignature = repayment.txSignature?.takeIf { looksLikeOnchainSignature(it) }
        val existing = canonicalSignature?.let {
            lendingDao.getRepaymentByRequestAndSignature(repayment.requestId, it)
        }
        val merged = if (existing != null && existing.repaymentId != repayment.repaymentId) {
            existing.copy(
                amount = max(existing.amount, repayment.amount),
                txStatus = moreAdvancedEscrowStatus(existing.txStatus, repayment.txStatus),
                paidAt = minOf(existing.paidAt, repayment.paidAt)
            )
        } else {
            repayment
        }
        lendingDao.insertLoanRepayment(merged)
        return merged
    }

    private suspend fun canonicalRepaymentStatus(
        txSignature: String?,
        announcedStatus: String
    ): String {
        val actualSignature = txSignature?.takeIf { looksLikeOnchainSignature(it) } ?: return EscrowTransferStatus.PENDING
        val confirmed = rpcService.confirmTransaction(actualSignature).getOrDefault(false)
        return when {
            confirmed -> EscrowTransferStatus.CONFIRMED
            announcedStatus == EscrowTransferStatus.FAILED -> EscrowTransferStatus.FAILED
            else -> EscrowTransferStatus.PENDING
        }
    }

    private fun looksLikeOnchainSignature(value: String): Boolean {
        return value.length in 80..100 && value.none { it.isWhitespace() }
    }

    private fun canonicalRepaymentIdentity(repayment: LoanRepaymentEntity): String {
        return repayment.txSignature?.takeIf { looksLikeOnchainSignature(it) }
            ?: repayment.repaymentId
    }

    private fun moreAdvancedEscrowStatus(current: String, incoming: String): String {
        fun rank(status: String): Int = when (status) {
            EscrowTransferStatus.CONFIRMED -> 3
            EscrowTransferStatus.FAILED -> 2
            EscrowTransferStatus.PENDING -> 1
            else -> 0
        }
        return if (rank(incoming) >= rank(current)) incoming else current
    }

    private suspend fun summarizeVotes(
        channel: LendingChannelEntity,
        loan: LoanRequestEntity,
        votes: List<LoanVoteEntity>,
        now: Long = System.currentTimeMillis()
    ): VoteSummary {
        val memberships = lendingDao.getMembershipsForLendingChannel(channel.lendingId)
        val eligibleVoterCount = memberships.count { membership ->
            membership.joinStatus == LendingMemberStatus.ACTIVE &&
                membership.depositStatus == EscrowTransferStatus.CONFIRMED &&
                (loan.borrowerType != BorrowerType.INDIVIDUAL || membership.memberPeerId != loan.borrowerPeerId)
        }
        val yesVotes = countLoanApprovals(votes)
        val noVotes = countLoanRejections(votes)
        val totalVotes = yesVotes + noVotes
        val quorumCount = requiredVoteQuorumCount(eligibleVoterCount, channel.quorumThresholdPercent)
        val votingDeadline = loan.requestedAt + channel.votingWindowHours.coerceAtLeast(1) * 60L * 60L * 1000L
        val votingClosed = now >= votingDeadline || (eligibleVoterCount > 0 && totalVotes >= eligibleVoterCount)
        val quorumReached = totalVotes >= quorumCount && quorumCount > 0
        val approvalPercent = if (totalVotes > 0) (yesVotes * 100) / totalVotes else 0
        val approved = votingClosed &&
            quorumReached &&
            totalVotes >= channel.minimumVoteCount.coerceAtLeast(1) &&
            yesVotes > noVotes &&
            approvalPercent >= channel.approvalThresholdPercent.coerceIn(1, 100)
        val rejected = votingClosed && !approved
        return VoteSummary(
            yesVotes = yesVotes,
            noVotes = noVotes,
            totalVotes = totalVotes,
            eligibleVoterCount = eligibleVoterCount,
            quorumCount = quorumCount,
            votingClosed = votingClosed,
            quorumReached = quorumReached,
            approved = approved,
            rejected = rejected
        )
    }

    private fun applyVoteSummary(
        loan: LoanRequestEntity,
        voteSummary: VoteSummary,
        now: Long = System.currentTimeMillis()
    ): LoanRequestEntity {
        return when {
            loan.status in setOf(
                LoanRequestStatus.DISBURSED,
                LoanRequestStatus.REPAID,
                LoanRequestStatus.DEFAULTED,
                LoanRequestStatus.CANCELLED,
                LoanRequestStatus.FUNDED_ELSEWHERE
            ) -> loan
            voteSummary.approved -> loan.copy(
                status = LoanRequestStatus.COMMUNITY_APPROVED,
                approvedAt = loan.approvedAt ?: now
            )
            voteSummary.rejected -> loan.copy(
                status = LoanRequestStatus.COMMUNITY_REJECTED
            )
            else -> loan.copy(
                status = LoanRequestStatus.PENDING
            )
        }
    }

    private fun normalizeVoteChoice(raw: String): String? {
        return when (raw.trim().uppercase()) {
            VoteChoice.YES, "APPROVE", "UPVOTE" -> VoteChoice.YES
            VoteChoice.NO, "DENY", "REJECT", "DOWNVOTE" -> VoteChoice.NO
            else -> null
        }
    }

    private fun moreAdvancedLoanStatus(current: String, incoming: String): String {
        val rank = mapOf(
            LoanRequestStatus.PENDING to 0,
            LoanRequestStatus.COMMUNITY_REJECTED to 1,
            LoanRequestStatus.COMMUNITY_APPROVED to 2,
            LoanRequestStatus.SIGNER_REVIEW to 3,
            LoanRequestStatus.SIGNER_REJECTED to 4,
            LoanRequestStatus.SIGNER_APPROVED to 5,
            LoanRequestStatus.FUNDED_ELSEWHERE to 6,
            LoanRequestStatus.DISBURSED to 7,
            LoanRequestStatus.PARTIALLY_REPAID to 8,
            LoanRequestStatus.REPAID to 9,
            LoanRequestStatus.OVERDUE to 10,
            LoanRequestStatus.DEFAULTED to 11,
            LoanRequestStatus.CANCELLED to 12
        )
        return if ((rank[incoming] ?: 0) >= (rank[current] ?: 0)) incoming else current
    }

    private fun nextSignerReviewId(): String = "SR-${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"

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

    private data class VoteSummary(
        val yesVotes: Int,
        val noVotes: Int,
        val totalVotes: Int,
        val eligibleVoterCount: Int,
        val quorumCount: Int,
        val votingClosed: Boolean,
        val quorumReached: Boolean,
        val approved: Boolean,
        val rejected: Boolean
    )
}
