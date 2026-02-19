package com.bitchat.android.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores message notarization records for blockchain timestamping.
 *
 * Workflow:
 * 1. User long-presses a message and selects "Notarize"
 * 2. SHA-256 hash of (sender + content + timestamp) is computed locally
 * 3. Record created with status QUEUED
 * 4. When connectivity is available, hashes are batched and posted to Solana via Memo program
 * 5. On-chain tx signature, slot, and block time are stored as proof
 */
@Entity(tableName = "message_notarizations")
data class MessageNotarizationEntity(
    @PrimaryKey
    val messageId: String,                  // BitchatMessage.id
    val messageHash: String,                // SHA-256 hex of (sender|content|timestamp)
    val senderNickname: String,             // For display
    val contentPreview: String,             // First 50 chars for display
    val messageTimestamp: Long,             // Original message timestamp (epoch ms)
    val status: String,                     // QUEUED, BROADCASTING, CONFIRMED, FAILED
    val createdAt: Long,                    // When notarization was requested
    val txSignature: String? = null,        // On-chain transaction signature (Base58)
    val slot: Long? = null,                 // Solana slot number
    val blockTime: Long? = null,            // On-chain block timestamp (epoch seconds)
    val errorMessage: String? = null,       // Error description if failed
    val batchId: String? = null             // Groups messages notarized in the same tx
)

/**
 * Notarization status constants.
 */
object NotarizationStatus {
    const val QUEUED = "QUEUED"
    const val BROADCASTING = "BROADCASTING"
    const val CONFIRMED = "CONFIRMED"
    const val FAILED = "FAILED"
}
