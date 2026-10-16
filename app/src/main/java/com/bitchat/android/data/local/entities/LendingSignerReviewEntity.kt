package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lending_signer_reviews",
    indices = [
        Index(value = ["lendingId"]),
        Index(value = ["requestId"], unique = true),
        Index(value = ["status"])
    ]
)
data class LendingSignerReviewEntity(
    @PrimaryKey
    val reviewId: String,
    val lendingId: String,
    val requestId: String,
    val createdByPeerId: String,
    val status: String = LendingSignerReviewStatus.PENDING,
    val squadsProposalAddress: String? = null,
    val openedAt: Long = System.currentTimeMillis(),
    val approvedAt: Long? = null,
    val rejectedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

object LendingSignerReviewStatus {
    const val PENDING = "PENDING"
    const val APPROVED = "APPROVED"
    const val REJECTED = "REJECTED"
    const val CANCELLED = "CANCELLED"
}
