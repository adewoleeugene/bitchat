package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lending_escrow_proposals",
    indices = [
        Index(value = ["lendingId"]),
        Index(value = ["requestId"]),
        Index(value = ["proposalType"]),
        Index(value = ["custodyExecutionStatus"])
    ]
)
data class LendingEscrowProposalEntity(
    @PrimaryKey
    val proposalId: String,
    val lendingId: String,
    val requestId: String? = null,
    val memberPeerId: String? = null,
    val proposalType: String,
    val appApprovalStatus: String,
    val custodyExecutionStatus: String,
    val targetWalletAddress: String,
    val mintAddress: String,
    val amountAtomic: Long,
    val txSignature: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object EscrowProposalType {
    const val STAKE_DEPOSIT = "STAKE_DEPOSIT"
    const val LOAN_DISBURSEMENT = "LOAN_DISBURSEMENT"
    const val STAKE_RELEASE = "STAKE_RELEASE"
}

object AppProposalStatus {
    const val PENDING = "PENDING"
    const val APPROVED = "APPROVED"
    const val REJECTED = "REJECTED"
}

object CustodyExecutionStatus {
    const val CREATED = "CREATED"
    const val EXECUTED = "EXECUTED"
    const val FAILED = "FAILED"
}
