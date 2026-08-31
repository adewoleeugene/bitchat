package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "lending_channels",
    indices = [
        Index(value = ["channelKey"], unique = true)
    ]
)
data class LendingChannelEntity(
    @PrimaryKey
    val lendingId: String,
    val channelKey: String,
    val displayName: String,
    val creatorPeerId: String,
    val creatorWalletAddress: String = "",
    val requiredStakeAmount: Long,
    val minimumVoteCount: Int = 2,
    @ColumnInfo(name = "defaultLoanDurationDays")
    val maxLoanDurationDays: Int = 14,
    val stakeTokenMint: String,
    val stakeTokenSymbol: String = "",
    val stakeTokenDecimals: Int = 6,
    val escrowMultisigAddress: String = "",
    val quorumThresholdPercent: Int = 60,
    val approvalThresholdPercent: Int = 50,
    val votingWindowHours: Int = 24,
    val defaultGracePeriodDays: Int = 7,
    val lifecycleState: String = LendingLifecycleState.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object LendingLifecycleState {
    const val ACTIVE = "ACTIVE"
    const val PAUSED = "PAUSED"
    const val CLOSED = "CLOSED"
}
