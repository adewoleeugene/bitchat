package com.bitchat.android.data.models

/**
 * Status of a queued Solana transaction.
 */
enum class TransactionStatus(val value: String) {
    QUEUED("QUEUED"),                         // Created, waiting for broadcast
    AWAITING_BLOCKHASH("AWAITING_BLOCKHASH"), // Sent intent via mesh, waiting for blockhash response
    BROADCASTING("BROADCASTING"),             // Currently being sent to RPC
    CONFIRMED("CONFIRMED"),                   // On-chain confirmation received
    FAILED("FAILED");                         // Broadcast failed after retries

    companion object {
        fun fromString(value: String): TransactionStatus {
            // Backward compatibility for rows stored before QUEUED rename.
            if (value == "PENDING") return QUEUED
            return entries.firstOrNull { it.value == value } ?: QUEUED
        }
    }
}
