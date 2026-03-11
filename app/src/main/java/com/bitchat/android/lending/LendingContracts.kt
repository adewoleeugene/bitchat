package com.bitchat.android.lending

import com.bitchat.android.data.local.entities.CredibilityProfileEntity
import com.bitchat.android.data.local.entities.LendingChannelEntity
import com.bitchat.android.data.local.entities.LendingEscrowAccountEntity
import com.bitchat.android.data.local.entities.LendingEscrowProposalEntity
import com.bitchat.android.data.local.entities.LendingMembershipEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import com.bitchat.android.data.local.entities.LoanRequestStatus
import com.bitchat.android.data.local.entities.LoanRepaymentEntity
import com.bitchat.android.data.local.entities.LoanRequestEntity
import com.bitchat.android.data.local.entities.LoanRequestKind
import com.bitchat.android.data.local.entities.LoanVoteEntity
import com.bitchat.android.data.local.entities.VoteChoice
import kotlinx.coroutines.flow.Flow

interface LendingChannelService {
    suspend fun getChannelByLendingId(lendingId: String): LendingChannelEntity?
    suspend fun getChannelByChannelKey(channelKey: String): LendingChannelEntity?
    fun observeAllChannels(): Flow<List<LendingChannelEntity>>
    fun observeAllPoolSnapshots(): Flow<List<LendingPoolSnapshotEntity>>
    suspend fun getChannelByIdentifier(identifier: String, preferredChannelKey: String? = null): LendingChannelEntity?
    suspend fun createLocalChannel(request: CreateLendingChannelRequest): LendingChannelEntity
    suspend fun importDiscoveredChannel(announcement: LendingChannelAnnouncement): LendingChannelEntity
    suspend fun configureSquad(request: ConfigureLendingSquadRequest): LendingChannelEntity
    suspend fun recordPendingMembership(request: RecordPendingMembershipRequest): LendingMembershipEntity
    suspend fun getMemberships(lendingId: String): List<LendingMembershipEntity>
    suspend fun getPoolSnapshot(lendingId: String): LendingPoolSnapshotEntity?
    suspend fun getStatus(identifier: String, preferredChannelKey: String? = null): LendingChannelStatus?
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
    suspend fun getLinkedLoanRequests(requestId: String): List<LoanRequestEntity>
    suspend fun getVotes(requestId: String): List<LoanVoteEntity>
    suspend fun getRepayments(requestId: String): List<LoanRepaymentEntity>
    suspend fun createLoanRequest(request: CreateLoanRequest): LoanRequestEntity
    suspend fun forwardLoanRequest(request: ForwardLoanRequest): LoanRequestEntity
    suspend fun cancelLoanRequest(request: CancelLoanRequest): LoanCancellationResult
    suspend fun importDiscoveredLoanRequest(message: LendingLoanRequestMessage): LoanRequestEntity?
    suspend fun importDiscoveredLoanVote(message: LendingLoanVoteMessage): LoanRequestEntity?
    suspend fun importDiscoveredLoanRepayment(message: LendingLoanRepaymentMessage): LoanRepaymentResult?
    suspend fun castVote(request: CastLoanVoteRequest): LoanVoteResult
    suspend fun disburseApprovedLoan(requestId: String): LoanRequestEntity
    suspend fun repayLoan(request: RecordLoanRepaymentRequest): LoanRepaymentResult
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
    val stakeTokenMint: String,
    val stakeTokenSymbol: String = "",
    val stakeTokenDecimals: Int = 6
)

data class RecordPendingMembershipRequest(
    val lendingId: String,
    val memberPeerId: String,
    val walletAddress: String,
    val stakeAmount: Long,
    val credibilityScore: Int,
    val credibilitySnapshotJson: String
)

data class ConfigureLendingSquadRequest(
    val identifier: String,
    val preferredChannelKey: String? = null,
    val multisigAddress: String,
    val vaultAddress: String? = null
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
    val rejected: Boolean
)

data class RecordLoanRepaymentRequest(
    val requestId: String,
    val payerPeerId: String,
    val amount: Long
)

data class LoanRepaymentResult(
    val repayment: LoanRepaymentEntity,
    val updatedRequest: LoanRequestEntity,
    val totalRepaidAmount: Long,
    val remainingBalance: Long
)

data class LoanCancellationResult(
    val request: LoanRequestEntity,
    val affectedRequests: List<LoanRequestEntity>
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

fun defaultVoteChoice(raw: String): String {
    return VoteChoice.YES
}

fun countLoanApprovals(votes: List<LoanVoteEntity>): Int {
    return votes.count { it.voteChoice == VoteChoice.YES }
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
