package com.bitchat.android.solana

import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest

object SolanaTokenAccountUtils {
    const val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val ASSOCIATED_TOKEN_PROGRAM_ID = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    const val SYSTEM_PROGRAM_ID = "11111111111111111111111111111111"

    private val ed25519Spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    private val base58Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun findAssociatedTokenAddress(ownerPublicKey: String, mintAddress: String): String {
        val owner = decodeBase58(ownerPublicKey)
        val mint = decodeBase58(mintAddress)
        val tokenProgram = decodeBase58(TOKEN_PROGRAM_ID)
        val associatedProgram = decodeBase58(ASSOCIATED_TOKEN_PROGRAM_ID)
        for (bump in 255 downTo 0) {
            val candidate = createProgramAddress(
                seeds = listOf(owner, tokenProgram, mint, byteArrayOf(bump.toByte())),
                programId = associatedProgram
            )
            if (!isEd25519CurvePoint(candidate)) {
                return SolanaKeyDerivation.encodeBase58(candidate)
            }
        }
        throw IllegalStateException("Unable to derive associated token account")
    }

    private fun createProgramAddress(seeds: List<ByteArray>, programId: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        seeds.forEach { sha256.update(it) }
        sha256.update(programId)
        sha256.update("ProgramDerivedAddress".toByteArray(Charsets.UTF_8))
        return sha256.digest()
    }

    private fun isEd25519CurvePoint(bytes: ByteArray): Boolean {
        return try {
            EdDSAPublicKeySpec(bytes, ed25519Spec)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun decodeBase58(input: String): ByteArray {
        var num = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)
        for (c in input) {
            val digit = base58Alphabet.indexOf(c)
            if (digit == -1) throw IllegalArgumentException("Invalid Base58 character: $c")
            num = num.multiply(base).add(java.math.BigInteger.valueOf(digit.toLong()))
        }
        val leadingZeros = input.takeWhile { it == '1' }.length
        val bytes = num.toByteArray()
        val stripped = if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.drop(1).toByteArray() else bytes
        return ByteArray(leadingZeros) + stripped
    }
}
