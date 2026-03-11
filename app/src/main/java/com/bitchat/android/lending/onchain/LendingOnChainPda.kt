package com.bitchat.android.lending.onchain

import com.bitchat.android.solana.SolanaKeyDerivation
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest

object LendingOnChainPda {
    private val ed25519Spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
    private val marker = "ProgramDerivedAddress".toByteArray(Charsets.UTF_8)

    fun findChannelPda(programId: String, lendingId: String): DerivedAddress {
        return findProgramAddress(
            programId = programId,
            seeds = listOf("lending-channel".toByteArray(Charsets.UTF_8), lendingId.toByteArray(Charsets.UTF_8))
        )
    }

    fun findLoanRequestPda(programId: String, channelPda: String, requestId: String): DerivedAddress {
        return findProgramAddress(
            programId = programId,
            seeds = listOf(
                "loan-request".toByteArray(Charsets.UTF_8),
                decodeBase58(channelPda),
                requestId.toByteArray(Charsets.UTF_8)
            )
        )
    }

    fun findVoteRecordPda(programId: String, loanRequestPda: String, voterWallet: String): DerivedAddress {
        return findProgramAddress(
            programId = programId,
            seeds = listOf(
                "vote-record".toByteArray(Charsets.UTF_8),
                decodeBase58(loanRequestPda),
                decodeBase58(voterWallet)
            )
        )
    }

    fun findProgramAddress(programId: String, seeds: List<ByteArray>): DerivedAddress {
        val programBytes = decodeBase58(programId)
        for (bump in 255 downTo 0) {
            val hash = sha256(
                buildList {
                    addAll(seeds)
                    add(byteArrayOf(bump.toByte()))
                    add(programBytes)
                    add(marker)
                }.fold(ByteArray(0)) { acc, part -> acc + part }
            )
            if (!isEd25519Point(hash)) {
                return DerivedAddress(SolanaKeyDerivation.encodeBase58(hash), bump)
            }
        }
        throw IllegalStateException("unable_to_find_pda")
    }

    private fun sha256(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    private fun isEd25519Point(value: ByteArray): Boolean {
        return try {
            EdDSAPublicKeySpec(value, ed25519Spec)
            true
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
        val raw = ByteArray(leadingZeros) + stripped
        return if (raw.size < 32) ByteArray(32 - raw.size) + raw else raw
    }
}

data class DerivedAddress(
    val address: String,
    val bump: Int
)
