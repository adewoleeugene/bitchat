package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for token-gated channel configuration.
 * Stores which token/NFT is required to join a specific channel.
 */
@Entity(tableName = "token_gate_configs")
data class TokenGateConfigEntity(
    @PrimaryKey
    val channelKey: String,              // Composite channel key (e.g., "mesh:#premium")
    val gateType: String,                // SPL_TOKEN, NFT_COLLECTION, NFT_SPECIFIC
    val tokenMintAddress: String,        // SPL token mint or NFT collection address (Base58)
    val minBalance: Long,                // Minimum token balance required (in smallest unit)
    val tokenSymbol: String = "",        // Display symbol (e.g., "$CHAT", "USDC")
    val tokenDecimals: Int = 0,          // Token decimals for display formatting
    val creatorPublicKey: String = "",   // Solana address of channel creator
    val createdAt: Long = System.currentTimeMillis(),
    val policyVersion: Int = 1,          // Monotonic gate policy version for this channel
    val gateHash: String = "",           // Stable hash of gate fields (type/mint/min/symbol/decimals/version)
    val lastValidatedAt: Long = 0,       // Last time we checked user's eligibility
    val isUserEligible: Boolean = false, // Cached eligibility result
    val validationTtlMs: Long = 24 * 60 * 60 * 1000L // TTL for cached validation (default 24h)
)

/**
 * Token gate types supported.
 */
object TokenGateType {
    const val SPL_TOKEN = "SPL_TOKEN"
    const val NFT_COLLECTION = "NFT_COLLECTION"
    const val NFT_SPECIFIC = "NFT_SPECIFIC"
}
