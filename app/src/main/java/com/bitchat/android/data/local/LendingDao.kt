package com.bitchat.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bitchat.android.data.local.entities.CredibilityProfileEntity
import com.bitchat.android.data.local.entities.LendingChannelEntity
import com.bitchat.android.data.local.entities.LendingEscrowAccountEntity
import com.bitchat.android.data.local.entities.LendingEscrowProposalEntity
import com.bitchat.android.data.local.entities.LendingMembershipEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import com.bitchat.android.data.local.entities.LendingSignerReviewEntity
import com.bitchat.android.data.local.entities.LoanRepaymentEntity
import com.bitchat.android.data.local.entities.LoanRequestEntity
import com.bitchat.android.data.local.entities.LoanVoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LendingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLendingChannel(channel: LendingChannelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEscrowAccount(account: LendingEscrowAccountEntity)

    @Query("SELECT * FROM lending_escrow_accounts WHERE lendingId = :lendingId LIMIT 1")
    suspend fun getEscrowAccount(lendingId: String): LendingEscrowAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEscrowProposal(proposal: LendingEscrowProposalEntity)

    @Query("SELECT * FROM lending_escrow_proposals WHERE proposalId = :proposalId LIMIT 1")
    suspend fun getEscrowProposal(proposalId: String): LendingEscrowProposalEntity?

    @Query("SELECT * FROM lending_escrow_proposals WHERE requestId = :requestId ORDER BY createdAt DESC")
    suspend fun getEscrowProposalsForRequest(requestId: String): List<LendingEscrowProposalEntity>

    @Query("SELECT * FROM lending_escrow_proposals WHERE lendingId = :lendingId ORDER BY createdAt DESC")
    suspend fun getEscrowProposalsForLendingChannel(lendingId: String): List<LendingEscrowProposalEntity>

    @Query("SELECT * FROM lending_escrow_proposals WHERE txSignature = :txReference ORDER BY createdAt DESC")
    suspend fun getEscrowProposalsByTxReference(txReference: String): List<LendingEscrowProposalEntity>

    @Query(
        """
        SELECT * FROM lending_escrow_proposals
        WHERE custodyExecutionStatus IN ('CREATED')
        ORDER BY createdAt ASC
        """
    )
    suspend fun getPendingEscrowProposals(): List<LendingEscrowProposalEntity>

    @Query("SELECT * FROM lending_channels WHERE lendingId = :lendingId LIMIT 1")
    suspend fun getLendingChannelById(lendingId: String): LendingChannelEntity?

    @Query("SELECT * FROM lending_channels WHERE channelKey = :channelKey LIMIT 1")
    suspend fun getLendingChannelByChannelKey(channelKey: String): LendingChannelEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM lending_channels WHERE lendingId = :lendingId)")
    suspend fun hasLendingId(lendingId: String): Boolean

    @Query("SELECT * FROM lending_channels ORDER BY createdAt DESC")
    fun observeAllLendingChannels(): Flow<List<LendingChannelEntity>>

    @Query("SELECT * FROM lending_channels ORDER BY createdAt DESC")
    suspend fun getAllLendingChannels(): List<LendingChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembership(membership: LendingMembershipEntity)

    @Query("SELECT * FROM lending_memberships WHERE lendingId = :lendingId ORDER BY joinedAt ASC")
    suspend fun getMembershipsForLendingChannel(lendingId: String): List<LendingMembershipEntity>

    @Query("SELECT * FROM lending_memberships WHERE lendingId = :lendingId AND memberPeerId = :memberPeerId LIMIT 1")
    suspend fun getMembership(lendingId: String, memberPeerId: String): LendingMembershipEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPoolSnapshot(snapshot: LendingPoolSnapshotEntity)

    @Query("SELECT * FROM lending_pool_snapshots WHERE lendingId = :lendingId LIMIT 1")
    suspend fun getPoolSnapshot(lendingId: String): LendingPoolSnapshotEntity?

    @Query("SELECT * FROM lending_pool_snapshots")
    fun observeAllPoolSnapshots(): Flow<List<LendingPoolSnapshotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSignerReview(review: LendingSignerReviewEntity)

    @Query("SELECT * FROM lending_signer_reviews WHERE requestId = :requestId LIMIT 1")
    suspend fun getSignerReviewForRequest(requestId: String): LendingSignerReviewEntity?

    @Query("SELECT * FROM lending_signer_reviews WHERE lendingId = :lendingId ORDER BY openedAt DESC")
    suspend fun getSignerReviewsForLendingChannel(lendingId: String): List<LendingSignerReviewEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLoanRequest(request: LoanRequestEntity)

    @Query("SELECT * FROM loan_requests WHERE requestId = :requestId LIMIT 1")
    suspend fun getLoanRequestById(requestId: String): LoanRequestEntity?

    @Query("SELECT * FROM loan_requests WHERE loanRequestPda = :loanRequestPda LIMIT 1")
    suspend fun getLoanRequestByPda(loanRequestPda: String): LoanRequestEntity?

    @Query("SELECT * FROM loan_requests WHERE lendingId = :lendingId ORDER BY requestedAt DESC")
    suspend fun getLoanRequestsForLendingChannel(lendingId: String): List<LoanRequestEntity>

    @Query(
        """
        SELECT * FROM loan_requests
        WHERE requestId = :familyRootRequestId OR parentRequestId = :familyRootRequestId
        ORDER BY requestedAt ASC
        """
    )
    suspend fun getLinkedLoanRequests(familyRootRequestId: String): List<LoanRequestEntity>

    @Query(
        """
        SELECT * FROM loan_requests
        WHERE (
            requestId = :familyRootRequestId OR parentRequestId = :familyRootRequestId
        )
          AND lendingId = :lendingId
        LIMIT 1
        """
    )
    suspend fun getLinkedLoanRequestForLending(
        familyRootRequestId: String,
        lendingId: String
    ): LoanRequestEntity?

    @Query(
        """
        SELECT * FROM loan_requests
        WHERE (
            requestId = :familyRootRequestId OR parentRequestId = :familyRootRequestId
        )
          AND requestId != :requestId
          AND status IN ('DISBURSED', 'REPAID')
        ORDER BY disbursedAt DESC, requestedAt DESC
        LIMIT 1
        """
    )
    suspend fun getFundedSiblingLoanRequest(
        familyRootRequestId: String,
        requestId: String
    ): LoanRequestEntity?

    @Query(
        """
        SELECT * FROM loan_requests
        WHERE lendingId = :lendingId
          AND borrowerType = 'INDIVIDUAL'
          AND borrowerPeerId = :borrowerPeerId
          AND status IN ('PENDING', 'APPROVED', 'DISBURSED')
        ORDER BY requestedAt DESC
        LIMIT 1
        """
    )
    suspend fun getActiveIndividualLoanForBorrower(
        lendingId: String,
        borrowerPeerId: String
    ): LoanRequestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLoanVote(vote: LoanVoteEntity)

    @Query("SELECT * FROM loan_votes WHERE requestId = :requestId ORDER BY votedAt ASC")
    suspend fun getVotesForRequest(requestId: String): List<LoanVoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLoanRepayment(repayment: LoanRepaymentEntity)

    @Query("SELECT * FROM loan_repayments WHERE requestId = :requestId ORDER BY paidAt ASC")
    suspend fun getRepaymentsForRequest(requestId: String): List<LoanRepaymentEntity>

    @Query("SELECT * FROM loan_repayments WHERE txSignature = :txReference ORDER BY paidAt ASC")
    suspend fun getRepaymentsByTxReference(txReference: String): List<LoanRepaymentEntity>

    @Query(
        """
        SELECT * FROM loan_repayments
        WHERE requestId = :requestId AND txSignature = :txSignature
        ORDER BY paidAt ASC
        LIMIT 1
        """
    )
    suspend fun getRepaymentByRequestAndSignature(requestId: String, txSignature: String): LoanRepaymentEntity?

    // Voter-backed lending queries

    @Query("SELECT * FROM loan_votes WHERE requestId = :requestId AND voteChoice = 'YES' ORDER BY votedAt ASC")
    suspend fun getYesVotesForRequest(requestId: String): List<LoanVoteEntity>

    @Query(
        """
        SELECT v.* FROM loan_votes v
        INNER JOIN loan_requests r ON v.requestId = r.requestId
        WHERE v.lendingId = :lendingId
          AND v.voterPeerId = :voterPeerId
          AND v.voteChoice = 'YES'
          AND r.backingModel = 'VOTER_BACKED'
          AND r.status IN ('PENDING', 'COMMUNITY_APPROVED', 'SIGNER_REVIEW', 'SIGNER_APPROVED', 'DISBURSED', 'PARTIALLY_REPAID', 'OVERDUE')
        """
    )
    suspend fun getActiveVoterBackedLoansForMember(lendingId: String, voterPeerId: String): List<LoanVoteEntity>

    @Query("UPDATE loan_votes SET lockedAmount = :lockedAmount WHERE requestId = :requestId AND voterPeerId = :voterPeerId")
    suspend fun updateVoteLockedAmount(requestId: String, voterPeerId: String, lockedAmount: Long)

    @Query("UPDATE loan_votes SET interestEarned = :interestEarned WHERE requestId = :requestId AND voterPeerId = :voterPeerId")
    suspend fun updateVoteInterestEarned(requestId: String, voterPeerId: String, interestEarned: Long)

    @Query("UPDATE loan_votes SET lossAbsorbed = :lossAbsorbed WHERE requestId = :requestId AND voterPeerId = :voterPeerId")
    suspend fun updateVoteLossAbsorbed(requestId: String, voterPeerId: String, lossAbsorbed: Long)

    @Query("UPDATE lending_memberships SET lockedStakeAmount = :lockedStakeAmount, updatedAt = :updatedAt WHERE lendingId = :lendingId AND memberPeerId = :memberPeerId")
    suspend fun updateMemberLockedStake(lendingId: String, memberPeerId: String, lockedStakeAmount: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE lending_memberships SET stakeAmount = :stakeAmount, lockedStakeAmount = :lockedStakeAmount, joinStatus = :joinStatus, suspendedReason = :suspendedReason, updatedAt = :updatedAt WHERE lendingId = :lendingId AND memberPeerId = :memberPeerId")
    suspend fun updateMemberStakeAndStatus(lendingId: String, memberPeerId: String, stakeAmount: Long, lockedStakeAmount: Long, joinStatus: String, suspendedReason: String?, updatedAt: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCredibilityProfile(profile: CredibilityProfileEntity)

    @Query(
        """
        SELECT * FROM credibility_profiles
        WHERE subjectType = :subjectType AND subjectKey = :subjectKey
        LIMIT 1
        """
    )
    suspend fun getCredibilityProfile(subjectType: String, subjectKey: String): CredibilityProfileEntity?
}
