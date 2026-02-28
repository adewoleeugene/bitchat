package com.bitchat.android.solana

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format for Solana transaction relay messages over Bluetooth mesh.
 *
 * RELAY_REQUEST (0x30) payload:
 * - requestId: 16 bytes (UUID)
 * - signedTxLength: 2 bytes (big-endian)
 * - signedTxBase64: variable (base64-encoded signed transaction)
 * - senderPubKeyLength: 1 byte
 * - senderPubKey: variable (Base58 sender address for receipts)
 *
 * RELAY_RECEIPT (0x31) payload:
 * - requestId: 16 bytes (matches request)
 * - status: 1 byte (0=FAILED, 1=BROADCAST, 2=CONFIRMED)
 * - txSignatureLength: 1 byte
 * - txSignature: variable (Base58 on-chain signature, empty if failed)
 * - errorMessageLength: 2 bytes (big-endian)
 * - errorMessage: variable (UTF-8, empty if successful)
 */
data class SolanaRelayRequest(
    val requestId: String,        // UUID string
    val signedTxBase64: String,   // Base64-encoded signed Solana transaction
    val senderPubKey: String      // Sender's Solana address for routing receipts
) {
    fun encode(): ByteArray {
        val requestIdBytes = requestId.toByteArray(Charsets.UTF_8)
        val txBytes = signedTxBase64.toByteArray(Charsets.UTF_8)
        val senderBytes = senderPubKey.toByteArray(Charsets.UTF_8)

        val size = 1 + requestIdBytes.size + 2 + txBytes.size + 1 + senderBytes.size
        val buffer = ByteBuffer.allocate(size).apply { order(ByteOrder.BIG_ENDIAN) }

        // Request ID (length-prefixed)
        buffer.put(requestIdBytes.size.coerceAtMost(255).toByte())
        buffer.put(requestIdBytes.take(255).toByteArray())

        // Signed transaction (length-prefixed, 2 bytes)
        buffer.putShort(txBytes.size.coerceAtMost(65535).toShort())
        buffer.put(txBytes.take(65535).toByteArray())

        // Sender public key (length-prefixed, 1 byte)
        buffer.put(senderBytes.size.coerceAtMost(255).toByte())
        buffer.put(senderBytes.take(255).toByteArray())

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    companion object {
        fun decode(data: ByteArray): SolanaRelayRequest? {
            try {
                if (data.size < 5) return null
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                // Request ID
                val requestIdLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < requestIdLen) return null
                val requestIdBytes = ByteArray(requestIdLen)
                buffer.get(requestIdBytes)
                val requestId = String(requestIdBytes, Charsets.UTF_8)

                // Signed transaction
                if (buffer.remaining() < 2) return null
                val txLen = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < txLen) return null
                val txBytes = ByteArray(txLen)
                buffer.get(txBytes)
                val signedTxBase64 = String(txBytes, Charsets.UTF_8)

                // Sender public key
                if (buffer.remaining() < 1) return null
                val senderLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < senderLen) return null
                val senderBytes = ByteArray(senderLen)
                buffer.get(senderBytes)
                val senderPubKey = String(senderBytes, Charsets.UTF_8)

                return SolanaRelayRequest(requestId, signedTxBase64, senderPubKey)
            } catch (_: Exception) {
                return null
            }
        }
    }
}

/**
 * Status codes for relay receipts.
 */
object RelayReceiptStatus {
    const val FAILED: Byte = 0
    const val BROADCAST: Byte = 1
    const val CONFIRMED: Byte = 2
}

/**
 * Status codes for relay ACKs used by the guaranteed-delivery control plane.
 */
object RelayAckType {
    const val REQUEST_SEEN: Byte = 1
    const val CLAIM_SEEN: Byte = 2
    const val RECEIPT_SEEN: Byte = 3
}

