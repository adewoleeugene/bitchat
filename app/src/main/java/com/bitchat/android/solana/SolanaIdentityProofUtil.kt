package com.bitchat.android.solana

import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec

/**
 * Utilities for wallet <-> username identity link proof.
 *
 * Proof format is deterministic and offline-verifiable:
 * message = "bitchat-wallet-link|v1|<nickname>|<solanaAddress>|<signingPubKeyHex>"
 * signature = Ed25519 signature by the Solana wallet private key.
 */
object SolanaIdentityProofUtil {

    private const val PROOF_PREFIX = "bitchat-wallet-link"
    private const val PROOF_VERSION = "v1"

    fun buildLinkMessage(
        nickname: String,
        solanaAddress: String,
        signingPublicKey: ByteArray
    ): ByteArray {
        val payload = listOf(
            PROOF_PREFIX,
            PROOF_VERSION,
            nickname,
            solanaAddress,
            signingPublicKey.joinToString("") { "%02x".format(it) }
        ).joinToString("|")
        return payload.toByteArray(Charsets.UTF_8)
    }

    fun verifyLinkProof(
        nickname: String,
        solanaAddress: String,
        signingPublicKey: ByteArray,
        signature: ByteArray
    ): Boolean {
        return try {
            val pubKeyBytes = decodeBase58(solanaAddress)
            if (pubKeyBytes.size != 32) return false

            if (java.security.Security.getProvider("EdDSA") == null) {
                java.security.Security.addProvider(net.i2p.crypto.eddsa.EdDSASecurityProvider())
            }

            val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
            val pubKey = EdDSAPublicKey(EdDSAPublicKeySpec(pubKeyBytes, spec))
            val message = buildLinkMessage(nickname, solanaAddress, signingPublicKey)

            val verifier = java.security.Signature.getInstance("NONEwithEdDSA", "EdDSA")
            verifier.initVerify(pubKey)
            verifier.update(message)
            verifier.verify(signature)
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

