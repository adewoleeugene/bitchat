package com.bitchat.android.lending

import com.bitchat.android.data.local.entities.CredibilityProfileEntity
import com.bitchat.android.data.local.entities.LendingChannelEntity
import com.bitchat.android.data.local.entities.LendingEscrowAccountEntity
import com.bitchat.android.data.local.entities.LendingEscrowProposalEntity
import com.bitchat.android.data.local.entities.LendingMembershipEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import com.bitchat.android.data.local.entities.LendingSignerReviewEntity
import com.bitchat.android.data.local.entities.LoanRequestStatus
import com.bitchat.android.data.local.entities.LoanRepaymentEntity
import com.bitchat.android.data.local.entities.LoanRequestEntity
import com.bitchat.android.data.local.entities.LoanRequestKind
import com.bitchat.android.data.local.entities.LoanVoteEntity
import com.bitchat.android.data.local.entities.VoteChoice
import kotlinx.coroutines.flow.Flow
import kotlin.math.ceil

interface LendingChannelService {
    suspend fun getChannelByLendingId(lendingId: String): LendingChannelEntity?
    suspend fun getChannelByChannelKey(channelKey: String): LendingChannelEntity?
    fun observeAllChannels(): Flow<List<LendingChannelEntity>>
    fun observeAllPoolSnapshots(): Flow<List<LendingPoolSnapshotEntity>>
    suspend fun getChannelByIdentifier(identifier: String, preferredChannelKey: String? = null): LendingChannelEntity?
    suspend fun createLocalChannel(request: CreateLendingChannelRequest): LendingChannelEntity
    suspend fun importSharedChannel(request: ImportLendingChannelRequest): LendingChannelEntity
    suspend fun configureSquad(request: ConfigureLendingSquadRequest): LendingChannelEntity
    suspend fun createSquad(request: CreateLendingSquadRequest): LendingChannelEntity
    suspend fun recordPendingMembership(request: RecordPendingMembershipRequest): LendingMembershipEntity
    suspend fun importMembershipUpdate(message: LendingMembershipMessage, senderPeerId: String?): LendingMembershipEntity?
    suspend fun getMemberships(lendingId: String): List<LendingMembershipEntity>
    suspend fun getPoolSnapshot(lendingId: String): LendingPoolSnapshotEntity?
    suspend fun getStatus(identifier: String, preferredChannelKey: String? = null): LendingChannelStatus?
    suspend fun updateMembershipStake(lendingId: String, memberPeerId: String, newStakeAmount: Long)
    suspend fun transferOwnership(lendingId: String, currentOwnerPeerId: String, newOwnerPeerId: String): LendingChannelEntity
    suspend fun closeChannel(lendingId: String)
}

interface LendingCredibilityService {
    suspend fun getProfile(subjectType: String, subjectKey: String): CredibilityProfileEntity?
    suspend fun evaluateAndPersist(request: LendingCredibilityRequest): LendingCredibilityResult
}

interface LendingEscrowService {
    suspend fun getMemberships(lendingId: String): List<LendingMembershipEntity>
    suspend fun getPoolSnapshot(lendingId: String): LendingPoolSnapshotEntity?
    suspend fun activateMembership(lendingId: String, memberPeerId: String): LendingMembershipEntity
    suspend fun prepareStakeDeposit(lendingId: String, memberPeerId: String): LendingStakeApprovalRequest
    suspend fun submitStakeDeposit(lendingId: String, memberPeerId: String): LendingMembershipEntity
    suspend fun repairMembershipState(lendingId: String, memberPeerId: String): LendingMembershipEntity?
    suspend fun repairMembershipsForTransaction(
        queuedTransactionId: String,
        txSignature: String? = null
    ): List<LendingMembershipEntity>
    suspend fun releaseMembershipStake(lendingId: String, memberPeerId: String): LendingMembershipEntity
    suspend fun provisionChannelEscrow(lendingId: String): LendingEscrowAccountEntity
    suspend fun getEscrowAccount(lendingId: String): LendingEscrowAccountEntity?
    suspend fun getEscrowProposalsForRequest(requestId: String): List<LendingEscrowProposalEntity>
    suspend fun createLoanDisbursementProposal(requestId: String): LendingEscrowProposalEntity
    suspend fun createStakeReleaseProposal(lendingId: String, memberPeerId: String): LendingEscrowProposalEntity
    suspend fun reconcilePendingEscrowOperations(): List<LendingEscrowProposalEntity>
}