data class SolanaRelayReceipt(
    val requestId: String,     // Matches the original request
    val status: Byte,          // RelayReceiptStatus
    val txSignature: String,   // On-chain signature (empty if failed)
    val errorMessage: String   // Error description (empty if successful)
) {
    fun encode(): ByteArray {
        val requestIdBytes = requestId.toByteArray(Charsets.UTF_8)
        val sigBytes = txSignature.toByteArray(Charsets.UTF_8)
        val errorBytes = errorMessage.toByteArray(Charsets.UTF_8)

        val size = 1 + requestIdBytes.size + 1 + 1 + sigBytes.size + 2 + errorBytes.size
        val buffer = ByteBuffer.allocate(size).apply { order(ByteOrder.BIG_ENDIAN) }

        // Request ID (length-prefixed)
        buffer.put(requestIdBytes.size.coerceAtMost(255).toByte())
        buffer.put(requestIdBytes.take(255).toByteArray())

        // Status
        buffer.put(status)

        // Transaction signature (length-prefixed, 1 byte)
        buffer.put(sigBytes.size.coerceAtMost(255).toByte())
        buffer.put(sigBytes.take(255).toByteArray())

        // Error message (length-prefixed, 2 bytes)
        buffer.putShort(errorBytes.size.coerceAtMost(65535).toShort())
        buffer.put(errorBytes.take(65535).toByteArray())

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    companion object {
        fun decode(data: ByteArray): SolanaRelayReceipt? {
            try {
                if (data.size < 4) return null
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                // Request ID
                val requestIdLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < requestIdLen) return null
                val requestIdBytes = ByteArray(requestIdLen)
                buffer.get(requestIdBytes)
                val requestId = String(requestIdBytes, Charsets.UTF_8)

                // Status
                if (buffer.remaining() < 1) return null
                val status = buffer.get()

                // Transaction signature
                if (buffer.remaining() < 1) return null
                val sigLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < sigLen) return null
                val sigBytes = ByteArray(sigLen)
                buffer.get(sigBytes)
                val txSignature = String(sigBytes, Charsets.UTF_8)

                // Error message
                if (buffer.remaining() < 2) return null
                val errorLen = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < errorLen) return null
                val errorBytes = ByteArray(errorLen)
                buffer.get(errorBytes)
                val errorMessage = String(errorBytes, Charsets.UTF_8)

                return SolanaRelayReceipt(requestId, status, txSignature, errorMessage)
            } catch (_: Exception) {
                return null
            }
        }
    }
}

/**
 * RELAY_CLAIM (0x34) payload:
 * - requestIdLength: 1 byte
 * - requestId: variable
 * - relayPeerIdLength: 1 byte
 * - relayPeerId: variable (mesh peer ID that claimed the relay)
 * - claimExpiresAtMs: 8 bytes (big-endian unix ms)
 */
