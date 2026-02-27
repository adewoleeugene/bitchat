package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted per-channel token-gate policy state, including tombstones.
 * Prevents policy version rollback/replay across delete/recreate cycles.
 */
@Entity(tableName = "token_gate_policy_state")
data class TokenGatePolicyStateEntity(
    @PrimaryKey
    val channelKey: String,
    val creatorPublicKey: String,
    val lastPolicyVersion: Int,
    val lastGateHash: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val isRemoved: Boolean = false
)
