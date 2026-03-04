package com.bitchat.android.solana

import com.bitchat.android.model.SolanaOwnershipProof
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec

/**
 * Build/verify ownership proof signatures included in ANNOUNCE packets.
 */
object SolanaOwnershipProofUtil {
    private const val PROOF_PREFIX = "bitchat-ownership-proof"
    private const val PROOF_VERSION = "v1"
    private const val MAX_PROOF_VALIDITY_MS = 24 * 60 * 60 * 1000L
    private const val MAX_CLOCK_SKEW_MS = 2 * 60 * 1000L
    private val BASE58_REGEX = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    fun buildProofMessage(
        nickname: String,
        solanaAddress: String,
        signingPublicKey: ByteArray,
        proof: SolanaOwnershipProof
    ): ByteArray {
        val payload = listOf(
            PROOF_PREFIX,
            PROOF_VERSION,
            nickname,
            solanaAddress,
            signingPublicKey.joinToString("") { "%02x".format(it) },
            proof.claimType.name,
            proof.targetAddress,
            proof.minRequired.toString(),
            proof.observedBalance.toString(),
            proof.validatedAtMs.toString(),
            proof.expiresAtMs.toString()
        ).joinToString("|")
        return payload.toByteArray(Charsets.UTF_8)
    }

    fun verifyProof(
        nickname: String,
        solanaAddress: String,
        signingPublicKey: ByteArray,
        proof: SolanaOwnershipProof,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (proof.signature.size != 64) return false
        if (proof.expiresAtMs <= nowMs) return false
        if (proof.validatedAtMs <= 0 || proof.validatedAtMs > proof.expiresAtMs) return false
        if (proof.observedBalance < 0 || proof.minRequired < 0) return false
        if (proof.observedBalance < proof.minRequired) return false
        if (proof.validatedAtMs > nowMs + MAX_CLOCK_SKEW_MS) return false
        if ((proof.expiresAtMs - proof.validatedAtMs) > MAX_PROOF_VALIDITY_MS) return false
        if (!BASE58_REGEX.matches(proof.targetAddress)) return false

        when (proof.claimType) {
            SolanaOwnershipProof.ClaimType.NFT_MINT,
            SolanaOwnershipProof.ClaimType.NFT_COLLECTION -> {
                if (proof.minRequired <= 0L || proof.observedBalance <= 0L) return false
            }
            SolanaOwnershipProof.ClaimType.SPL_TOKEN -> {
                // Generic fungible-token claim: minRequired/observedBalance already validated above.
            }
        }

        return try {
            val pubKeyBytes = decodeBase58(solanaAddress)
            if (pubKeyBytes.size != 32) return false

            if (java.security.Security.getProvider("EdDSA") == null) {
                java.security.Security.addProvider(net.i2p.crypto.eddsa.EdDSASecurityProvider())
            }

            val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
            val pubKey = EdDSAPublicKey(EdDSAPublicKeySpec(pubKeyBytes, spec))
            val message = buildProofMessage(nickname, solanaAddress, signingPublicKey, proof)

            val verifier = java.security.Signature.getInstance("NONEwithEdDSA", "EdDSA")
            verifier.initVerify(pubKey)
            verifier.update(message)
            verifier.verify(proof.signature)
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeBase58(input: String): ByteArray {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)

        for (c in input) {
            val digit = alphabet.indexOf(c)
            if (digit == -1) throw IllegalArgumentException("Invalid Base58 character: $c")
            num = num.multiply(base).add(java.math.BigInteger.valueOf(digit.toLong()))
        }

        val leadingZeros = input.takeWhile { it == '1' }.length
        val bytes = num.toByteArray()
        val stripped = if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.drop(1).toByteArray() else bytes
        return ByteArray(leadingZeros) + stripped
    }
}
