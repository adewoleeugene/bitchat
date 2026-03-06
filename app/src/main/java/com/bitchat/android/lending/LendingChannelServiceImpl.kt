package com.bitchat.android.lending

import com.bitchat.android.data.local.LendingDao
import com.bitchat.android.data.local.entities.EscrowTransferStatus
import com.bitchat.android.data.local.entities.LendingChannelEntity
import com.bitchat.android.data.local.entities.LendingMemberStatus
import com.bitchat.android.data.local.entities.LendingMembershipEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import com.bitchat.android.data.local.entities.LoanRequestStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LendingChannelServiceImpl @Inject constructor(
    private val lendingDao: LendingDao,
    private val lendingIdGenerator: LendingIdGenerator
) : LendingChannelService {

    override suspend fun getChannelByLendingId(lendingId: String): LendingChannelEntity? {
        return lendingDao.getLendingChannelById(lendingId.uppercase())
    }

    override suspend fun getChannelByChannelKey(channelKey: String): LendingChannelEntity? {
        return lendingDao.getLendingChannelByChannelKey(channelKey)
    }

    override fun observeAllChannels(): Flow<List<LendingChannelEntity>> {
        return lendingDao.observeAllLendingChannels()
    }

    override suspend fun getChannelByIdentifier(
        identifier: String,
        preferredChannelKey: String?
    ): LendingChannelEntity? {
        val normalized = identifier.trim()
        if (normalized.isBlank()) return null

        if (normalized.startsWith("#") || normalized.startsWith("mesh:") || normalized.startsWith("geo:")) {
            val directKey = when {
                normalized.startsWith("mesh:") || normalized.startsWith("geo:") -> normalized
                preferredChannelKey != null -> preferredChannelKey
                else -> "mesh:$normalized"
            }
            return lendingDao.getLendingChannelByChannelKey(directKey)
                ?: lendingDao.getAllLendingChannels().firstOrNull {
                    it.displayName.equals(normalized, ignoreCase = true)
                }
        }

        return lendingDao.getLendingChannelById(normalized.uppercase())
    }

    override suspend fun createLocalChannel(request: CreateLendingChannelRequest): LendingChannelEntity {
        lendingDao.getLendingChannelByChannelKey(request.channelKey)?.let { return it }

        val lendingId = lendingIdGenerator.generateUniqueId { candidate ->
            lendingDao.hasLendingId(candidate)
        }
        val entity = LendingChannelEntity(
            lendingId = lendingId,
            channelKey = request.channelKey,
            displayName = request.displayName,
            creatorPeerId = request.creatorPeerId,
            creatorWalletAddress = request.creatorWalletAddress,
            requiredStakeAmount = request.requiredStakeAmount,
            stakeTokenMint = request.stakeTokenMint,
            stakeTokenSymbol = request.stakeTokenSymbol,
            stakeTokenDecimals = request.stakeTokenDecimals
        )
        lendingDao.insertLendingChannel(entity)
        lendingDao.upsertPoolSnapshot(
            LendingPoolSnapshotEntity(
                lendingId = lendingId,
                totalStakedAmount = 0L,
                reservedAmount = 0L,
                disbursedAmount = 0L,
                availableLiquidityAmount = 0L
            )
        )
        lendingDao.upsertMembership(
            LendingMembershipEntity(
                lendingId = lendingId,
                memberPeerId = request.creatorPeerId,
                walletAddress = request.creatorWalletAddress,
                stakeAmount = request.requiredStakeAmount,
                depositStatus = EscrowTransferStatus.PENDING,
                joinStatus = LendingMemberStatus.PENDING,
                credibilityScore = 0,
                credibilitySnapshotJson = ""
            )
        )
        return entity
    }

    override suspend fun recordPendingMembership(request: RecordPendingMembershipRequest): LendingMembershipEntity {
        val existing = lendingDao.getMembership(request.lendingId, request.memberPeerId)
        val membership = (existing ?: LendingMembershipEntity(
            lendingId = request.lendingId,
            memberPeerId = request.memberPeerId,
            walletAddress = request.walletAddress,
            stakeAmount = request.stakeAmount
        )).copy(
            walletAddress = request.walletAddress,
            stakeAmount = request.stakeAmount,
            credibilityScore = request.credibilityScore,
            credibilitySnapshotJson = request.credibilitySnapshotJson,
            depositStatus = EscrowTransferStatus.PENDING,
            joinStatus = LendingMemberStatus.PENDING,
            updatedAt = System.currentTimeMillis()
        )
        lendingDao.upsertMembership(membership)
        return membership
    }

    override suspend fun getMemberships(lendingId: String): List<LendingMembershipEntity> {
        return lendingDao.getMembershipsForLendingChannel(lendingId)
    }

    override suspend fun getPoolSnapshot(lendingId: String): LendingPoolSnapshotEntity? {
        return lendingDao.getPoolSnapshot(lendingId)
    }

    override suspend fun getStatus(
        identifier: String,
        preferredChannelKey: String?
    ): LendingChannelStatus? {
        val channel = getChannelByIdentifier(identifier, preferredChannelKey) ?: return null
        val memberships = lendingDao.getMembershipsForLendingChannel(channel.lendingId)
        val snapshot = lendingDao.getPoolSnapshot(channel.lendingId)
        val activeLoans = lendingDao.getLoanRequestsForLendingChannel(channel.lendingId)
            .count { it.status in setOf(LoanRequestStatus.PENDING, LoanRequestStatus.APPROVED, LoanRequestStatus.DISBURSED) }
        return LendingChannelStatus(channel, snapshot, memberships, activeLoans)
    }
}
