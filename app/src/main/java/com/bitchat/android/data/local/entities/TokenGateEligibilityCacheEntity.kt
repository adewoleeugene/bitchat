package com.bitchat.android.data.local.entities

import androidx.room.Entity

/**
 * Cached token gate validation outcome for a specific wallet and gate version.
 * Primary key is composite so policy changes (gateHash) automatically invalidate old cache entries.
 */
@Entity(
    tableName = "token_gate_eligibility_cache",
    primaryKeys = ["channelKey", "walletAddress", "gateHash"]
)
data class TokenGateEligibilityCacheEntity(
    val channelKey: String,
    val walletAddress: String,
    val gateHash: String,
    val isEligible: Boolean,
    val observedBalance: Long,
    val validatedAt: Long,
    val expiresAt: Long,
    val source: String,     // RPC, CACHE
    val rpcSlot: Long? = null,
    val errorCode: String? = null
)

object TokenGateValidationSource {
    const val RPC = "RPC"
    const val CACHE = "CACHE"
}

