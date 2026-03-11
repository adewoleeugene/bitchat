package com.bitchat.android.lending

import com.bitchat.android.data.local.LendingDao
import com.bitchat.android.data.local.entities.EscrowTransferStatus
import com.bitchat.android.data.local.entities.EscrowCustodyState
import com.bitchat.android.data.local.entities.EscrowProvider
import com.bitchat.android.data.local.entities.LendingChannelEntity
import com.bitchat.android.data.local.entities.LendingMemberStatus
import com.bitchat.android.data.local.entities.LendingMembershipEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import com.bitchat.android.data.local.entities.CustodyExecutionStatus
import com.bitchat.android.data.local.entities.EscrowProposalType
import com.bitchat.android.data.local.entities.LoanRequestStatus
import com.bitchat.android.lending.onchain.InitializeLendingChannelOnChainParams
import com.bitchat.android.lending.onchain.LendingOnChainService
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LendingChannelServiceImpl @Inject constructor(
    private val lendingDao: LendingDao,
    private val lendingIdGenerator: LendingIdGenerator,
    private val squadsService: SquadsService,
    private val lendingOnChainService: LendingOnChainService
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

    override fun observeAllPoolSnapshots(): Flow<List<LendingPoolSnapshotEntity>> {
        return lendingDao.observeAllPoolSnapshots()
    }

    override suspend fun getChannelByIdentifier(
        identifier: String,
        preferredChannelKey: String?
    ): LendingChannelEntity? {
        val normalized = identifier.trim()
        if (normalized.isBlank()) return null

        val allChannels = lendingDao.getAllLendingChannels()
        val normalizedChannelName = normalized.removePrefix("#")

        if (normalized.startsWith("#") || normalized.startsWith("mesh:") || normalized.startsWith("geo:")) {
            val directKey = when {
                normalized.startsWith("mesh:") || normalized.startsWith("geo:") -> normalized
                preferredChannelKey != null -> preferredChannelKey
                else -> "mesh:$normalized"
            }
            return lendingDao.getLendingChannelByChannelKey(directKey)
                ?: allChannels.firstOrNull {
                    it.displayName.equals(normalized, ignoreCase = true)
                }
        }

        allChannels.firstOrNull { channel ->
            channel.displayName.removePrefix("#").equals(normalizedChannelName, ignoreCase = true) ||
                channel.channelKey.substringAfterLast(':').removePrefix("#").equals(normalizedChannelName, ignoreCase = true)
        }?.let { return it }

        return lendingDao.getLendingChannelById(normalized.uppercase())
    }

    override suspend fun createLocalChannel(request: CreateLendingChannelRequest): LendingChannelEntity {
        lendingDao.getLendingChannelByChannelKey(request.channelKey)?.let { existing ->
            val sameAsset = if (
                isNativeSolStakeAsset(existing.stakeTokenMint, existing.stakeTokenSymbol) &&
                isNativeSolStakeAsset(request.stakeTokenMint, request.stakeTokenSymbol)
            ) {
                true
            } else {
                existing.stakeTokenMint == request.stakeTokenMint &&
                    existing.stakeTokenSymbol == request.stakeTokenSymbol &&
                    existing.stakeTokenDecimals == request.stakeTokenDecimals
            }
            val sameStake = existing.requiredStakeAmount == request.requiredStakeAmount
            if (!sameAsset || !sameStake) {
                val existingAsset = existing.stakeTokenSymbol.ifBlank { existing.stakeTokenMint }
                val requestedAsset = request.stakeTokenSymbol.ifBlank { request.stakeTokenMint }
                throw IllegalStateException(
                    "lending channel ${request.displayName} already exists with ${existing.requiredStakeAmount} $existingAsset; use a different channel name or leave/reset the existing lending channel before changing it to ${request.requiredStakeAmount} $requestedAsset"
                )
            }
            return existing
        }

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
        if (lendingOnChainService.isEnabled()) {
            lendingOnChainService.initializeChannelOnChain(
                InitializeLendingChannelOnChainParams(
                    lendingId = lendingId,
                    creatorWallet = request.creatorWalletAddress,
                    quorumThresholdPercent = entity.quorumThresholdPercent,
                    approvalThresholdPercent = entity.approvalThresholdPercent,
                    memberCount = 1,
                    lifecycleState = 0,
                    requiredStakeAmount = entity.requiredStakeAmount,
                    stakeTokenMint = entity.stakeTokenMint,
                    stakeTokenDecimals = entity.stakeTokenDecimals,
                    createdAt = entity.createdAt
                )
            ).getOrElse { throw it }
        }
        return entity
    }

    override suspend fun importDiscoveredChannel(announcement: LendingChannelAnnouncement): LendingChannelEntity {
        val lendingId = announcement.lendingId.uppercase()
        val existing = lendingDao.getLendingChannelById(lendingId)
            ?: lendingDao.getLendingChannelByChannelKey(announcement.channelKey)

        val entity = (existing ?: LendingChannelEntity(
            lendingId = lendingId,
            channelKey = announcement.channelKey,
            displayName = announcement.displayName,
            creatorPeerId = announcement.creatorPeerId,
            creatorWalletAddress = announcement.creatorWalletAddress,
            requiredStakeAmount = announcement.requiredStakeAmount,
            stakeTokenMint = announcement.stakeTokenMint,
            stakeTokenSymbol = announcement.stakeTokenSymbol,
            stakeTokenDecimals = announcement.stakeTokenDecimals
        )).copy(
            lendingId = lendingId,
            channelKey = announcement.channelKey,
            displayName = announcement.displayName,
            creatorPeerId = announcement.creatorPeerId,
            creatorWalletAddress = announcement.creatorWalletAddress,
            requiredStakeAmount = announcement.requiredStakeAmount,
            stakeTokenMint = announcement.stakeTokenMint,
            stakeTokenSymbol = announcement.stakeTokenSymbol,
            stakeTokenDecimals = announcement.stakeTokenDecimals,
            escrowMultisigAddress = announcement.treasuryMultisigAddress
                ?: existing?.escrowMultisigAddress
                ?: "",
            updatedAt = System.currentTimeMillis()
        )
        lendingDao.insertLendingChannel(entity)
        val existingSnapshot = lendingDao.getPoolSnapshot(entity.lendingId)
        lendingDao.upsertPoolSnapshot(
            (existingSnapshot ?: LendingPoolSnapshotEntity(lendingId = entity.lendingId)).copy(
                totalStakedAmount = announcement.totalStakedAmount ?: existingSnapshot?.totalStakedAmount ?: 0L,
                availableLiquidityAmount = announcement.totalStakedAmount
                    ?: existingSnapshot?.availableLiquidityAmount
                    ?: 0L,
                updatedAt = System.currentTimeMillis()
            )
        )
        if (!announcement.treasuryOwnerAddress.isNullOrBlank()) {
            lendingDao.upsertEscrowAccount(
                (lendingDao.getEscrowAccount(entity.lendingId) ?: com.bitchat.android.data.local.entities.LendingEscrowAccountEntity(
                    lendingId = entity.lendingId,
                    multisigAddress = announcement.treasuryMultisigAddress.orEmpty(),
                    vaultAddress = announcement.treasuryOwnerAddress,
                    vaultTokenAccountAddress = announcement.treasuryTokenAccountAddress.orEmpty()
                )).copy(
                    multisigAddress = announcement.treasuryMultisigAddress
                        ?: lendingDao.getEscrowAccount(entity.lendingId)?.multisigAddress
                        ?: "",
                    vaultAddress = announcement.treasuryOwnerAddress,
                    vaultTokenAccountAddress = announcement.treasuryTokenAccountAddress.orEmpty(),
                    provider = announcement.custodyProvider ?: EscrowProvider.APP_TREASURY,
                    custodyState = announcement.custodyState
                        ?: com.bitchat.android.data.local.entities.EscrowCustodyState.PROVISIONED,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        if (!announcement.confirmedMemberPeerId.isNullOrBlank() &&
            !announcement.confirmedMemberWalletAddress.isNullOrBlank() &&
            announcement.confirmedMemberStakeAmount != null
        ) {
            val existingMembership = lendingDao.getMembership(entity.lendingId, announcement.confirmedMemberPeerId)
            val importedMembership = (existingMembership ?: LendingMembershipEntity(
                lendingId = entity.lendingId,
                memberPeerId = announcement.confirmedMemberPeerId,
                walletAddress = announcement.confirmedMemberWalletAddress,
                stakeAmount = announcement.confirmedMemberStakeAmount
            )).copy(
                walletAddress = announcement.confirmedMemberWalletAddress,
                stakeAmount = announcement.confirmedMemberStakeAmount,
                depositStatus = EscrowTransferStatus.CONFIRMED,
                joinStatus = LendingMemberStatus.ACTIVE,
                updatedAt = System.currentTimeMillis()
            )
            lendingDao.upsertMembership(importedMembership)
        }
        return entity
    }

    override suspend fun configureSquad(request: ConfigureLendingSquadRequest): LendingChannelEntity {
        val channel = getChannelByIdentifier(request.identifier, request.preferredChannelKey)
            ?: throw IllegalArgumentException("lending_channel_not_found")
        val multisigAddress = request.multisigAddress.trim()
        if (multisigAddress.isBlank()) throw IllegalArgumentException("squad_multisig_required")

        val multisigState = squadsService.fetchMultisigState(multisigAddress).getOrElse { throw it }
        if (multisigState.threshold != REQUIRED_LOAN_APPROVAL_COUNT) {
            throw IllegalArgumentException("squad_threshold_must_be_$REQUIRED_LOAN_APPROVAL_COUNT")
        }
        if (multisigState.memberCount < TARGET_LOAN_APPROVAL_MEMBER_COUNT) {
            throw IllegalArgumentException("squad_member_count_must_be_at_least_$TARGET_LOAN_APPROVAL_MEMBER_COUNT")
        }

        val vaultAddress = request.vaultAddress?.trim()?.takeIf { it.isNotBlank() }
            ?: SquadsCodec.getVaultPda(
                programId = squadsService.config().programId,
                multisigAddress = multisigAddress,
                index = 0
            )

        val updatedChannel = channel.copy(
            escrowMultisigAddress = multisigAddress,
            updatedAt = System.currentTimeMillis()
        )
        lendingDao.insertLendingChannel(updatedChannel)
        val existingEscrow = lendingDao.getEscrowAccount(channel.lendingId)
        lendingDao.upsertEscrowAccount(
            (existingEscrow ?: com.bitchat.android.data.local.entities.LendingEscrowAccountEntity(
                lendingId = channel.lendingId,
                multisigAddress = multisigAddress,
                vaultAddress = vaultAddress
            )).copy(
                multisigAddress = multisigAddress,
                vaultAddress = vaultAddress,
                provider = EscrowProvider.SQUADS,
                custodyState = EscrowCustodyState.ACTIVE,
                pendingMigrationMultisigAddress = "",
                updatedAt = System.currentTimeMillis()
            )
        )
        return updatedChannel
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
        val proposals = lendingDao.getEscrowProposalsForLendingChannel(channel.lendingId)
        val activeLoans = lendingDao.getLoanRequestsForLendingChannel(channel.lendingId)
            .count { it.status in setOf(LoanRequestStatus.PENDING, LoanRequestStatus.APPROVED, LoanRequestStatus.DISBURSED) }
        val unreconciledActiveMembers = memberships.count { membership ->
            membership.joinStatus == LendingMemberStatus.ACTIVE &&
                membership.depositStatus == EscrowTransferStatus.CONFIRMED &&
                proposals.none {
                    it.memberPeerId == membership.memberPeerId &&
                        it.proposalType == EscrowProposalType.STAKE_DEPOSIT &&
                        it.custodyExecutionStatus == CustodyExecutionStatus.EXECUTED
                }
        }
        return LendingChannelStatus(channel, snapshot, memberships, activeLoans, unreconciledActiveMembers)
    }
}
