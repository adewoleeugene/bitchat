package com.bitchat.android.lending

class LendingCredibilityScorer {
    companion object {
        const val THRESHOLD = 60
        const val MIN_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        private const val USAGE_AGE_MAX_DAYS = 30
        private const val PARTICIPATION_TARGET = 120
        private const val RECENT_ACTIVITY_TARGET = 40
        private const val WALLET_AGE_TARGET_DAYS = 30
        private const val WALLET_BALANCE_TARGET_LAMPORTS = 1_000_000_000L
    }

    data class Input(
        val firstSeenAt: Long,
        val now: Long,
        val walletLinked: Boolean,
        val walletCreatedAt: Long = 0L,
        val walletBalanceLamports: Long = 0L,
        val stakeBalanceSatisfied: Boolean,
        val totalActions: Int,
        val recentActions: Int
    )

    data class Output(
        val usageAgePoints: Int,
        val participationPoints: Int,
        val recentActivityPoints: Int,
        val walletStrengthPoints: Int,
        val score: Int,
        val passedThreshold: Boolean,
        val hardGateFailures: List<String>
    ) {
        val passedHardGates: Boolean
            get() = hardGateFailures.isEmpty()
    }

    fun score(input: Input): Output {
        val hardGateFailures = mutableListOf<String>()
        val accountAgeMs = if (input.firstSeenAt > 0L) input.now - input.firstSeenAt else 0L

        if (!input.walletLinked) hardGateFailures += "wallet_link_required"
        if (!input.stakeBalanceSatisfied) hardGateFailures += "stake_balance_required"

        val usageAgePoints = computeUsageAgePoints(accountAgeMs)
        val participationPoints = computeParticipationPoints(input.totalActions)
        val recentActivityPoints = computeRecentActivityPoints(input.recentActions)
        val walletStrengthPoints = computeWalletStrengthPoints(
            walletAgeMs = if (input.walletLinked) input.now - input.walletCreatedAt else 0L,
            walletBalanceLamports = input.walletBalanceLamports
        )
        val score = usageAgePoints + participationPoints + recentActivityPoints + walletStrengthPoints

        return Output(
            usageAgePoints = usageAgePoints,
            participationPoints = participationPoints,
            recentActivityPoints = recentActivityPoints,
            walletStrengthPoints = walletStrengthPoints,
            score = score,
            passedThreshold = score >= THRESHOLD,
            hardGateFailures = hardGateFailures
        )
    }

    private fun computeUsageAgePoints(accountAgeMs: Long): Int {
        val days = (accountAgeMs / (24L * 60L * 60L * 1000L)).toInt().coerceAtLeast(0)
        return scaledPoints(days, USAGE_AGE_MAX_DAYS, 25)
    }

    private fun computeParticipationPoints(totalActions: Int): Int {
        return scaledPoints(totalActions, PARTICIPATION_TARGET, 30)
    }

    private fun computeRecentActivityPoints(recentActions: Int): Int {
        return scaledPoints(recentActions, RECENT_ACTIVITY_TARGET, 20)
    }

    private fun computeWalletStrengthPoints(walletAgeMs: Long, walletBalanceLamports: Long): Int {
        val walletAgeDays = (walletAgeMs / (24L * 60L * 60L * 1000L)).toInt().coerceAtLeast(0)
        val agePoints = scaledPoints(walletAgeDays, WALLET_AGE_TARGET_DAYS, 10)
        val balancePoints = scaledPoints(walletBalanceLamports.toIntSafely(), WALLET_BALANCE_TARGET_LAMPORTS.toIntSafely(), 15)
        return agePoints + balancePoints
    }

    private fun scaledPoints(value: Int, target: Int, maxPoints: Int): Int {
        if (target <= 0) return maxPoints
        val ratio = value.coerceAtMost(target).toDouble() / target.toDouble()
        return kotlin.math.round(ratio * maxPoints).toInt()
    }

    private fun Long.toIntSafely(): Int = if (this > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else toInt()
}
