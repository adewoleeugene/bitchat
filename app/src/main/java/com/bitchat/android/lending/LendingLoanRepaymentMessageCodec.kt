package com.bitchat.android.lending

import com.google.gson.Gson

data class LendingLoanRepaymentMessage(
    val repaymentId: String,
    val requestId: String,
    val lendingId: String,
    val payerPeerId: String,
    val payerLabel: String? = null,
    val amount: Long,
    val txSignature: String? = null,
    val txStatus: String,
    val totalRepaidAmount: Long,
    val remainingBalance: Long,
    val requestStatus: String,
    val paidAt: Long
)

object LendingLoanRepaymentMessageCodec {
    private const val PREFIX = "__bitchat_lending_loan_repayment__:"
    private val gson = Gson()

    fun encode(message: LendingLoanRepaymentMessage): String {
        return PREFIX + gson.toJson(message)
    }

    fun decode(content: String): LendingLoanRepaymentMessage? {
        if (!content.startsWith(PREFIX)) return null
        return runCatching {
            gson.fromJson(content.removePrefix(PREFIX), LendingLoanRepaymentMessage::class.java)
        }.getOrNull()
    }
}
