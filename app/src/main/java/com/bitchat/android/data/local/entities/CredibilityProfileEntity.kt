package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "credibility_profiles",
    indices = [
        Index(value = ["subjectType", "subjectKey"], unique = true)
    ]
)
data class CredibilityProfileEntity(
    @PrimaryKey
    val profileId: String,
    val subjectType: String,
    val subjectKey: String,
    val score: Int,
    val usageAgePoints: Int = 0,
    val participationPoints: Int = 0,
    val recentActivityPoints: Int = 0,
    val walletStrengthPoints: Int = 0,
    val hardGateStatus: String = CredibilityHardGateStatus.PENDING,
    val firstSeenAt: Long = 0,
    val lastComputedAt: Long = System.currentTimeMillis(),
    val snapshotJson: String = ""
)

object CredibilitySubjectType {
    const val PEER_ID = "PEER_ID"
    const val WALLET_ADDRESS = "WALLET_ADDRESS"
}

object CredibilityHardGateStatus {
    const val PENDING = "PENDING"
    const val PASSED = "PASSED"
    const val FAILED = "FAILED"
}
