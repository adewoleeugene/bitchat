package com.bitchat.android.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "loan_requests",
    indices = [
        Index(value = ["lendingId"]),
        Index(value = ["status"]),
        Index(value = ["borrowerPeerId"]),
        Index(value = ["parentRequestId"]),
        Index(value = ["originLendingId"]),
        Index(value = ["fundingLendingId"]),
        Index(value = ["loanRequestPda"]),
        Index(value = ["squadsProposalAddress"])
    ]
)
data class LoanRequestEntity(
    @PrimaryKey
    val requestId: String,
    val lendingId: String,
    val borrowerType: String,
    val borrowerPeerId: String? = null,
    val borrowerWalletAddress: String? = null,
    val borrowerGroupKey: String? = null,
    val principalAmount: Long,
    val interestBps: Int,
    val durationDays: Int,
    val purpose: String,
    @ColumnInfo(defaultValue = "'[]'")
    val endorserPeerIdsJson: String = "[]",
    val status: String = LoanRequestStatus.PENDING,
    val requestedAt: Long = System.currentTimeMillis(),
    val dueAt: Long = 0,
    val approvedAt: Long? = null,
    val disbursedAt: Long? = null,
    val defaultedAt: Long? = null,
    val parentRequestId: String? = null,
    @ColumnInfo(defaultValue = "'ORIGIN'")
    val requestKind: String = LoanRequestKind.ORIGIN,
    val originLendingId: String? = null,
    val forwardedFromRequestId: String? = null,
    val fundingLendingId: String? = null,
    val squadsMultisigAddress: String? = null,
    val squadsVaultAddress: String? = null,
    val squadsProposalAddress: String? = null,
    val squadsTransactionIndex: Long? = null,
    val channelPda: String? = null,
    val loanRequestPda: String? = null,
    val lastChainSyncSignature: String? = null,
    val lastChainSyncedSlot: Long? = null,
    @ColumnInfo(defaultValue = "'LOCAL_ONLY'")
    val chainStatus: String = LoanChainStatus.LOCAL_ONLY,
    @ColumnInfo(defaultValue = "'VOTER_BACKED'")
    val backingModel: String = LoanBackingModel.VOTER_BACKED
)

object BorrowerType {
    const val INDIVIDUAL = "INDIVIDUAL"
    const val GROUP = "GROUP"
}

object LoanRequestStatus {
    const val PENDING = "PENDING"
    const val COMMUNITY_APPROVED = "COMMUNITY_APPROVED"
    const val COMMUNITY_REJECTED = "COMMUNITY_REJECTED"
    const val APPROVED = COMMUNITY_APPROVED
    const val REJECTED = COMMUNITY_REJECTED
    const val SIGNER_REVIEW = "SIGNER_REVIEW"
    const val SIGNER_APPROVED = "SIGNER_APPROVED"
    const val SIGNER_REJECTED = "SIGNER_REJECTED"
    const val DISBURSED = "DISBURSED"
    const val PARTIALLY_REPAID = "PARTIALLY_REPAID"
    const val REPAID = "REPAID"
    const val OVERDUE = "OVERDUE"
    const val FUNDED_ELSEWHERE = "FUNDED_ELSEWHERE"
    const val DEFAULTED = "DEFAULTED"
    const val CANCELLED = "CANCELLED"
}

object LoanRequestKind {
    const val ORIGIN = "ORIGIN"
    const val FORWARDED_COPY = "FORWARDED_COPY"
}

object LoanChainStatus {
    const val LOCAL_ONLY = "LOCAL_ONLY"
    const val PENDING_SUBMISSION = "PENDING_SUBMISSION"
    const val SUBMITTED = "SUBMITTED"
    const val CONFIRMED = "CONFIRMED"
    const val FAILED = "FAILED"
}

object LoanBackingModel {
    const val POOL = "POOL"
    const val VOTER_BACKED = "VOTER_BACKED"
}