interface LendingLoanService {
    suspend fun getLoanRequests(lendingId: String): List<LoanRequestEntity>
    suspend fun getLoanRequest(requestId: String): LoanRequestEntity?
    suspend fun reconcileLoanRequestState(requestId: String): LoanRequestEntity?
    suspend fun reconcileOverdueAndDefaultedLoans(): List<LoanStatusTransition>
    suspend fun getSignerReview(requestId: String): LendingSignerReviewEntity?
    suspend fun getLinkedLoanRequests(requestId: String): List<LoanRequestEntity>
    suspend fun getVotes(requestId: String): List<LoanVoteEntity>
    suspend fun getRepayments(requestId: String): List<LoanRepaymentEntity>
    suspend fun createLoanRequest(request: CreateLoanRequest): LoanRequestEntity
    suspend fun openSignerReview(request: OpenSignerReviewRequest): SignerReviewResult
    suspend fun authorizeSignerReview(request: AuthorizeSignerReviewRequest): SignerReviewResult
    suspend fun forwardLoanRequest(request: ForwardLoanRequest): LoanRequestEntity
    suspend fun cancelLoanRequest(request: CancelLoanRequest): LoanCancellationResult
    suspend fun importDiscoveredLoanRequest(message: LendingLoanRequestMessage, senderPeerId: String?): LoanRequestEntity?
    suspend fun importDiscoveredLoanVote(message: LendingLoanVoteMessage, senderPeerId: String?): LoanRequestEntity?
    suspend fun importDiscoveredLoanRepayment(message: LendingLoanRepaymentMessage, senderPeerId: String?): LoanRepaymentResult?
    suspend fun castVote(request: CastLoanVoteRequest): LoanVoteResult
    suspend fun disburseApprovedLoan(request: DisburseApprovedLoanRequest): LoanRequestEntity
    suspend fun repayLoan(request: RecordLoanRepaymentRequest): LoanRepaymentResult
    suspend fun repairRepaymentsForTransaction(
        queuedTransactionId: String,
        transactionStatus: String,
        txSignature: String? = null
    ): List<LoanRepaymentEntity>
    suspend fun leaveChannel(request: LeaveLendingChannelRequest): LendingLeaveResult
}

data class LendingCredibilityRequest(
    val peerId: String,
    val stakeAmountRequired: Long,
    val observedStakeBalance: Long? = null,
    val stakeBalanceSatisfied: Boolean = false,
    val now: Long = System.currentTimeMillis()
)

data class LendingCredibilityResult(
    val profile: CredibilityProfileEntity,
    val passedHardGates: Boolean,
    val passedThreshold: Boolean,
    val hardGateFailures: List<String>,
    val totalActions: Int,
    val recentActions: Int
)

data class CreateLendingChannelRequest(
    val channelKey: String,
    val displayName: String,
    val creatorPeerId: String,
    val creatorWalletAddress: String,
    val requiredStakeAmount: Long,
    val minimumVoteCount: Int = REQUIRED_LOAN_APPROVAL_COUNT,
    val maxLoanDurationDays: Int = 14,
    val stakeTokenMint: String,
    val stakeTokenSymbol: String = "",
    val stakeTokenDecimals: Int = 6,
    val votingWindowHours: Int = 24,
    val defaultGracePeriodDays: Int = 7
)

data class RecordPendingMembershipRequest(
    val lendingId: String,
    val memberPeerId: String,
    val walletAddress: String,
    val stakeAmount: Long,
    val credibilityScore: Int,
    val credibilitySnapshotJson: String
)

data class ImportLendingChannelRequest(
    val lendingId: String,
    val channelKey: String,
    val displayName: String,
    val creatorPeerId: String,
    val creatorWalletAddress: String,
    val requiredStakeAmount: Long,
    val minimumVoteCount: Int,
    val maxLoanDurationDays: Int = 14,
    val stakeTokenMint: String,
    val stakeTokenSymbol: String = "",
    val stakeTokenDecimals: Int = 6,
    val seedCreatorMembership: Boolean = false,
    val votingWindowHours: Int = 24,
    val defaultGracePeriodDays: Int = 7
)

data class ConfigureLendingSquadRequest(
    val identifier: String,
    val preferredChannelKey: String? = null,
    val actorPeerId: String,
    val multisigAddress: String,
    val vaultAddress: String? = null
)

