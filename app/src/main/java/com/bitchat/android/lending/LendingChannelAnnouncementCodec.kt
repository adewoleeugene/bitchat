package com.bitchat.android.lending

import com.google.gson.Gson

data class LendingChannelAnnouncement(
    val lendingId: String,
    val channelKey: String,
    val displayName: String,
    val creatorPeerId: String,
    val creatorWalletAddress: String,
    val requiredStakeAmount: Long,
    val stakeTokenMint: String,
    val stakeTokenSymbol: String,
    val stakeTokenDecimals: Int,
    val treasuryMultisigAddress: String? = null,
    val treasuryOwnerAddress: String? = null,
    val treasuryTokenAccountAddress: String? = null,
    val custodyProvider: String? = null,
    val custodyState: String? = null,
    val totalStakedAmount: Long? = null,
    val confirmedMemberPeerId: String? = null,
    val confirmedMemberWalletAddress: String? = null,
    val confirmedMemberStakeAmount: Long? = null
)

object LendingChannelAnnouncementCodec {
    private const val PREFIX = "__bitchat_lending_channel__:"
    private val gson = Gson()

    fun encode(announcement: LendingChannelAnnouncement): String {
        return PREFIX + gson.toJson(announcement)
    }

    fun decode(content: String): LendingChannelAnnouncement? {
        if (!content.startsWith(PREFIX)) return null
        return runCatching {
            gson.fromJson(content.removePrefix(PREFIX), LendingChannelAnnouncement::class.java)
        }.getOrNull()
    }
}
