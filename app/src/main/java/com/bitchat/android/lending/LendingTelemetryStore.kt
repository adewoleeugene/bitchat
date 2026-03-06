package com.bitchat.android.lending

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LendingTelemetryStore @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private const val PREFS_NAME = "lending_telemetry"
        private const val KEY_APP_FIRST_SEEN_AT = "app_first_seen_at"
        private const val KEY_PEER_ACTIVITY = "peer_activity"
        private const val RECENT_ACTIVITY_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
    }

    data class PeerActivityTelemetry(
        val peerId: String,
        val totalChannelMessages: Int = 0,
        val totalPrivateMessages: Int = 0,
        val totalFeedPosts: Int = 0,
        val totalFeedReplies: Int = 0,
        val totalFeedReactions: Int = 0,
        val recentActivityTimestamps: List<Long> = emptyList(),
        val lastActivityAt: Long = 0L
    ) {
        val totalActions: Int
            get() = totalChannelMessages + totalPrivateMessages + totalFeedPosts + totalFeedReplies + totalFeedReactions
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, PeerActivityTelemetry>()

    init {
        ensureAppFirstSeen()
        loadCache()
    }

    fun ensureAppFirstSeen(now: Long = System.currentTimeMillis()): Long {
        val existing = prefs.getLong(KEY_APP_FIRST_SEEN_AT, 0L)
        if (existing > 0L) return existing
        prefs.edit().putLong(KEY_APP_FIRST_SEEN_AT, now).apply()
        return now
    }

    fun getAppFirstSeenAt(): Long = ensureAppFirstSeen()

    fun getPeerActivity(peerId: String, now: Long = System.currentTimeMillis()): PeerActivityTelemetry {
        val current = cache[peerId] ?: PeerActivityTelemetry(peerId = peerId)
        val pruned = current.copy(
            recentActivityTimestamps = current.recentActivityTimestamps.filter { it >= now - RECENT_ACTIVITY_WINDOW_MS }
        )
        if (pruned != current) {
            cache[peerId] = pruned
            persistCache()
        }
        return pruned
    }

    fun hasTrackedActivity(peerId: String): Boolean = getPeerActivity(peerId).totalActions > 0

    fun recordChannelMessage(peerId: String, timestamp: Long = System.currentTimeMillis()) {
        update(peerId, timestamp) { current ->
            current.copy(totalChannelMessages = current.totalChannelMessages + 1)
        }
    }

    fun recordPrivateMessage(peerId: String, timestamp: Long = System.currentTimeMillis()) {
        update(peerId, timestamp) { current ->
            current.copy(totalPrivateMessages = current.totalPrivateMessages + 1)
        }
    }

    fun recordFeedPost(peerId: String, timestamp: Long = System.currentTimeMillis()) {
        update(peerId, timestamp) { current ->
            current.copy(totalFeedPosts = current.totalFeedPosts + 1)
        }
    }

    fun recordFeedReply(peerId: String, timestamp: Long = System.currentTimeMillis()) {
        update(peerId, timestamp) { current ->
            current.copy(totalFeedReplies = current.totalFeedReplies + 1)
        }
    }

    fun recordFeedReaction(peerId: String, timestamp: Long = System.currentTimeMillis()) {
        update(peerId, timestamp) { current ->
            current.copy(totalFeedReactions = current.totalFeedReactions + 1)
        }
    }

    fun seedPeerActivity(
        peerId: String,
        totalChannelMessages: Int,
        totalPrivateMessages: Int,
        totalFeedPosts: Int,
        totalFeedReplies: Int,
        totalFeedReactions: Int,
        recentActivityTimestamps: List<Long>,
        lastActivityAt: Long
    ) {
        val normalizedRecent = recentActivityTimestamps
            .filter { it > 0L }
            .sorted()
            .takeLast(500)
        cache[peerId] = PeerActivityTelemetry(
            peerId = peerId,
            totalChannelMessages = totalChannelMessages,
            totalPrivateMessages = totalPrivateMessages,
            totalFeedPosts = totalFeedPosts,
            totalFeedReplies = totalFeedReplies,
            totalFeedReactions = totalFeedReactions,
            recentActivityTimestamps = normalizedRecent,
            lastActivityAt = lastActivityAt
        )
        persistCache()
    }

    private fun update(
        peerId: String,
        timestamp: Long,
        transform: (PeerActivityTelemetry) -> PeerActivityTelemetry
    ) {
        if (peerId.isBlank()) return
        val now = System.currentTimeMillis()
        val current = getPeerActivity(peerId, now)
        val updated = transform(current).copy(
            recentActivityTimestamps = (current.recentActivityTimestamps + timestamp)
                .filter { it >= now - RECENT_ACTIVITY_WINDOW_MS }
                .sorted()
                .takeLast(500),
            lastActivityAt = maxOf(current.lastActivityAt, timestamp)
        )
        cache[peerId] = updated
        persistCache()
    }

    private fun loadCache() {
        val raw = prefs.getString(KEY_PEER_ACTIVITY, null) ?: return
        runCatching {
            val type = object : TypeToken<Map<String, PeerActivityTelemetry>>() {}.type
            val parsed: Map<String, PeerActivityTelemetry> = gson.fromJson(raw, type) ?: emptyMap()
            cache.putAll(parsed)
        }
    }

    private fun persistCache() {
        prefs.edit().putString(KEY_PEER_ACTIVITY, gson.toJson(cache)).apply()
    }
}