data class CreateLendingSquadRequest(
    val identifier: String,
    val preferredChannelKey: String? = null,
    val actorPeerId: String,
    val memberWalletAddresses: List<String>,
    val threshold: Int = REQUIRED_LOAN_APPROVAL_COUNT
)

data class LendingChannelStatus(
    val channel: LendingChannelEntity,
    val poolSnapshot: LendingPoolSnapshotEntity?,
    val memberships: List<LendingMembershipEntity>,
    val activeLoanCount: Int,
    val unreconciledActiveMemberCount: Int = 0
)

data class LendingStakeApprovalRequest(
    val lendingId: String,
    val memberPeerId: String,
    val channelDisplayName: String,
    val actionLabel: String,
    val treasuryAddress: String,
    val amountAtomic: Long,
    val decimals: Int,
    val symbol: String,
    val assetDescriptor: String
)

data class LendingLeaveApprovalRequest(
    val lendingId: String,
    val memberPeerId: String,
    val channelKey: String,
    val channelDisplayName: String,
    val treasuryAddress: String,
    val recipientAddress: String,
    val amountAtomic: Long,
    val decimals: Int,
    val symbol: String,
    val assetDescriptor: String
)

data class LendingTreasurySetupRequest(
    val lendingId: String,
    val channelKey: String,
    val channelDisplayName: String,
    val approvalThreshold: Int,
    val recommendedSignerCount: Int,
    val cluster: String,
    val signerCandidates: List<LendingTreasurySignerCandidate> = emptyList(),
    val existingMultisigAddress: String = "",
    val existingVaultAddress: String = ""
)

data class LendingTreasurySignerCandidate(
    val peerId: String,
    val displayName: String,
    val walletAddress: String,
    val roleLabel: String,
    val recommended: Boolean
)

data class CreateLoanRequest(
    val identifier: String,
    val preferredChannelKey: String? = null,
    val requesterPeerId: String,
    val borrowerType: String,
    val principalAmount: Long,
    val durationDays: Int,
    val purpose: String,
    val endorserPeerIds: List<String> = emptyList(),
    val interestBps: Int = DEFAULT_INTEREST_BPS,
    val borrowerGroupKey: String? = null
)

data class CastLoanVoteRequest(
    val requestId: String,
    val voterPeerId: String,
    val voteChoice: String
)

data class ForwardLoanRequest(
    val requestId: String,
    val destinationIdentifier: String,
    val preferredChannelKey: String? = null,
    val actorPeerId: String
)

data class CancelLoanRequest(
    val requestId: String,
    val actorPeerId: String,
    val actorIsAdmin: Boolean = false
)

data class LoanVoteResult(
    val request: LoanRequestEntity,
    val votes: List<LoanVoteEntity>,
    val quorumReached: Boolean,
    val approved: Boolean,
    val rejected: Boolean,
    val voterLockedAmount: Long = 0,
    val fullyBacked: Boolean = false
)

data class RecordLoanRepaymentRequest(
    val requestId: String,
    val payerPeerId: String,
    val amount: Long
)

data class OpenSignerReviewRequest(
    val requestId: String,
    val actorPeerId: String,
    val actorIsAdmin: Boolean = false
)

data class AuthorizeSignerReviewRequest(
    val requestId: String,
    val actorPeerId: String,
    val actorIsApprover: Boolean = false
)

data class DisburseApprovedLoanRequest(
    val requestId: String,
    val actorPeerId: String,
    val actorIsAdmin: Boolean = false
)

data class LoanRepaymentResult(
    val repayment: LoanRepaymentEntity,
    val updatedRequest: LoanRequestEntity,
    val totalRepaidAmount: Long,
    val remainingBalance: Long
)

data class LoanStatusTransition(
    val requestId: String,
    val lendingId: String,
    val previousStatus: String,
    val newStatus: String,
    val channelDisplayName: String = ""
)

data class LoanCancellationResult(
    val request: LoanRequestEntity,
    val affectedRequests: List<LoanRequestEntity>
)

data class SignerReviewResult(
    val request: LoanRequestEntity,
    val review: LendingSignerReviewEntity,
    val created: Boolean
)

data class LeaveLendingChannelRequest(
    val identifier: String,
    val preferredChannelKey: String? = null,
    val memberPeerId: String
)

