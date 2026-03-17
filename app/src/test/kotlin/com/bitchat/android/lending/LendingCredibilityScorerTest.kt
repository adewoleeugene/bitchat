package com.bitchat.android.lending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LendingCredibilityScorerTest {

    private val scorer = LendingCredibilityScorer()
    private val now = 1_800_000_000_000L

    @Test
    fun score_failsWalletAndStakeHardGatesWhileLowSignalsLowerThresholdScore() {
        val result = scorer.score(
            LendingCredibilityScorer.Input(
                firstSeenAt = now - 2L * 24L * 60L * 60L * 1000L,
                now = now,
                walletLinked = false,
                stakeBalanceSatisfied = false,
                totalActions = 5,
                recentActions = 2
            )
        )

        assertFalse(result.passedHardGates)
        assertTrue("wallet_link_required" in result.hardGateFailures)
        assertTrue("stake_balance_required" in result.hardGateFailures)
        assertFalse(result.passedThreshold)
        assertEquals(2, result.hardGateFailures.size)
    }

    @Test
    fun score_passesHardGatesAndThresholdForEstablishedParticipant() {
        val result = scorer.score(
            LendingCredibilityScorer.Input(
                firstSeenAt = now - 45L * 24L * 60L * 60L * 1000L,
                now = now,
                walletLinked = true,
                walletCreatedAt = now - 40L * 24L * 60L * 60L * 1000L,
                walletBalanceLamports = 2_500_000_000L,
                stakeBalanceSatisfied = true,
                totalActions = 140,
                recentActions = 48
            )
        )

        assertTrue(result.passedHardGates)
        assertTrue(result.passedThreshold)
        assertEquals(25, result.usageAgePoints)
        assertEquals(30, result.participationPoints)
        assertEquals(20, result.recentActivityPoints)
        assertEquals(25, result.walletStrengthPoints)
        assertEquals(100, result.score)
    }

    @Test
    fun score_isDeterministicForBorderlineThresholdCase() {
        val result = scorer.score(
            LendingCredibilityScorer.Input(
                firstSeenAt = now - 30L * 24L * 60L * 60L * 1000L,
                now = now,
                walletLinked = true,
                walletCreatedAt = now - 30L * 24L * 60L * 60L * 1000L,
                walletBalanceLamports = 400_000_000L,
                stakeBalanceSatisfied = true,
                totalActions = 80,
                recentActions = 20
            )
        )

        assertTrue(result.passedHardGates)
        assertEquals(25, result.usageAgePoints)
        assertEquals(20, result.participationPoints)
        assertEquals(10, result.recentActivityPoints)
        assertEquals(16, result.walletStrengthPoints)
        assertEquals(71, result.score)
        assertTrue(result.passedThreshold)
    }
}