data class SolanaRelayClaim(
    val requestId: String,
    val relayPeerId: String,
    val claimExpiresAtMs: Long
) {
    fun encode(): ByteArray {
        val requestBytes = requestId.toByteArray(Charsets.UTF_8)
        val relayPeerBytes = relayPeerId.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + requestBytes.size + 1 + relayPeerBytes.size + 8)
            .apply { order(ByteOrder.BIG_ENDIAN) }

        buffer.put(requestBytes.size.coerceAtMost(255).toByte())
        buffer.put(requestBytes.take(255).toByteArray())
        buffer.put(relayPeerBytes.size.coerceAtMost(255).toByte())
        buffer.put(relayPeerBytes.take(255).toByteArray())
        buffer.putLong(claimExpiresAtMs)

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    companion object {
        fun decode(data: ByteArray): SolanaRelayClaim? {
            return try {
                if (data.size < 11) return null
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                val requestLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < requestLen) return null
                val requestBytes = ByteArray(requestLen)
                buffer.get(requestBytes)
                val requestId = String(requestBytes, Charsets.UTF_8)

                val relayPeerLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < relayPeerLen) return null
                val relayPeerBytes = ByteArray(relayPeerLen)
                buffer.get(relayPeerBytes)
                val relayPeerId = String(relayPeerBytes, Charsets.UTF_8)

                if (buffer.remaining() < 8) return null
                val claimExpiresAtMs = buffer.getLong()

                SolanaRelayClaim(requestId, relayPeerId, claimExpiresAtMs)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * RELAY_ACK (0x35) payload:
 * - requestIdLength: 1 byte
 * - requestId: variable
 * - ackType: 1 byte (RelayAckType)
 * - peerIdLength: 1 byte
 * - peerId: variable (peer emitting the ACK)
 * - timestampMs: 8 bytes (big-endian unix ms)
 */
data class SolanaRelayAck(
    val requestId: String,
    val ackType: Byte,
    val peerId: String,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun encode(): ByteArray {
        val requestBytes = requestId.toByteArray(Charsets.UTF_8)
        val peerBytes = peerId.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + requestBytes.size + 1 + 1 + peerBytes.size + 8)
            .apply { order(ByteOrder.BIG_ENDIAN) }

        buffer.put(requestBytes.size.coerceAtMost(255).toByte())
        buffer.put(requestBytes.take(255).toByteArray())
        buffer.put(ackType)
        buffer.put(peerBytes.size.coerceAtMost(255).toByte())
        buffer.put(peerBytes.take(255).toByteArray())
        buffer.putLong(timestampMs)

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    companion object {
        fun decode(data: ByteArray): SolanaRelayAck? {
            return try {
                if (data.size < 12) return null
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                val requestLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < requestLen) return null
                val requestBytes = ByteArray(requestLen)
                buffer.get(requestBytes)
                val requestId = String(requestBytes, Charsets.UTF_8)

                if (buffer.remaining() < 1) return null
                val ackType = buffer.get()

                val peerLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < peerLen) return null
                val peerBytes = ByteArray(peerLen)
                buffer.get(peerBytes)
                val peerId = String(peerBytes, Charsets.UTF_8)

                if (buffer.remaining() < 8) return null
                val timestampMs = buffer.getLong()

                SolanaRelayAck(requestId, ackType, peerId, timestampMs)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * TRANSFER_INTENT (0x32) payload — unsigned transfer parameters from offline user.
 *
 * Wire format:
 * - intentIdLength: 1 byte
 * - intentId: variable (UUID string, matches tx.id)
 * - senderPubKeyLength: 1 byte
 * - senderPubKey: variable (Base58 sender address)
 * - recipientPubKeyLength: 1 byte
 * - recipientPubKey: variable (Base58 recipient address)
 * - amountLamports: 8 bytes (big-endian)
 */
data class SolanaTransferIntent(
    val intentId: String,
    val senderPubKey: String,
    val recipientPubKey: String,
    val amountLamports: Long
) {
    fun encode(): ByteArray {
        val intentIdBytes = intentId.toByteArray(Charsets.UTF_8)
        val senderBytes = senderPubKey.toByteArray(Charsets.UTF_8)
        val recipientBytes = recipientPubKey.toByteArray(Charsets.UTF_8)

        val size = 1 + intentIdBytes.size + 1 + senderBytes.size + 1 + recipientBytes.size + 8
        val buffer = ByteBuffer.allocate(size).apply { order(ByteOrder.BIG_ENDIAN) }

        buffer.put(intentIdBytes.size.coerceAtMost(255).toByte())
        buffer.put(intentIdBytes.take(255).toByteArray())

        buffer.put(senderBytes.size.coerceAtMost(255).toByte())
        buffer.put(senderBytes.take(255).toByteArray())

        buffer.put(recipientBytes.size.coerceAtMost(255).toByte())
        buffer.put(recipientBytes.take(255).toByteArray())

        buffer.putLong(amountLamports)

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    companion object {
        fun decode(data: ByteArray): SolanaTransferIntent? {
            try {
                if (data.size < 12) return null
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                val intentIdLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < intentIdLen) return null
                val intentIdBytes = ByteArray(intentIdLen)
                buffer.get(intentIdBytes)
                val intentId = String(intentIdBytes, Charsets.UTF_8)

                if (buffer.remaining() < 1) return null
                val senderLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < senderLen) return null
                val senderBytes = ByteArray(senderLen)
                buffer.get(senderBytes)
                val senderPubKey = String(senderBytes, Charsets.UTF_8)

                if (buffer.remaining() < 1) return null
                val recipientLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < recipientLen) return null
                val recipientBytes = ByteArray(recipientLen)
                buffer.get(recipientBytes)
                val recipientPubKey = String(recipientBytes, Charsets.UTF_8)

                if (buffer.remaining() < 8) return null
                val amountLamports = buffer.getLong()

                return SolanaTransferIntent(intentId, senderPubKey, recipientPubKey, amountLamports)
            } catch (_: Exception) {
                return null
            }
        }
    }
}

/**
 * BLOCKHASH_RESPONSE (0x33) payload — fresh blockhash from online peer.
 *
 * Wire format:
 * - intentIdLength: 1 byte
 * - intentId: variable (matches original intent)
 * - blockhashLength: 1 byte
 * - blockhash: variable (Base58 recent blockhash, empty on error)
 * - lastValidBlockHeight: 8 bytes (big-endian)
 * - errorMessageLength: 2 bytes (big-endian)
 * - errorMessage: variable (UTF-8, empty if successful)
 */
data class SolanaBlockhashResponse(
    val intentId: String,
    val blockhash: String,
    val lastValidBlockHeight: Long,
    val errorMessage: String
) {
    fun encode(): ByteArray {
        val intentIdBytes = intentId.toByteArray(Charsets.UTF_8)
        val blockhashBytes = blockhash.toByteArray(Charsets.UTF_8)
        val errorBytes = errorMessage.toByteArray(Charsets.UTF_8)

        val size = 1 + intentIdBytes.size + 1 + blockhashBytes.size + 8 + 2 + errorBytes.size
        val buffer = ByteBuffer.allocate(size).apply { order(ByteOrder.BIG_ENDIAN) }

        buffer.put(intentIdBytes.size.coerceAtMost(255).toByte())
        buffer.put(intentIdBytes.take(255).toByteArray())

        buffer.put(blockhashBytes.size.coerceAtMost(255).toByte())
        buffer.put(blockhashBytes.take(255).toByteArray())

        buffer.putLong(lastValidBlockHeight)

        buffer.putShort(errorBytes.size.coerceAtMost(65535).toShort())
        buffer.put(errorBytes.take(65535).toByteArray())

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    companion object {
        fun decode(data: ByteArray): SolanaBlockhashResponse? {
            try {
                if (data.size < 12) return null
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                val intentIdLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < intentIdLen) return null
                val intentIdBytes = ByteArray(intentIdLen)
                buffer.get(intentIdBytes)
                val intentId = String(intentIdBytes, Charsets.UTF_8)

                if (buffer.remaining() < 1) return null
                val blockhashLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < blockhashLen) return null
                val blockhashBytes = ByteArray(blockhashLen)
                buffer.get(blockhashBytes)
                val blockhash = String(blockhashBytes, Charsets.UTF_8)

                if (buffer.remaining() < 8) return null
                val lastValidBlockHeight = buffer.getLong()

                if (buffer.remaining() < 2) return null
                val errorLen = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < errorLen) return null
                val errorBytes = ByteArray(errorLen)
                buffer.get(errorBytes)
                val errorMessage = String(errorBytes, Charsets.UTF_8)

                return SolanaBlockhashResponse(intentId, blockhash, lastValidBlockHeight, errorMessage)
            } catch (_: Exception) {
                return null
            }
        }
    }
}

