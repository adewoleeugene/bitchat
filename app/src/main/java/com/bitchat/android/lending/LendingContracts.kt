package com.bitchat.android.lending

import com.bitchat.android.data.local.entities.CredibilityProfileEntity
import com.bitchat.android.data.local.entities.LendingChannelEntity
import com.bitchat.android.data.local.entities.LendingMembershipEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import com.bitchat.android.data.local.entities.LoanRequestStatus
import com.bitchat.android.data.local.entities.LoanRepaymentEntity
import com.bitchat.android.data.local.entities.LoanRequestEntity
import com.bitchat.android.data.local.entities.LoanVoteEntity
import kotlinx.coroutines.flow.Flow

interface LendingChannelService {
    suspend fun getChannelByLendingId(lendingId: String): LendingChannelEntity?
    suspend fun getChannelByChannelKey(channelKey: String): LendingChannelEntity?
    fun observeAllChannels(): Flow<List<LendingChannelEntity>>
    suspend fun getChannelByIdentifier(identifier: String, preferredChannelKey: String? = null): LendingChannelEntity?
    suspend fun createLocalChannel(request: CreateLendingChannelRequest): LendingChannelEntity
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
}

interface LendingLoanService {
    suspend fun getLoanRequests(lendingId: String): List<LoanRequestEntity>
    suspend fun getVotes(requestId: String): List<LoanVoteEntity>
    suspend fun getRepayments(requestId: String): List<LoanRepaymentEntity>
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

data class LendingChannelStatus(
    val channel: LendingChannelEntity,
    val poolSnapshot: LendingPoolSnapshotEntity?,
    val memberships: List<LendingMembershipEntity>,
    val activeLoanCount: Int
)
