package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for transactions queued for broadcast.
 * Transactions created offline via Bluetooth are stored here
 * and broadcast when internet connectivity is restored.
 */
@Entity(tableName = "queued_transactions")
data class QueuedTransactionEntity(
    @PrimaryKey
    val id: String,                     // UUID
    val signedTransactionBase64: String, // Signed tx bytes, base64-encoded
    val senderPublicKey: String,        // Sender wallet address (Base58)
    val recipientPublicKey: String,     // Recipient wallet address (Base58)
    val amountLamports: Long,           // Amount in atomic units for the selected asset
    val assetKind: String = "NATIVE_SOL",
    val assetMintAddress: String? = null,
    val assetSymbol: String = "SOL",
    val assetDecimals: Int = 9,
    val status: String,                 // QUEUED, BROADCASTING, CONFIRMED, FAILED
    val createdAt: Long,                // Unix timestamp millis
    val lastAttemptAt: Long = 0,        // Last broadcast attempt timestamp
    val attemptCount: Int = 0,          // Number of broadcast attempts
    val txSignature: String? = null,    // On-chain signature once broadcast
    val errorMessage: String? = null,   // Last error if failed
    val ttlExpiresAt: Long = 0          // Transaction expiry (default 24h from creation)
)