/**
 * BALANCE_INTENT (0x36) payload — offline user requesting a relayed balance fetch.
 *
 * Wire format:
 * - intentIdLength: 1 byte
 * - intentId: variable (UUID string)
 * - requesterPubKeyLength: 1 byte
 * - requesterPubKey: variable (Base58 wallet address to query)
 */
data class SolanaBalanceIntent(
    val intentId: String,
    val requesterPubKey: String
) {
    fun encode(): ByteArray {
        val intentIdBytes = intentId.toByteArray(Charsets.UTF_8)
        val requesterBytes = requesterPubKey.toByteArray(Charsets.UTF_8)
        val size = 1 + intentIdBytes.size + 1 + requesterBytes.size
        val buffer = ByteBuffer.allocate(size).apply { order(ByteOrder.BIG_ENDIAN) }

        buffer.put(intentIdBytes.size.coerceAtMost(255).toByte())
        buffer.put(intentIdBytes.take(255).toByteArray())

        buffer.put(requesterBytes.size.coerceAtMost(255).toByte())
        buffer.put(requesterBytes.take(255).toByteArray())

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    companion object {
        fun decode(data: ByteArray): SolanaBalanceIntent? {
            return try {
                if (data.size < 3) return null
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                val intentIdLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < intentIdLen) return null
                val intentIdBytes = ByteArray(intentIdLen)
                buffer.get(intentIdBytes)
                val intentId = String(intentIdBytes, Charsets.UTF_8)

                if (buffer.remaining() < 1) return null
                val requesterLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < requesterLen) return null
                val requesterBytes = ByteArray(requesterLen)
                buffer.get(requesterBytes)
                val requesterPubKey = String(requesterBytes, Charsets.UTF_8)

                SolanaBalanceIntent(intentId, requesterPubKey)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * BALANCE_RESPONSE (0x37) payload — relayed balance result from online peer.
 *
 * Wire format:
 * - intentIdLength: 1 byte
 * - intentId: variable (matches original intent)
 * - walletPubKeyLength: 1 byte
 * - walletPubKey: variable (queried wallet)
 * - lamports: 8 bytes (big-endian)
 * - slot: 8 bytes (big-endian, 0 if unavailable)
 * - fetchedAtMs: 8 bytes (big-endian, relay local timestamp)
 * - errorMessageLength: 2 bytes (big-endian)
 * - errorMessage: variable (UTF-8, empty if successful)
 */
data class SolanaBalanceResponse(
    val intentId: String,
    val walletPubKey: String,
    val lamports: Long,
    val slot: Long,
    val fetchedAtMs: Long,
    val errorMessage: String
) {
    fun encode(): ByteArray {
        val intentIdBytes = intentId.toByteArray(Charsets.UTF_8)
        val walletBytes = walletPubKey.toByteArray(Charsets.UTF_8)
        val errorBytes = errorMessage.toByteArray(Charsets.UTF_8)
        val size = 1 + intentIdBytes.size + 1 + walletBytes.size + 8 + 8 + 8 + 2 + errorBytes.size
        val buffer = ByteBuffer.allocate(size).apply { order(ByteOrder.BIG_ENDIAN) }

        buffer.put(intentIdBytes.size.coerceAtMost(255).toByte())
        buffer.put(intentIdBytes.take(255).toByteArray())

        buffer.put(walletBytes.size.coerceAtMost(255).toByte())
        buffer.put(walletBytes.take(255).toByteArray())

        buffer.putLong(lamports)
        buffer.putLong(slot)
        buffer.putLong(fetchedAtMs)

        buffer.putShort(errorBytes.size.coerceAtMost(65535).toShort())
        buffer.put(errorBytes.take(65535).toByteArray())

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    companion object {
        fun decode(data: ByteArray): SolanaBalanceResponse? {
            return try {
                if (data.size < 29) return null
                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                val intentIdLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < intentIdLen) return null
                val intentIdBytes = ByteArray(intentIdLen)
                buffer.get(intentIdBytes)
                val intentId = String(intentIdBytes, Charsets.UTF_8)

                if (buffer.remaining() < 1) return null
                val walletLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < walletLen) return null
                val walletBytes = ByteArray(walletLen)
                buffer.get(walletBytes)
                val walletPubKey = String(walletBytes, Charsets.UTF_8)

                if (buffer.remaining() < 24) return null
                val lamports = buffer.getLong()
                val slot = buffer.getLong()
                val fetchedAtMs = buffer.getLong()

                if (buffer.remaining() < 2) return null
                val errorLen = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < errorLen) return null
                val errorBytes = ByteArray(errorLen)
                buffer.get(errorBytes)
                val errorMessage = String(errorBytes, Charsets.UTF_8)

                SolanaBalanceResponse(intentId, walletPubKey, lamports, slot, fetchedAtMs, errorMessage)
            } catch (_: Exception) {
                null
            }
        }
    }
}
