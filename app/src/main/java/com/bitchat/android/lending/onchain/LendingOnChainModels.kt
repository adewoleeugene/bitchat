package com.bitchat.android.lending.onchain

import com.bitchat.android.data.local.entities.LoanChainStatus
import com.bitchat.android.data.local.entities.LoanRequestStatus

data class LendingProgramConfig(
    val programId: String = DEFAULT_LENDING_PROGRAM_ID,
    val enabled: Boolean = false
)

data class InitializeLendingChannelOnChainParams(
    val lendingId: String,
    val creatorWallet: String,
    val quorumThresholdPercent: Int,
    val approvalThresholdPercent: Int,
    val memberCount: Int,
    val lifecycleState: Int = 0,
    val requiredStakeAmount: Long,
    val stakeTokenMint: String,
    val stakeTokenDecimals: Int,
    val createdAt: Long = System.currentTimeMillis()
)

data class OnChainLoanRequestState(
    val channelPda: String,
    val loanRequestPda: String,
    val borrowerWallet: String,
    val principalAmount: Long,
    val durationDays: Int,
    val interestBps: Int,
    val purposeHashHex: String,
    val yesVotes: Int,
    val noVotes: Int,
    val requestedAt: Long,
    val dueAt: Long,
    val approvedAt: Long?,
    val disbursedAt: Long?,
    val repaidAt: Long?,
    val totalRepaidAmount: Long,
    val chainStatus: String,
    val txSignature: String? = null,
    val slot: Long? = null
)

data class OnChainVoteRecord(
    val voteRecordPda: String,
    val loanRequestPda: String,
    val voterWallet: String,
    val voteChoice: String,
    val votedAt: Long
)

data class CreateLoanRequestOnChainParams(
    val lendingId: String,
    val requestId: String,
    val borrowerWallet: String,
    val principalAmount: Long,
    val durationDays: Int,
    val interestBps: Int,
    val purpose: String,
    val requestedAt: Long,
    val dueAt: Long
)

data class CastLoanVoteOnChainParams(
    val lendingId: String,
    val requestId: String,
    val voteChoice: String,
    val votedAt: Long = System.currentTimeMillis()
)

data class FinalizeLoanRequestOnChainParams(
    val lendingId: String,
    val requestId: String,
    val finalizedAt: Long = System.currentTimeMillis()
)

data class RecordLoanRepaymentOnChainParams(
    val lendingId: String,
    val requestId: String,
    val amount: Long,
    val paidAt: Long = System.currentTimeMillis()
)

data class OnChainSubmissionResult(
    val channelPda: String,
    val loanRequestPda: String? = null,
    val txSignature: String,
    val voteRecordPda: String? = null,
    val slot: Long? = null,
    val chainStatus: String = LoanChainStatus.SUBMITTED
)

fun mapChainLoanStatus(raw: Int): String {
    return when (raw) {
        0 -> LoanRequestStatus.PENDING
        1 -> LoanRequestStatus.APPROVED
        2 -> LoanRequestStatus.REJECTED
        3 -> LoanRequestStatus.DISBURSED
        4 -> LoanRequestStatus.REPAID
        5 -> LoanRequestStatus.DEFAULTED
        6 -> LoanRequestStatus.CANCELLED
        else -> LoanRequestStatus.PENDING
    }
}

const val DEFAULT_LENDING_PROGRAM_ID = "11111111111111111111111111111111"
