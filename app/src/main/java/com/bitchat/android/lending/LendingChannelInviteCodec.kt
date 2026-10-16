package com.bitchat.android.lending

import com.google.gson.Gson
import java.util.Base64

data class LendingChannelInvite(
    val lendingId: String,
    val displayName: String,
    val creatorPeerId: String,
    val creatorWalletAddress: String,
    val requiredStakeAmount: Long,
    val minimumVoteCount: Int,
    val maxLoanDurationDays: Int = 14,
    val stakeTokenMint: String,
    val stakeTokenSymbol: String = "",
    val stakeTokenDecimals: Int = 6,
    val votingWindowHours: Int = 24,
    val defaultGracePeriodDays: Int = 7,
    val creatorMembershipConfirmed: Boolean = true
)

object LendingChannelInviteCodec {
    private const val PREFIX = "bitchat-lending-invite:"
    private val gson = Gson()

    fun encode(invite: LendingChannelInvite): String {
        val payload = gson.toJson(invite).toByteArray(Charsets.UTF_8)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    fun decode(raw: String): LendingChannelInvite? {
        if (!raw.startsWith(PREFIX)) return null
        return runCatching {
            val json = Base64.getUrlDecoder().decode(raw.removePrefix(PREFIX)).toString(Charsets.UTF_8)
            gson.fromJson(json, LendingChannelInvite::class.java)
        }.getOrNull()
    }
}
