package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "loan_requests",
    indices = [
        Index(value = ["lendingId"]),
        Index(value = ["status"]),
        Index(value = ["borrowerPeerId"])
    ]
)
data class LoanRequestEntity(
    @PrimaryKey
    val requestId: String,
    val lendingId: String,
    val borrowerType: String,
    val borrowerPeerId: String? = null,
    val borrowerGroupKey: String? = null,
    val principalAmount: Long,
    val interestBps: Int,
    val durationDays: Int,
    val purpose: String,
    val status: String = LoanRequestStatus.PENDING,
    val requestedAt: Long = System.currentTimeMillis(),
    val dueAt: Long = 0,
    val approvedAt: Long? = null,
    val disbursedAt: Long? = null,
    val defaultedAt: Long? = null
)

object BorrowerType {
    const val INDIVIDUAL = "INDIVIDUAL"
    const val GROUP = "GROUP"
}

object LoanRequestStatus {
    const val PENDING = "PENDING"
    const val APPROVED = "APPROVED"
    const val REJECTED = "REJECTED"
    const val DISBURSED = "DISBURSED"
    const val REPAID = "REPAID"
    const val DEFAULTED = "DEFAULTED"
    const val CANCELLED = "CANCELLED"
}