data class LendingLeaveResult(
    val membership: LendingMembershipEntity,
    val queuedTransferId: String? = null,
    val escrowProposalId: String? = null,
    val escrowExecutionStatus: String? = null
)

const val DEFAULT_INTEREST_BPS = 500
const val DEFAULT_CREDIBILITY_THRESHOLD = 60
const val DEFAULT_BORROW_CAP_PERCENT = 80
const val REQUIRED_LOAN_APPROVAL_COUNT = 2
const val TARGET_LOAN_APPROVAL_MEMBER_COUNT = 3
const val NATIVE_SOL_ASSET = "SOL"
const val NATIVE_SOL_REFUND_FEE_RESERVE_LAMPORTS = 10_000L

/** Maximum percentage of a voter's original stake that can be at risk across all active loans. */
const val VOTER_MAX_RISK_PERCENT = 50
/** Credibility score penalty applied to each voter who backed a defaulted loan. */
const val DEFAULT_LOSS_CREDIBILITY_PENALTY = 15
/** Suspension threshold: member is suspended if remaining stake drops below this % of channel minimum. */
const val VOTER_SUSPENSION_THRESHOLD_PERCENT = 50

/**
 * Calculate the equal per-voter share of a loan amount.
 * Remainder goes to the first voter (ceiling division for first, floor for rest).
 */
fun calculatePerVoterShare(loanAmount: Long, yesVoterCount: Int): Long {
    if (yesVoterCount <= 0) return loanAmount
    return loanAmount / yesVoterCount
}

/** Remainder amount assigned to the first YES voter to cover rounding. */
fun calculatePerVoterRemainder(loanAmount: Long, yesVoterCount: Int): Long {
    if (yesVoterCount <= 0) return 0
    return loanAmount - (loanAmount / yesVoterCount) * yesVoterCount
}

/** Maximum amount a voter can back, respecting the 50% safety net. */
fun maxBackingForVoter(membership: LendingMembershipEntity): Long {
    val maxAtRisk = membership.stakeAmount * VOTER_MAX_RISK_PERCENT / 100
    return (maxAtRisk - membership.lockedStakeAmount).coerceAtLeast(0)
}

/** Whether a member should be suspended due to stake falling below threshold. */
fun isVoterBelowSuspensionThreshold(membership: LendingMembershipEntity, channel: LendingChannelEntity): Boolean {
    val effectiveStake = membership.stakeAmount - membership.lockedStakeAmount
    val threshold = channel.requiredStakeAmount * VOTER_SUSPENSION_THRESHOLD_PERCENT / 100
    return effectiveStake < threshold
}

fun defaultVoteChoice(raw: String): String {
    return when (raw.trim().uppercase()) {
        VoteChoice.NO, "NO", "DENY", "REJECT", "DOWNVOTE" -> VoteChoice.NO
        else -> VoteChoice.YES
    }
}

fun countLoanApprovals(votes: List<LoanVoteEntity>): Int {
    return votes.count { it.voteChoice == VoteChoice.YES }
}

fun countLoanRejections(votes: List<LoanVoteEntity>): Int {
    return votes.count { it.voteChoice == VoteChoice.NO }
}

fun requiredVoteQuorumCount(
    eligibleVoterCount: Int,
    quorumThresholdPercent: Int
): Int {
    if (eligibleVoterCount <= 0) return 0
    val threshold = quorumThresholdPercent.coerceIn(1, 100)
    return ceil((eligibleVoterCount * threshold) / 100.0).toInt().coerceAtLeast(1)
}

fun familyRootRequestId(request: LoanRequestEntity): String = request.parentRequestId ?: request.requestId

fun requestKindLabel(requestKind: String?): String {
    return when (requestKind) {
        LoanRequestKind.FORWARDED_COPY -> "Forwarded"
        else -> "Origin"
    }
}

fun isNativeSolStakeAsset(mint: String, symbol: String = ""): Boolean {
    return mint.equals(NATIVE_SOL_ASSET, ignoreCase = true) ||
        symbol.equals(NATIVE_SOL_ASSET, ignoreCase = true)
}

fun requiredJoinDebitAmount(channel: LendingChannelEntity): Long {
    return if (isNativeSolStakeAsset(channel.stakeTokenMint, channel.stakeTokenSymbol)) {
        channel.requiredStakeAmount + NATIVE_SOL_REFUND_FEE_RESERVE_LAMPORTS
    } else {
        channel.requiredStakeAmount
    }
}
