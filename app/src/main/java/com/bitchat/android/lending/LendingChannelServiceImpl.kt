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
import kotlin.math.max
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LendingChannelServiceImpl @Inject constructor(
    private val lendingDao: LendingDao,
    private val lendingIdGenerator: LendingIdGenerator,
    private val squadsService: SquadsService
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
        val exactPreferredKey = preferredChannelKey
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("mesh:") || it.startsWith("geo:")) it else "mesh:$it" }

        exactPreferredKey?.let { directKey ->
            lendingDao.getLendingChannelByChannelKey(directKey)?.let { return it }
        }

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
            val sameMinimumVotes = existing.minimumVoteCount == request.minimumVoteCount
            val sameDuration = existing.maxLoanDurationDays == request.maxLoanDurationDays
            if (!sameAsset || !sameStake || !sameMinimumVotes || !sameDuration) {
                val existingAsset = existing.stakeTokenSymbol.ifBlank { existing.stakeTokenMint }
                throw IllegalStateException(
                    "lending channel ${request.displayName} already exists with ${existing.requiredStakeAmount} $existingAsset, minimum ${existing.minimumVoteCount} votes, and max payback ${existing.maxLoanDurationDays} days; use a different channel name or leave/reset the existing lending channel before changing it"
                )
            }
            return existing
        }

        if (request.minimumVoteCount <= 0) {
            throw IllegalArgumentException("minimum_vote_count_must_be_positive")
        }
        if (request.maxLoanDurationDays <= 0) {
            throw IllegalArgumentException("max_loan_duration_days_must_be_positive")
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
            minimumVoteCount = request.minimumVoteCount,
            maxLoanDurationDays = request.maxLoanDurationDays,
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

    override suspend fun importSharedChannel(request: ImportLendingChannelRequest): LendingChannelEntity {
        if (request.minimumVoteCount <= 0) {
            throw IllegalArgumentException("minimum_vote_count_must_be_positive")
        }
        if (request.maxLoanDurationDays <= 0) {
            throw IllegalArgumentException("max_loan_duration_days_must_be_positive")
        }

        lendingDao.getLendingChannelById(request.lendingId.uppercase())?.let { existing ->
            validateCompatibleChannel(existing, request.displayName, request.requiredStakeAmount, request.minimumVoteCount, request.maxLoanDurationDays, request.stakeTokenMint, request.stakeTokenSymbol, request.stakeTokenDecimals)
            return existing
        }

        lendingDao.getLendingChannelByChannelKey(request.channelKey)?.let { existing ->
            validateCompatibleChannel(existing, request.displayName, request.requiredStakeAmount, request.minimumVoteCount, request.maxLoanDurationDays, request.stakeTokenMint, request.stakeTokenSymbol, request.stakeTokenDecimals)
            return existing
        }

        val entity = LendingChannelEntity(
            lendingId = request.lendingId.uppercase(),
            channelKey = request.channelKey,
            displayName = request.displayName,
            creatorPeerId = request.creatorPeerId,
            creatorWalletAddress = request.creatorWalletAddress,
            requiredStakeAmount = request.requiredStakeAmount,
            minimumVoteCount = request.minimumVoteCount,
            maxLoanDurationDays = request.maxLoanDurationDays,
            stakeTokenMint = request.stakeTokenMint,
            stakeTokenSymbol = request.stakeTokenSymbol,
            stakeTokenDecimals = request.stakeTokenDecimals
        )
        lendingDao.insertLendingChannel(entity)
        val seededStakeAmount = if (request.seedCreatorMembership) request.requiredStakeAmount else 0L
        lendingDao.upsertPoolSnapshot(
            LendingPoolSnapshotEntity(
                lendingId = entity.lendingId,
                totalStakedAmount = seededStakeAmount,
                reservedAmount = 0L,
                disbursedAmount = 0L,
                availableLiquidityAmount = seededStakeAmount
            )
        )
        if (request.seedCreatorMembership) {
            lendingDao.upsertMembership(
                LendingMembershipEntity(
                    lendingId = entity.lendingId,
                    memberPeerId = request.creatorPeerId,
                    walletAddress = request.creatorWalletAddress,
                    stakeAmount = request.requiredStakeAmount,
                    depositStatus = EscrowTransferStatus.CONFIRMED,
                    joinStatus = LendingMemberStatus.ACTIVE,
                    credibilityScore = 100,
                    credibilitySnapshotJson = """{"source":"explicit_invite"}"""
                )
            )
        }
        return entity
    }

    override suspend fun configureSquad(request: ConfigureLendingSquadRequest): LendingChannelEntity {
        val channel = getChannelByIdentifier(request.identifier, request.preferredChannelKey)
            ?: throw IllegalArgumentException("lending_channel_not_found")
        if (channel.creatorPeerId != request.actorPeerId) {
            throw IllegalStateException("owner_only_squad_configuration")
        }
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

        return persistSquadConfiguration(channel, multisigAddress, vaultAddress)
    }

    override suspend fun createSquad(request: CreateLendingSquadRequest): LendingChannelEntity {
        val channel = getChannelByIdentifier(request.identifier, request.preferredChannelKey)
            ?: throw IllegalArgumentException("lending_channel_not_found")
        if (channel.creatorPeerId != request.actorPeerId) {
            throw IllegalStateException("owner_only_squad_configuration")
        }
        val created = squadsService.createLendingMultisig(
            memberWallets = request.memberWalletAddresses,
            threshold = request.threshold
        ).getOrElse { throw it }
        return persistSquadConfiguration(channel, created.multisigAddress, created.vaultAddress)
    }

    private suspend fun persistSquadConfiguration(
        channel: LendingChannelEntity,
        multisigAddress: String,
        vaultAddress: String
    ): LendingChannelEntity {
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
        refreshPoolSnapshot(request.lendingId)
        return membership
    }

    override suspend fun importMembershipUpdate(
        message: LendingMembershipMessage,
        senderPeerId: String?
    ): LendingMembershipEntity? {
        val channel = lendingDao.getLendingChannelById(message.lendingId.uppercase()) ?: return null
        if (senderPeerId.isNullOrBlank() || senderPeerId != message.memberPeerId) return null
        if (message.walletAddress.isBlank()) return null
        if (message.stakeAmount != channel.requiredStakeAmount) return null

        val normalizedJoinStatus = when (message.joinStatus) {
            LendingMemberStatus.ACTIVE,
            LendingMemberStatus.PENDING,
            LendingMemberStatus.SUSPENDED,
            LendingMemberStatus.EXITED -> message.joinStatus
            else -> return null
        }
        val normalizedDepositStatus = when (message.depositStatus) {
            EscrowTransferStatus.CONFIRMED,
            EscrowTransferStatus.PENDING,
            EscrowTransferStatus.FAILED,
            EscrowTransferStatus.RELEASED -> message.depositStatus
            else -> return null
        }

        val existing = lendingDao.getMembership(channel.lendingId, message.memberPeerId)
        val importedJoinStatus = when {
            existing?.joinStatus == LendingMemberStatus.ACTIVE &&
                existing.depositStatus == EscrowTransferStatus.CONFIRMED -> existing.joinStatus
            normalizedJoinStatus == LendingMemberStatus.EXITED -> LendingMemberStatus.EXITED
            else -> LendingMemberStatus.PENDING
        }
        val importedDepositStatus = when {
            existing?.joinStatus == LendingMemberStatus.ACTIVE &&
                existing.depositStatus == EscrowTransferStatus.CONFIRMED -> existing.depositStatus
            normalizedJoinStatus == LendingMemberStatus.EXITED ||
                normalizedDepositStatus == EscrowTransferStatus.RELEASED -> EscrowTransferStatus.RELEASED
            else -> EscrowTransferStatus.PENDING
        }
        val membership = (existing ?: LendingMembershipEntity(
            lendingId = channel.lendingId,
            memberPeerId = message.memberPeerId,
            walletAddress = message.walletAddress,
            stakeAmount = message.stakeAmount
        )).copy(
            walletAddress = message.walletAddress,
            stakeAmount = message.stakeAmount,
            depositStatus = importedDepositStatus,
            joinStatus = importedJoinStatus,
            updatedAt = maxOf(existing?.updatedAt ?: 0L, message.updatedAt, System.currentTimeMillis())
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

    private suspend fun refreshPoolSnapshot(lendingId: String) {
        val memberships = lendingDao.getMembershipsForLendingChannel(lendingId)
        val activeStake = memberships
            .filter { it.joinStatus == LendingMemberStatus.ACTIVE && it.depositStatus == EscrowTransferStatus.CONFIRMED }
            .sumOf { it.stakeAmount }
        val loanRequests = lendingDao.getLoanRequestsForLendingChannel(lendingId)
        val confirmedRepaymentsByRequest = loanRequests.associate { request ->
            request.requestId to lendingDao.getRepaymentsForRequest(request.requestId)
                .filter { it.txStatus == EscrowTransferStatus.CONFIRMED }
                .sumOf { it.amount }
        }
        val totalRepayments = confirmedRepaymentsByRequest.values.sum()
        val reservedAmount = loanRequests
            .filter { it.status == LoanRequestStatus.APPROVED }
            .sumOf { request ->
                max(request.principalAmount - (confirmedRepaymentsByRequest[request.requestId] ?: 0L), 0L)
            }
        val disbursedAmount = loanRequests
            .filter { it.status in setOf(LoanRequestStatus.DISBURSED, LoanRequestStatus.REPAID, LoanRequestStatus.DEFAULTED) }
            .sumOf { request ->
                max(request.principalAmount - (confirmedRepaymentsByRequest[request.requestId] ?: 0L), 0L)
            }
        lendingDao.upsertPoolSnapshot(
            LendingPoolSnapshotEntity(
                lendingId = lendingId,
                totalStakedAmount = activeStake,
                reservedAmount = reservedAmount,
                disbursedAmount = disbursedAmount,
                availableLiquidityAmount = max(activeStake + totalRepayments - reservedAmount - disbursedAmount, 0L),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun validateCompatibleChannel(
        existing: LendingChannelEntity,
        requestedDisplayName: String,
        requiredStakeAmount: Long,
        minimumVoteCount: Int,
        maxLoanDurationDays: Int,
        stakeTokenMint: String,
        stakeTokenSymbol: String,
        stakeTokenDecimals: Int
    ) {
        val sameAsset = if (
            isNativeSolStakeAsset(existing.stakeTokenMint, existing.stakeTokenSymbol) &&
            isNativeSolStakeAsset(stakeTokenMint, stakeTokenSymbol)
        ) {
            true
        } else {
            existing.stakeTokenMint == stakeTokenMint &&
                existing.stakeTokenSymbol == stakeTokenSymbol &&
                existing.stakeTokenDecimals == stakeTokenDecimals
        }
        val sameStake = existing.requiredStakeAmount == requiredStakeAmount
        val sameMinimumVotes = existing.minimumVoteCount == minimumVoteCount
        val sameDuration = existing.maxLoanDurationDays == maxLoanDurationDays
        val sameName = existing.displayName.equals(requestedDisplayName, ignoreCase = true)
        if (!sameAsset || !sameStake || !sameMinimumVotes || !sameDuration || !sameName) {
            throw IllegalStateException("lending_channel_conflict")
        }
    }
}
