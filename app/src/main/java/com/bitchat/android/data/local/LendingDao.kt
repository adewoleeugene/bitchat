package com.bitchat.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bitchat.android.data.local.entities.CredibilityProfileEntity
import com.bitchat.android.data.local.entities.LendingChannelEntity
import com.bitchat.android.data.local.entities.LendingMembershipEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import com.bitchat.android.data.local.entities.LoanRepaymentEntity
import com.bitchat.android.data.local.entities.LoanRequestEntity
import com.bitchat.android.data.local.entities.LoanVoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LendingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLendingChannel(channel: LendingChannelEntity)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLoanRequest(request: LoanRequestEntity)

    @Query("SELECT * FROM loan_requests WHERE lendingId = :lendingId ORDER BY requestedAt DESC")
    suspend fun getLoanRequestsForLendingChannel(lendingId: String): List<LoanRequestEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLoanVote(vote: LoanVoteEntity)

    @Query("SELECT * FROM loan_votes WHERE requestId = :requestId ORDER BY votedAt ASC")
    suspend fun getVotesForRequest(requestId: String): List<LoanVoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLoanRepayment(repayment: LoanRepaymentEntity)

    @Query("SELECT * FROM loan_repayments WHERE requestId = :requestId ORDER BY paidAt ASC")
    suspend fun getRepaymentsForRequest(requestId: String): List<LoanRepaymentEntity>

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
