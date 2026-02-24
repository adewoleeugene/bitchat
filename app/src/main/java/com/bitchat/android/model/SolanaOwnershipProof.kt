package com.bitchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Signed ownership proof embedded in ANNOUNCE packets.
 * The signature is produced by the Solana wallet private key.
 */
@Parcelize
data class SolanaOwnershipProof(
    val claimType: ClaimType,
    val targetAddress: String,
    val minRequired: Long,
    val observedBalance: Long,
    val validatedAtMs: Long,
    val expiresAtMs: Long,
    val signature: ByteArray
) : Parcelable {
    enum class ClaimType(val code: UByte) {
        SPL_TOKEN(0x01u),
        NFT_MINT(0x02u),
        NFT_COLLECTION(0x03u);

        companion object {
            fun fromCode(code: UByte): ClaimType? = values().find { it.code == code }
        }
    }

    fun encode(): ByteArray? {
        val target = targetAddress.toByteArray(Charsets.UTF_8)
        if (target.isEmpty() || target.size > 96) return null
        if (signature.isEmpty() || signature.size > 96) return null

        val out = ByteArray(1 + 1 + 1 + target.size + 8 + 8 + 8 + 8 + 1 + signature.size)
        var offset = 0
        out[offset++] = 1 // version
        out[offset++] = claimType.code.toByte()
        out[offset++] = target.size.toByte()
        System.arraycopy(target, 0, out, offset, target.size)
        offset += target.size
        putLong(out, offset, minRequired); offset += 8
        putLong(out, offset, observedBalance); offset += 8
        putLong(out, offset, validatedAtMs); offset += 8
        putLong(out, offset, expiresAtMs); offset += 8
        out[offset++] = signature.size.toByte()
        System.arraycopy(signature, 0, out, offset, signature.size)
        return out
    }

    companion object {
        fun decode(data: ByteArray): SolanaOwnershipProof? {
            if (data.size < 1 + 1 + 1 + 8 + 8 + 8 + 8 + 1) return null
            var offset = 0
            val version = data[offset++].toInt() and 0xFF
            if (version != 1) return null

            val claimType = ClaimType.fromCode(data[offset++].toUByte()) ?: return null
            val targetLen = data[offset++].toInt() and 0xFF
            if (targetLen <= 0 || offset + targetLen > data.size) return null
            val target = String(data, offset, targetLen, Charsets.UTF_8)
            offset += targetLen

            if (offset + 8 * 4 + 1 > data.size) return null
            val minRequired = readLong(data, offset); offset += 8
            val observed = readLong(data, offset); offset += 8
            val validatedAt = readLong(data, offset); offset += 8
            val expiresAt = readLong(data, offset); offset += 8
            val sigLen = data[offset++].toInt() and 0xFF
            if (sigLen <= 0 || offset + sigLen > data.size) return null
            val signature = data.copyOfRange(offset, offset + sigLen)

            return SolanaOwnershipProof(
                claimType = claimType,
                targetAddress = target,
                minRequired = minRequired,
                observedBalance = observed,
                validatedAtMs = validatedAt,
                expiresAtMs = expiresAt,
                signature = signature
            )
        }

        private fun putLong(out: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) {
                out[offset + i] = ((value shr (56 - i * 8)) and 0xFF).toByte()
            }
        }

        private fun readLong(input: ByteArray, offset: Int): Long {
            var v = 0L
            for (i in 0 until 8) {
                v = (v shl 8) or (input[offset + i].toLong() and 0xFFL)
            }
            return v
        }
    }
}
