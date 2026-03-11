package com.bitchat.android.lending.onchain

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object LendingOnChainCodec {
    private val discriminatorCache = mutableMapOf<String, ByteArray>()

    fun initializeChannelData(params: InitializeLendingChannelOnChainParams): ByteArray {
        val lendingIdBytes = params.lendingId.trim().toByteArray(Charsets.UTF_8)
        val treasuryAuthority = decodePubkeyOrZero(params.creatorWallet)
        val stakeMint = decodePubkeyOrZero(params.stakeTokenMint)
        val payload = ByteBuffer.allocate(
            2 + lendingIdBytes.size + 1 + 1 + 2 + 1 + 8 + 1 + 8 + 32 + 32
        )
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(lendingIdBytes.size.toShort())
            .put(lendingIdBytes)
            .put(params.quorumThresholdPercent.toByte())
            .put(params.approvalThresholdPercent.toByte())
            .putShort(params.memberCount.toShort())
            .put(params.lifecycleState.toByte())
            .putLong(params.requiredStakeAmount)
            .put(params.stakeTokenDecimals.toByte())
            .putLong(params.createdAt)
            .put(treasuryAuthority)
            .put(stakeMint)
            .array()
        return discriminator("initialize_channel") + payload
    }

    fun createLoanRequestData(params: CreateLoanRequestOnChainParams): ByteArray {
        val requestIdBytes = params.requestId.trim().toByteArray(Charsets.UTF_8)
        val purposeHash = sha256(params.purpose.trim().toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(2 + requestIdBytes.size + 1 + 2 + 2 + 8 + 8 + 8 + 32)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(requestIdBytes.size.toShort())
            .put(requestIdBytes)
            .put(0)
            .putShort(params.durationDays.toShort())
            .putShort(params.interestBps.toShort())
            .putLong(params.principalAmount)
            .putLong(params.requestedAt)
            .putLong(params.dueAt)
            .put(purposeHash)
            .array()
        return discriminator("create_loan_request") + payload
    }

    fun castVoteData(params: CastLoanVoteOnChainParams): ByteArray {
        val voteChoice = if (params.voteChoice.equals("yes", ignoreCase = true)) 1.toByte() else 2.toByte()
        val payload = ByteBuffer.allocate(1 + 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(voteChoice)
            .putLong(params.votedAt)
            .array()
        return discriminator("cast_vote") + payload
    }

    fun finalizeLoanRequestData(params: FinalizeLoanRequestOnChainParams): ByteArray {
        val payload = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(params.finalizedAt)
            .array()
        return discriminator("finalize_loan_request") + payload
    }

    fun recordRepaymentData(params: RecordLoanRepaymentOnChainParams): ByteArray {
        val payload = ByteBuffer.allocate(8 + 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(params.amount)
            .putLong(params.paidAt)
            .array()
        return discriminator("record_repayment") + payload
    }

    fun decodeLoanRequestState(
        channelPda: String,
        loanRequestPda: String,
        dataBase64: String,
        txSignature: String? = null,
        slot: Long? = null
    ): OnChainLoanRequestState {
        val bytes = Base64.decode(dataBase64, Base64.DEFAULT)
        val body = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        body.get()
        body.get()
        val status = body.get().toInt() and 0xFF
        body.get()
        val durationDays = body.short.toInt() and 0xFFFF
        val interestBps = body.short.toInt() and 0xFFFF
        val yesVotes = body.short.toInt() and 0xFFFF
        val noVotes = body.short.toInt() and 0xFFFF
        val requestedAt = body.long
        val dueAt = body.long
        val approvedAtRaw = body.long
        val disbursedAtRaw = body.long
        val repaidAtRaw = body.long
        val principalAmount = body.long
        val totalRepaidAmount = body.long
        val purposeHash = ByteArray(32).also(body::get)
        body.position(body.position() + 32)
        val borrower = ByteArray(32).also(body::get)
        return OnChainLoanRequestState(
            channelPda = channelPda,
            loanRequestPda = loanRequestPda,
            borrowerWallet = SolanaBase58.encode(borrower),
            principalAmount = principalAmount,
            durationDays = durationDays,
            interestBps = interestBps,
            purposeHashHex = purposeHash.joinToString("") { "%02x".format(it) },
            yesVotes = yesVotes,
            noVotes = noVotes,
            requestedAt = requestedAt,
            dueAt = dueAt,
            approvedAt = approvedAtRaw.takeIf { it > 0 },
            disbursedAt = disbursedAtRaw.takeIf { it > 0 },
            repaidAt = repaidAtRaw.takeIf { it > 0 },
            totalRepaidAmount = totalRepaidAmount,
            chainStatus = mapChainLoanStatus(status),
            txSignature = txSignature,
            slot = slot
        )
    }

    private fun discriminator(name: String): ByteArray {
        return discriminatorCache.getOrPut(name) {
            sha256("global:$name".toByteArray(Charsets.UTF_8)).copyOfRange(0, 8)
        }
    }

    private fun sha256(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    private fun decodePubkeyOrZero(input: String): ByteArray {
        return runCatching { decodeBase58(input) }.getOrElse { ByteArray(32) }
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
        return if (raw.size < 32) ByteArray(32 - raw.size) + raw else raw.takeLast(32).toByteArray()
    }
}

private object SolanaBase58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        val encoded = StringBuilder()
        var value = java.math.BigInteger(1, input)
        val base = java.math.BigInteger.valueOf(58)
        while (value > java.math.BigInteger.ZERO) {
            val divRem = value.divideAndRemainder(base)
            value = divRem[0]
            encoded.append(ALPHABET[divRem[1].toInt()])
        }
        repeat(zeros) { encoded.append('1') }
        return encoded.reverse().toString()
    }
}
