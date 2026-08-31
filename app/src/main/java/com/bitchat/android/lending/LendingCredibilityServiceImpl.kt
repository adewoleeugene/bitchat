package com.bitchat.android.lending

import android.content.Context
import com.bitchat.android.data.local.FeedDao
import com.bitchat.android.data.local.LendingDao
import com.bitchat.android.data.local.WalletDao
import com.bitchat.android.data.local.entities.CredibilityHardGateStatus
import com.bitchat.android.data.local.entities.CredibilityProfileEntity
import com.bitchat.android.data.local.entities.CredibilitySubjectType
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LendingCredibilityServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lendingDao: LendingDao,
    private val walletDao: WalletDao,
    private val feedDao: FeedDao,
    private val telemetryStore: LendingTelemetryStore
) : LendingCredibilityService {
    companion object {
        private const val RECENT_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
    }

    private val gson = Gson()
    private val scorer = LendingCredibilityScorer()

    override suspend fun getProfile(subjectType: String, subjectKey: String): CredibilityProfileEntity? {
        return lendingDao.getCredibilityProfile(subjectType, subjectKey)
    }

    override suspend fun evaluateAndPersist(request: LendingCredibilityRequest): LendingCredibilityResult =
        withContext(Dispatchers.IO) {
            val telemetry = bootstrapAndLoadTelemetry(request.peerId, request.now)
            val wallet = walletDao.getActiveWallet()
            val appFirstSeenAt = telemetryStore.getAppFirstSeenAt()
            val walletFirstSeenAt = wallet?.createdAt ?: 0L
            val firstSeenAt = listOf(appFirstSeenAt, walletFirstSeenAt).filter { it > 0L }.minOrNull() ?: 0L
            val scoreOutput = scorer.score(
                LendingCredibilityScorer.Input(
                    firstSeenAt = firstSeenAt,
                    now = request.now,
                    walletLinked = wallet != null,
                    walletCreatedAt = wallet?.createdAt ?: 0L,
                    walletBalanceLamports = wallet?.lastBalanceLamports ?: 0L,
                    stakeBalanceSatisfied = request.stakeBalanceSatisfied,
                    totalActions = telemetry.totalActions,
                    recentActions = telemetry.recentActivityCount
                )
            )

            val snapshotJson = gson.toJson(
                mapOf(
                    "peerId" to request.peerId,
                    "stakeAmountRequired" to request.stakeAmountRequired,
                    "observedStakeBalance" to request.observedStakeBalance,
                    "totalActions" to telemetry.totalActions,
                    "recentActions" to telemetry.recentActivityCount,
                    "hardGateFailures" to scoreOutput.hardGateFailures
                )
            )

            val profile = CredibilityProfileEntity(
                profileId = "peer:${request.peerId}",
                subjectType = CredibilitySubjectType.PEER_ID,
                subjectKey = request.peerId,
                score = scoreOutput.score,
                usageAgePoints = scoreOutput.usageAgePoints,
                participationPoints = scoreOutput.participationPoints,
                recentActivityPoints = scoreOutput.recentActivityPoints,
                walletStrengthPoints = scoreOutput.walletStrengthPoints,
                hardGateStatus = if (scoreOutput.passedHardGates) {
                    CredibilityHardGateStatus.PASSED
                } else {
                    CredibilityHardGateStatus.FAILED
                },
                firstSeenAt = firstSeenAt,
                lastComputedAt = request.now,
                snapshotJson = snapshotJson
            )
            lendingDao.upsertCredibilityProfile(profile)

            wallet?.publicKey?.takeIf { it.isNotBlank() }?.let { walletAddress ->
                lendingDao.upsertCredibilityProfile(
                    profile.copy(
                        profileId = "wallet:$walletAddress",
                        subjectType = CredibilitySubjectType.WALLET_ADDRESS,
                        subjectKey = walletAddress
                    )
                )
            }

            LendingCredibilityResult(
                profile = profile,
                passedHardGates = scoreOutput.passedHardGates,
                passedThreshold = scoreOutput.passedThreshold,
                hardGateFailures = scoreOutput.hardGateFailures,
                totalActions = telemetry.totalActions,
                recentActions = telemetry.recentActivityCount
            )
        }

    private suspend fun bootstrapAndLoadTelemetry(
        peerId: String,
        now: Long
    ): LendingTelemetrySnapshot {
        if (!telemetryStore.hasTrackedActivity(peerId)) {
            val dataManager = com.bitchat.android.ui.DataManager(context)
            val channelMessages = dataManager.loadChannelMessages().values.flatten()
            val channelEvents = channelMessages
                .filter { it.senderPeerID == peerId }
                .map { it.timestamp.time }

            val since = now - RECENT_WINDOW_MS
            val feedPostTimestamps = feedDao.getRecentPostTimestampsByAuthor(peerId, since)
            val feedReplyTimestamps = feedDao.getRecentReplyTimestampsByAuthor(peerId, since)
            val feedReactionTimestamps = feedDao.getRecentReactionTimestampsByAuthor(peerId, since)
            val allRecent = (channelEvents.filter { it >= since } + feedPostTimestamps + feedReplyTimestamps + feedReactionTimestamps)
                .sorted()

            telemetryStore.seedPeerActivity(
                peerId = peerId,
                totalChannelMessages = channelEvents.size,
                totalPrivateMessages = 0,
                totalFeedPosts = feedDao.countPostsByAuthor(peerId),
                totalFeedReplies = feedDao.countRepliesByAuthor(peerId),
                totalFeedReactions = feedDao.countReactionsByAuthor(peerId),
                recentActivityTimestamps = allRecent,
                lastActivityAt = (channelEvents.maxOrNull() ?: 0L)
                    .coerceAtLeast(feedPostTimestamps.maxOrNull() ?: 0L)
                    .coerceAtLeast(feedReplyTimestamps.maxOrNull() ?: 0L)
                    .coerceAtLeast(feedReactionTimestamps.maxOrNull() ?: 0L)
            )
        }

        val activity = telemetryStore.getPeerActivity(peerId, now)
        return LendingTelemetrySnapshot(
            totalActions = activity.totalActions,
            recentActivityCount = activity.recentActivityTimestamps.size
        )
    }

    private data class LendingTelemetrySnapshot(
        val totalActions: Int,
        val recentActivityCount: Int
    )
}
