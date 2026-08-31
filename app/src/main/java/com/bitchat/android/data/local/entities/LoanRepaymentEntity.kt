package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "loan_repayments",
    indices = [
        Index(value = ["requestId"]),
        Index(value = ["lendingId"])
    ]
)
data class LoanRepaymentEntity(
    @PrimaryKey
    val repaymentId: String,
    val requestId: String,
    val lendingId: String,
    val amount: Long,
    val txSignature: String? = null,
    val txStatus: String = EscrowTransferStatus.PENDING,
    val paidAt: Long = System.currentTimeMillis()
)
