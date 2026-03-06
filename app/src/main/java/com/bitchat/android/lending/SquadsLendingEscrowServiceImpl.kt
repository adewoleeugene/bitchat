package com.bitchat.android.lending

import com.bitchat.android.data.local.LendingDao
import com.bitchat.android.data.local.entities.AppProposalStatus
import com.bitchat.android.data.local.entities.BorrowerType
import com.bitchat.android.data.local.entities.CustodyExecutionStatus
import com.bitchat.android.data.local.entities.EscrowCustodyState
import com.bitchat.android.data.local.entities.EscrowProposalType
import com.bitchat.android.data.local.entities.EscrowProvider
import com.bitchat.android.data.local.entities.EscrowTransferStatus
import com.bitchat.android.data.local.entities.LendingChannelEntity
import com.bitchat.android.data.local.entities.LendingEscrowAccountEntity
import com.bitchat.android.data.local.entities.LendingEscrowProposalEntity
import com.bitchat.android.data.local.entities.LendingMemberStatus
import com.bitchat.android.data.local.entities.LendingMembershipEntity
import com.bitchat.android.data.local.entities.LendingPoolSnapshotEntity
import com.bitchat.android.data.local.entities.LoanRequestEntity
import com.bitchat.android.solana.LendingTransferGateway
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SquadsLendingEscrowServiceImpl @Inject constructor(
    private val lendingDao: LendingDao,
    private val transferGateway: LendingTransferGateway
) : LendingEscrowService {

    override suspend fun getMemberships(lendingId: String): List<LendingMembershipEntity> {
        return lendingDao.getMembershipsForLendingChannel(lendingId)
    }

    override suspend fun getPoolSnapshot(lendingId: String): LendingPoolSnapshotEntity? {
        return lendingDao.getPoolSnapshot(lendingId)
    }

    override suspend fun activateMembership(lendingId: String, memberPeerId: String): LendingMembershipEntity {
        provisionChannelEscrow(lendingId)
        val existing = lendingDao.getMembership(lendingId, memberPeerId)
            ?: throw IllegalArgumentException("membership_not_found")
        val updated = existing.copy(
            depositStatus = EscrowTransferStatus.CONFIRMED,
            joinStatus = LendingMemberStatus.ACTIVE,
            updatedAt = System.currentTimeMillis()
        )
        lendingDao.upsertMembership(updated)
        return updated
    }

    override suspend fun releaseMembershipStake(lendingId: String, memberPeerId: String): LendingMembershipEntity {
        val existing = lendingDao.getMembership(lendingId, memberPeerId)
            ?: throw IllegalArgumentException("membership_not_found")
        val updated = existing.copy(
            depositStatus = EscrowTransferStatus.RELEASED,
            joinStatus = LendingMemberStatus.EXITED,
            updatedAt = System.currentTimeMillis()
        )
        lendingDao.upsertMembership(updated)
        return updated
    }

    override suspend fun provisionChannelEscrow(lendingId: String): LendingEscrowAccountEntity {
        lendingDao.getEscrowAccount(lendingId)?.let { return it }
        val channel = getChannel(lendingId)
        val account = LendingEscrowAccountEntity(
            lendingId = lendingId,
            multisigAddress = "SQDS${lendingId}MULTI",
            vaultAddress = "SQDS${lendingId}VAULT",
            provider = EscrowProvider.SQUADS,
            custodyState = EscrowCustodyState.ACTIVE
        )
        lendingDao.upsertEscrowAccount(account)
        if (channel.escrowMultisigAddress != account.multisigAddress) {
            lendingDao.insertLendingChannel(
                channel.copy(
                    escrowMultisigAddress = account.multisigAddress,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        return account
    }

    override suspend fun getEscrowAccount(lendingId: String): LendingEscrowAccountEntity? {
        return lendingDao.getEscrowAccount(lendingId)
    }

    override suspend fun getEscrowProposalsForRequest(requestId: String): List<LendingEscrowProposalEntity> {
        return lendingDao.getEscrowProposalsForRequest(requestId)
    }

    override suspend fun createLoanDisbursementProposal(requestId: String): LendingEscrowProposalEntity {
        lendingDao.getEscrowProposalsForRequest(requestId)
            .firstOrNull { it.proposalType == EscrowProposalType.LOAN_DISBURSEMENT }
            ?.let { return it }

        val loan = lendingDao.getLoanRequestById(requestId)
            ?: throw IllegalArgumentException("loan_request_not_found")
        val channel = getChannel(loan.lendingId)
        val account = provisionChannelEscrow(loan.lendingId)
        val targetWallet = resolveLoanRecipientWallet(loan, channel)
        val proposal = LendingEscrowProposalEntity(
            proposalId = nextProposalId(),
            lendingId = loan.lendingId,
            requestId = loan.requestId,
            proposalType = EscrowProposalType.LOAN_DISBURSEMENT,
            appApprovalStatus = AppProposalStatus.APPROVED,
            custodyExecutionStatus = CustodyExecutionStatus.CREATED,
            targetWalletAddress = targetWallet,
            mintAddress = channel.stakeTokenMint,
            amountAtomic = loan.principalAmount
        )
        return executeProposal(proposal, channel, account)
    }

    override suspend fun createStakeReleaseProposal(
        lendingId: String,
        memberPeerId: String
    ): LendingEscrowProposalEntity {
        lendingDao.getEscrowProposalsForLendingChannel(lendingId)
            .firstOrNull {
                it.memberPeerId == memberPeerId && it.proposalType == EscrowProposalType.STAKE_RELEASE &&
                    it.custodyExecutionStatus != CustodyExecutionStatus.FAILED
            }?.let { return it }

        val channel = getChannel(lendingId)
        val membership = lendingDao.getMembership(lendingId, memberPeerId)
            ?: throw IllegalArgumentException("membership_not_found")
        val account = provisionChannelEscrow(lendingId)
        val proposal = LendingEscrowProposalEntity(
            proposalId = nextProposalId(),
            lendingId = lendingId,
            memberPeerId = memberPeerId,
            proposalType = EscrowProposalType.STAKE_RELEASE,
            appApprovalStatus = AppProposalStatus.APPROVED,
            custodyExecutionStatus = CustodyExecutionStatus.CREATED,
            targetWalletAddress = membership.walletAddress,
            mintAddress = channel.stakeTokenMint,
            amountAtomic = membership.stakeAmount
        )
        return executeProposal(proposal, channel, account)
    }

    override suspend fun reconcilePendingEscrowOperations(): List<LendingEscrowProposalEntity> {
        val pending = lendingDao.getPendingEscrowProposals()
        val reconciled = mutableListOf<LendingEscrowProposalEntity>()
        for (proposal in pending) {
            val channel = getChannel(proposal.lendingId)
            val account = provisionChannelEscrow(proposal.lendingId)
            reconciled += executeProposal(proposal, channel, account)
        }
        return reconciled
    }

    private suspend fun executeProposal(
        proposal: LendingEscrowProposalEntity,
        channel: LendingChannelEntity,
        account: LendingEscrowAccountEntity
    ): LendingEscrowProposalEntity {
        val transferResult = transferGateway.queueSplTransfer(
            recipientPublicKey = proposal.targetWalletAddress,
            mintAddress = proposal.mintAddress,
            amountAtomic = proposal.amountAtomic,
            decimals = channel.stakeTokenDecimals,
            symbol = channel.stakeTokenSymbol.ifBlank { "TOKEN" },
            memo = "squads ${proposal.proposalType.lowercase()} ${proposal.proposalId}"
        )
        val updated = if (transferResult.isSuccess) {
            proposal.copy(
                custodyExecutionStatus = CustodyExecutionStatus.EXECUTED,
                txSignature = transferResult.getOrNull(),
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            proposal.copy(
                custodyExecutionStatus = CustodyExecutionStatus.FAILED,
                txSignature = null,
                errorMessage = transferResult.exceptionOrNull()?.message,
                updatedAt = System.currentTimeMillis()
            )
        }
        lendingDao.upsertEscrowProposal(updated)
        lendingDao.upsertEscrowAccount(account.copy(updatedAt = System.currentTimeMillis()))
        return updated
    }

    private suspend fun resolveLoanRecipientWallet(
        loan: LoanRequestEntity,
        channel: LendingChannelEntity
    ): String {
        return if (loan.borrowerType == BorrowerType.INDIVIDUAL) {
            val borrowerWallet = loan.borrowerPeerId?.let { peerId ->
                lendingDao.getMembership(loan.lendingId, peerId)?.walletAddress
            }
            borrowerWallet ?: throw IllegalStateException("borrower_wallet_not_found")
        } else {
            channel.creatorWalletAddress
        }
    }

    private suspend fun getChannel(lendingId: String): LendingChannelEntity {
        return lendingDao.getLendingChannelById(lendingId)
            ?: throw IllegalArgumentException("lending_channel_not_found")
    }

    private fun nextProposalId(): String {
        return "SQP-${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"
    }
}
