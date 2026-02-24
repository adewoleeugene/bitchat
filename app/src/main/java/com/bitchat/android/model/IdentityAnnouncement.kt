package com.bitchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.bitchat.android.util.*

/**
 * Identity announcement structure with TLV encoding
 * Compatible with iOS AnnouncementPacket TLV format
 */
@Parcelize
data class IdentityAnnouncement(
    val nickname: String,
    val noisePublicKey: ByteArray,    // Noise static public key (Curve25519.KeyAgreement)
    val signingPublicKey: ByteArray,  // Ed25519 public key for signing
    val solanaAddress: String? = null, // Optional Solana wallet address (Base58)
    val solanaLinkProofSignature: ByteArray? = null, // Optional Ed25519 proof signed by Solana wallet key
    val solanaOwnershipProofs: List<SolanaOwnershipProof> = emptyList() // Optional signed NFT/token holding claims
) : Parcelable {
    /**
     * TLV types matching iOS implementation
     */
    private enum class TLVType(val value: UByte) {
        NICKNAME(0x01u),
        NOISE_PUBLIC_KEY(0x02u),
        SIGNING_PUBLIC_KEY(0x03u),
        SOLANA_ADDRESS(0x05u),       // Solana wallet address (Base58 string)
        SOLANA_LINK_PROOF(0x06u),    // Signature proving wallet controls nickname+signing key link
        SOLANA_OWNERSHIP_PROOF(0x07u); // Signed ownership claim (repeated)

        companion object {
            fun fromValue(value: UByte): TLVType? {
                return values().find { it.value == value }
            }
        }
    }

    /**
     * Encode to TLV binary data matching iOS implementation
     */
    fun encode(): ByteArray? {
        val nicknameData = nickname.toByteArray(Charsets.UTF_8)
        
        // Check size limits
        if (nicknameData.size > 255 || noisePublicKey.size > 255 || signingPublicKey.size > 255) {
            return null
        }
        
        val result = mutableListOf<Byte>()
        
        // TLV for nickname
        result.add(TLVType.NICKNAME.value.toByte())
        result.add(nicknameData.size.toByte())
        result.addAll(nicknameData.toList())
        
        // TLV for noise public key
        result.add(TLVType.NOISE_PUBLIC_KEY.value.toByte())
        result.add(noisePublicKey.size.toByte())
        result.addAll(noisePublicKey.toList())
        
        // TLV for signing public key
        result.add(TLVType.SIGNING_PUBLIC_KEY.value.toByte())
        result.add(signingPublicKey.size.toByte())
        result.addAll(signingPublicKey.toList())

        // TLV for Solana address (optional)
        solanaAddress?.let { addr ->
            val addrData = addr.toByteArray(Charsets.UTF_8)
            if (addrData.size <= 255) {
                result.add(TLVType.SOLANA_ADDRESS.value.toByte())
                result.add(addrData.size.toByte())
                result.addAll(addrData.toList())
            }
        }

        // TLV for Solana link proof signature (optional)
        solanaLinkProofSignature?.let { proof ->
            if (proof.isNotEmpty() && proof.size <= 255) {
                result.add(TLVType.SOLANA_LINK_PROOF.value.toByte())
                result.add(proof.size.toByte())
                result.addAll(proof.toList())
            }
        }

        // TLV for ownership proofs (optional, repeated).
        // Keep ANNOUNCE bounded to avoid proof-flooding payloads.
        solanaOwnershipProofs.take(MAX_OWNERSHIP_PROOFS).forEach { proof ->
            val proofData = proof.encode() ?: return@forEach
            if (proofData.isNotEmpty() && proofData.size <= 255) {
                result.add(TLVType.SOLANA_OWNERSHIP_PROOF.value.toByte())
                result.add(proofData.size.toByte())
                result.addAll(proofData.toList())
            }
        }

        return result.toByteArray()
    }
    
    companion object {
        private const val MAX_OWNERSHIP_PROOFS = 12

        /**
         * Decode from TLV binary data matching iOS implementation
         */
        fun decode(data: ByteArray): IdentityAnnouncement? {
            // Create defensive copy
            val dataCopy = data.copyOf()
            
            var offset = 0
            var nickname: String? = null
            var noisePublicKey: ByteArray? = null
            var signingPublicKey: ByteArray? = null
            var solanaAddress: String? = null
            var solanaLinkProofSignature: ByteArray? = null
            val solanaOwnershipProofs = mutableListOf<SolanaOwnershipProof>()

            while (offset + 2 <= dataCopy.size) {
                // Read TLV type
                val typeValue = dataCopy[offset].toUByte()
                val type = TLVType.fromValue(typeValue)
                offset += 1

                // Read TLV length
                val length = dataCopy[offset].toUByte().toInt()
                offset += 1

                // Check bounds
                if (offset + length > dataCopy.size) return null

                // Read TLV value
                val value = dataCopy.sliceArray(offset until offset + length)
                offset += length

                // Process known TLV types, skip unknown ones for forward compatibility
                when (type) {
                    TLVType.NICKNAME -> {
                        nickname = String(value, Charsets.UTF_8)
                    }
                    TLVType.NOISE_PUBLIC_KEY -> {
                        noisePublicKey = value
                    }
                    TLVType.SIGNING_PUBLIC_KEY -> {
                        signingPublicKey = value
                    }
                    TLVType.SOLANA_ADDRESS -> {
                        solanaAddress = String(value, Charsets.UTF_8)
                    }
                    TLVType.SOLANA_LINK_PROOF -> {
                        solanaLinkProofSignature = value
                    }
                    TLVType.SOLANA_OWNERSHIP_PROOF -> {
                        SolanaOwnershipProof.decode(value)?.let { solanaOwnershipProofs.add(it) }
                    }
                    null -> {
                        // Unknown TLV; skip (tolerant decoder for forward compatibility)
                        continue
                    }
                }
            }

            // All three core fields are required; solanaAddress is optional
            return if (nickname != null && noisePublicKey != null && signingPublicKey != null) {
                IdentityAnnouncement(
                    nickname = nickname,
                    noisePublicKey = noisePublicKey,
                    signingPublicKey = signingPublicKey,
                    solanaAddress = solanaAddress,
                    solanaLinkProofSignature = solanaLinkProofSignature,
                    solanaOwnershipProofs = solanaOwnershipProofs.toList()
                )
            } else {
                null
            }
        }
    }
    
    // Override equals and hashCode since we use ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as IdentityAnnouncement
        
        if (nickname != other.nickname) return false
        if (!noisePublicKey.contentEquals(other.noisePublicKey)) return false
        if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false
        if (solanaAddress != other.solanaAddress) return false
        if (solanaLinkProofSignature != null) {
            if (other.solanaLinkProofSignature == null) return false
            if (!solanaLinkProofSignature.contentEquals(other.solanaLinkProofSignature)) return false
        } else if (other.solanaLinkProofSignature != null) return false
        if (solanaOwnershipProofs != other.solanaOwnershipProofs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = nickname.hashCode()
        result = 31 * result + noisePublicKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        result = 31 * result + (solanaAddress?.hashCode() ?: 0)
        result = 31 * result + (solanaLinkProofSignature?.contentHashCode() ?: 0)
        result = 31 * result + solanaOwnershipProofs.hashCode()
        return result
    }

    override fun toString(): String {
        val solSuffix = solanaAddress?.let { ", solana=${it.take(8)}..." } ?: ""
        return "IdentityAnnouncement(nickname='$nickname', noisePublicKey=${noisePublicKey.joinToString("") { "%02x".format(it) }.take(16)}..., signingPublicKey=${signingPublicKey.joinToString("") { "%02x".format(it) }.take(16)}...$solSuffix)"
    }
}
