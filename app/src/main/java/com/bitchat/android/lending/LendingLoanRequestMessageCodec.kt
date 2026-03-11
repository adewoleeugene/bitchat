package com.bitchat.android.lending

import com.google.gson.Gson

data class LendingLoanRequestMessage(
    val requestId: String,
    val lendingId: String,
    val channelDisplayName: String,
    val principalAmount: Long,
    val assetSymbol: String = "",
    val assetDecimals: Int = 0,
    val durationDays: Int,
    val interestBps: Int,
    val purpose: String,
    val requestedAt: Long = System.currentTimeMillis(),
    val borrowerPeerId: String? = null,
    val borrowerWalletAddress: String? = null,
    val borrowerLabel: String? = null,
    val status: String = "PENDING",
    val parentRequestId: String? = null,
    val originLendingId: String? = null,
    val forwardedFromRequestId: String? = null,
    val fundingLendingId: String? = null,
    val requestKind: String = "ORIGIN"
)

object LendingLoanRequestMessageCodec {
    private const val PREFIX = "__bitchat_lending_loan_request__:"
    private val gson = Gson()

    fun encode(message: LendingLoanRequestMessage): String {
        return PREFIX + gson.toJson(message)
    }

    fun decode(content: String): LendingLoanRequestMessage? {
        if (!content.startsWith(PREFIX)) return null
        return runCatching {
            gson.fromJson(content.removePrefix(PREFIX), LendingLoanRequestMessage::class.java)
        }.getOrNull()
    }
}
