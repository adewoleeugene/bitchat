package com.bitchat.android.lending.onchain

import android.util.Base64
import com.bitchat.android.solana.SolanaWalletService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LendingOnChainTransactionBuilder @Inject constructor(
    private val walletService: SolanaWalletService
) {
    fun signLegacyTransaction(
        signerPublicKey: String,
        recentBlockhash: String,
        instructions: List<ProgramInstruction>
    ): String? {
        val signerPubKey = decodeBase58(signerPublicKey)
        val recentBlockhashBytes = decodeBase58(recentBlockhash)
        val accountKeys = linkedSetOf<ByteArray>()
        accountKeys += signerPubKey
        instructions.flatMap { it.accounts }.forEach { accountKeys += decodeBase58(it.publicKey) }
        instructions.map { decodeBase58(it.programId) }.forEach { accountKeys += it }
        val keyList = accountKeys.toList()

        val writableUnsigned = instructions
            .flatMap { it.accounts }
            .filter { !it.isSigner && it.isWritable }
            .map { it.publicKey }
            .toSet()
        val readonlyUnsigned = keyList.size - 1 - writableUnsigned.size
        val header = byteArrayOf(1, 0, readonlyUnsigned.coerceAtLeast(0).toByte())

        val encodedInstructions = instructions.map { instruction ->
            val programIndex = keyList.indexOfFirst { it.contentEquals(decodeBase58(instruction.programId)) }
            val accountIndices = instruction.accounts.map { meta ->
                keyList.indexOfFirst { it.contentEquals(decodeBase58(meta.publicKey)) }.toByte()
            }.toByteArray()
            byteArrayOf(programIndex.toByte()) +
                compactU16(accountIndices.size) +
                accountIndices +
                compactU16(instruction.data.size) +
                instruction.data
        }

        val message = header +
            compactU16(keyList.size) +
            keyList.fold(ByteArray(0)) { acc, key -> acc + key } +
            recentBlockhashBytes +
            compactU16(encodedInstructions.size) +
            encodedInstructions.fold(ByteArray(0)) { acc, instruction -> acc + instruction }

        val signature = walletService.sign(message) ?: return null
        val transaction = byteArrayOf(1) + signature + message
        return Base64.encodeToString(transaction, Base64.NO_WRAP)
    }

    private fun compactU16(value: Int): ByteArray {
        if (value < 0x80) return byteArrayOf(value.toByte())
        if (value < 0x4000) {
            return byteArrayOf(
                ((value and 0x7F) or 0x80).toByte(),
                ((value shr 7) and 0x7F).toByte()
            )
        }
        return byteArrayOf(
            ((value and 0x7F) or 0x80).toByte(),
            (((value shr 7) and 0x7F) or 0x80).toByte(),
            ((value shr 14) and 0x03).toByte()
        )
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

data class AccountMeta(
    val publicKey: String,
    val isSigner: Boolean,
    val isWritable: Boolean
)

data class ProgramInstruction(
    val programId: String,
    val accounts: List<AccountMeta>,
    val data: ByteArray
)
