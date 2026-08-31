package com.bitchat.android.lending

import com.google.gson.Gson

data class LendingLoanVoteMessage(
    val requestId: String,
    val lendingId: String,
    val voterPeerId: String,
    val voterLabel: String? = null,
    val voteChoice: String,
    val yesVotes: Int,
    val noVotes: Int,
    val requestStatus: String,
    val voterLockedAmount: Long = 0,
    val fullyBacked: Boolean = false,
    val approvedAt: Long? = null,
    val disbursedAt: Long? = null,
    val squadsMultisigAddress: String? = null,
    val squadsVaultAddress: String? = null,
    val squadsProposalAddress: String? = null,
    val squadsTransactionIndex: Long? = null
)

object LendingLoanVoteMessageCodec {
    private const val PREFIX = "__bitchat_lending_loan_vote__:"
    private val gson = Gson()

    fun encode(message: LendingLoanVoteMessage): String {
        return PREFIX + gson.toJson(message)
    }

    fun decode(content: String): LendingLoanVoteMessage? {
        if (!content.startsWith(PREFIX)) return null
        return runCatching {
            gson.fromJson(content.removePrefix(PREFIX), LendingLoanVoteMessage::class.java)
        }.getOrNull()
    }
}
