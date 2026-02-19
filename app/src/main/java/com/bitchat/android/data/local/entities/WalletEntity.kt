package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity storing Solana wallet metadata.
 * Private keys are NOT stored here — they live in Android Keystore.
 */
@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey
    val publicKey: String,       // Base58 Solana public key
    val createdAt: Long,         // Unix timestamp millis
    val isActive: Boolean,       // Currently selected wallet
    val label: String = "",      // User-assigned label
    val lastBalanceLamports: Long = 0, // Cached balance in lamports (1 SOL = 1_000_000_000)
    val lastBalanceUpdatedAt: Long = 0 // When balance was last fetched
)
