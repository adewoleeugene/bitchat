package com.bitchat.android.lending

import com.google.gson.Gson

data class LendingMembershipMessage(
    val lendingId: String,
    val memberPeerId: String,
    val walletAddress: String,
    val stakeAmount: Long,
    val depositStatus: String,
    val joinStatus: String,
    val updatedAt: Long = System.currentTimeMillis()
)

object LendingMembershipMessageCodec {
    private const val PREFIX = "__bitchat_lending_membership__:"
    private val gson = Gson()

    fun encode(message: LendingMembershipMessage): String {
        return PREFIX + gson.toJson(message)
    }

    fun decode(content: String): LendingMembershipMessage? {
        if (!content.startsWith(PREFIX)) return null
        return runCatching {
            gson.fromJson(content.removePrefix(PREFIX), LendingMembershipMessage::class.java)
        }.getOrNull()
    }
}
