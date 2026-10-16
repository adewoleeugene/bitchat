package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "lending_memberships",
    primaryKeys = ["lendingId", "memberPeerId"],
    indices = [
        Index(value = ["walletAddress"]),
        Index(value = ["joinStatus"]),
        Index(value = ["depositStatus"])
    ]
)
data class LendingMembershipEntity(
    val lendingId: String,
    val memberPeerId: String,
    val walletAddress: String,
    val stakeAmount: Long,
    val lockedStakeAmount: Long = 0,
    val depositStatus: String = EscrowTransferStatus.PENDING,
    val joinStatus: String = LendingMemberStatus.PENDING,
    val suspendedReason: String? = null,
    val credibilityScore: Int = 0,
    val credibilitySnapshotJson: String = "",
    val joinedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object LendingMemberStatus {
    const val PENDING = "PENDING"
    const val ACTIVE = "ACTIVE"
    const val SUSPENDED = "SUSPENDED"
    const val EXITED = "EXITED"
}

object EscrowTransferStatus {
    const val PENDING = "PENDING"
    const val CONFIRMED = "CONFIRMED"
    const val FAILED = "FAILED"
    const val RELEASED = "RELEASED"
}
