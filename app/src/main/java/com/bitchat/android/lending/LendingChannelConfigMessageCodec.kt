package com.bitchat.android.lending

import com.google.gson.Gson

data class LendingChannelConfigMessage(
    val lendingId: String,
    val channelKey: String,
    val displayName: String,
    val creatorPeerId: String,
    val creatorWalletAddress: String,
    val requiredStakeAmount: Long,
    val minimumVoteCount: Int,
    val maxLoanDurationDays: Int,
    val stakeTokenMint: String,
    val stakeTokenSymbol: String = "",
    val stakeTokenDecimals: Int = 6,
    val votingWindowHours: Int = 24,
    val defaultGracePeriodDays: Int = 7,
    val lifecycleState: String = "ACTIVE"
)

object LendingChannelConfigMessageCodec {
    private const val PREFIX = "__bitchat_lending_channel_config__:"
    private val gson = Gson()

    fun encode(message: LendingChannelConfigMessage): String {
        return PREFIX + gson.toJson(message)
    }

    fun decode(content: String): LendingChannelConfigMessage? {
        if (!content.startsWith(PREFIX)) return null
        return runCatching {
            gson.fromJson(content.removePrefix(PREFIX), LendingChannelConfigMessage::class.java)
        }.getOrNull()
    }
}

data class LendingChannelConfigRequestMessage(
    val displayName: String,
    val channelKey: String
)

object LendingChannelConfigRequestMessageCodec {
    private const val PREFIX = "__bitchat_lending_channel_request__:"
    private val gson = Gson()

    fun encode(message: LendingChannelConfigRequestMessage): String {
        return PREFIX + gson.toJson(message)
    }

    fun decode(content: String): LendingChannelConfigRequestMessage? {
        if (!content.startsWith(PREFIX)) return null
        return runCatching {
            gson.fromJson(content.removePrefix(PREFIX), LendingChannelConfigRequestMessage::class.java)
        }.getOrNull()
    }
}
