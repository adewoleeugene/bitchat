package com.bitchat.android.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import kotlin.random.Random

/**
 * Handles data persistence operations for the chat system
 */
class DataManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DataManager"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Channel-related maps that need to persist state
    private val _channelCreators = mutableMapOf<String, String>()
    private val _favoritePeers = mutableSetOf<String>()
    private val _blockedUsers = mutableSetOf<String>()
    private val _channelMembers = mutableMapOf<String, MutableSet<String>>()
    private val _channelRoles = mutableMapOf<String, MutableMap<String, String>>()
    
    val channelCreators: Map<String, String> get() = _channelCreators
    val favoritePeers: Set<String> get() = _favoritePeers
    val blockedUsers: Set<String> get() = _blockedUsers
    val channelMembers: Map<String, MutableSet<String>> get() = _channelMembers
    val channelRoles: Map<String, MutableMap<String, String>> get() = _channelRoles
    
    // MARK: - Nickname Management
    
    fun loadNickname(): String {
        val savedNickname = prefs.getString("nickname", null)
        return if (savedNickname != null) {
            savedNickname
        } else {
            val randomNickname = "anon${Random.nextInt(1000, 9999)}"
            saveNickname(randomNickname)
            randomNickname
        }
    }
    
    fun saveNickname(nickname: String) {
        prefs.edit().putString("nickname", nickname).apply()
    }
    
    // MARK: - Geohash Channel Persistence
    
    fun loadLastGeohashChannel(): String? {
        return prefs.getString("last_geohash_channel", null)
    }
    
    fun saveLastGeohashChannel(channelData: String) {
        prefs.edit().putString("last_geohash_channel", channelData).apply()
        Log.d(TAG, "Saved last geohash channel: $channelData")
    }
    
    fun clearLastGeohashChannel() {
        prefs.edit().remove("last_geohash_channel").apply()
        Log.d(TAG, "Cleared last geohash channel")
    }

    // MARK: - Location Services State
    
    fun saveLocationServicesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("location_services_enabled", enabled).apply()
        Log.d(TAG, "Saved location services enabled state: $enabled")
    }
    
    fun isLocationServicesEnabled(): Boolean {
        return prefs.getBoolean("location_services_enabled", true) // Default to enabled
    }
    
    // MARK: - Channel Data Management
    
    fun loadChannelData(): Pair<Set<String>, Set<String>> {
        // Load joined channels
        val savedChannels = prefs.getStringSet("joined_channels", emptySet()) ?: emptySet()

        // Normalize old format keys (backward compatibility migration)
        // Old format: "#gaming" -> New format: "mesh:#gaming"
        val normalizedChannels = savedChannels.map { key ->
            ChannelKeys.normalize(key)
        }.toSet()

        // Load password protected channels
        val savedProtectedChannels = prefs.getStringSet("password_protected_channels", emptySet()) ?: emptySet()

        // Normalize password protected channels as well
        val normalizedProtectedChannels = savedProtectedChannels.map { key ->
            ChannelKeys.normalize(key)
        }.toSet()

        // Load channel creators
        val creatorsJson = prefs.getString("channel_creators", "{}")
        try {
            val creatorsMap = gson.fromJson(creatorsJson, Map::class.java) as? Map<String, String>
            // Normalize creator keys too
            creatorsMap?.forEach { (key, value) ->
                val normalizedKey = ChannelKeys.normalize(key)
                _channelCreators[normalizedKey] = value
                setChannelRole(normalizedKey, value, ChannelRoles.OWNER)
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }

        // Load channel roles (best effort, backward compatible)
        val rolesJson = prefs.getString("channel_roles", "{}")
        try {
            val raw = gson.fromJson(rolesJson, Map::class.java) as? Map<*, *>
            raw?.forEach { (channelKeyAny, rolesAny) ->
                val channelKey = ChannelKeys.normalize(channelKeyAny?.toString().orEmpty())
                if (channelKey.isBlank()) return@forEach
                val roleMap = mutableMapOf<String, String>()
                val rolesMap = rolesAny as? Map<*, *>
                rolesMap?.forEach { (peerAny, roleAny) ->
                    val peerID = peerAny?.toString().orEmpty()
                    val role = roleAny?.toString().orEmpty()
                    if (peerID.isNotBlank() && role.isNotBlank()) {
                        roleMap[peerID] = role
                    }
                }
                if (roleMap.isNotEmpty()) {
                    _channelRoles[channelKey] = roleMap
                }
            }
        } catch (_: Exception) {
            // Ignore parsing errors
        }

        // Initialize channel members for loaded channels
        normalizedChannels.forEach { channel ->
            if (!_channelMembers.containsKey(channel)) {
                _channelMembers[channel] = mutableSetOf()
            }
        }

        return Pair(normalizedChannels, normalizedProtectedChannels)
    }
    
    fun saveChannelData(joinedChannels: Set<String>, passwordProtectedChannels: Set<String>) {
        prefs.edit().apply {
            putStringSet("joined_channels", joinedChannels)
            putStringSet("password_protected_channels", passwordProtectedChannels)
            putString("channel_creators", gson.toJson(_channelCreators))
            putString("channel_roles", gson.toJson(_channelRoles))
            apply()
        }
    }
    
    fun addChannelCreator(channel: String, creatorID: String) {
        _channelCreators[channel] = creatorID
        setChannelRole(channel, creatorID, ChannelRoles.OWNER)
    }
    
    fun removeChannelCreator(channel: String) {
        val creator = _channelCreators[channel]
        if (!creator.isNullOrBlank()) {
            _channelRoles[channel]?.remove(creator)
        }
        _channelCreators.remove(channel)
    }
    
    fun isChannelCreator(channel: String, peerID: String): Boolean {
        return _channelCreators[channel] == peerID
    }

    fun hasChannelCreator(channel: String): Boolean {
        return _channelCreators.containsKey(channel)
    }

    fun transferChannelOwnership(channel: String, newOwnerPeerID: String): Boolean {
        val oldOwner = _channelCreators[channel] ?: return false
        if (oldOwner == newOwnerPeerID) return false

        _channelCreators[channel] = newOwnerPeerID
        setChannelRole(channel, oldOwner, ChannelRoles.ADMIN)
        setChannelRole(channel, newOwnerPeerID, ChannelRoles.OWNER)
        addChannelMember(channel, newOwnerPeerID)
        return true
    }
    
    // MARK: - Channel Members Management
    
    fun addChannelMember(channel: String, peerID: String) {
        if (!_channelMembers.containsKey(channel)) {
            _channelMembers[channel] = mutableSetOf()
        }
        _channelMembers[channel]?.add(peerID)
        if (_channelRoles[channel]?.containsKey(peerID) != true) {
            setChannelRole(channel, peerID, ChannelRoles.MEMBER)
        }
    }
    
    fun removeChannelMember(channel: String, peerID: String) {
        _channelMembers[channel]?.remove(peerID)
        _channelRoles[channel]?.remove(peerID)
    }
    
    fun removeChannelMembers(channel: String) {
        _channelMembers.remove(channel)
        _channelRoles.remove(channel)
    }
    
    fun cleanupDisconnectedMembers(channel: String, connectedPeers: List<String>, myPeerID: String) {
        val removed = _channelMembers[channel]?.filter { memberID ->
            memberID != myPeerID && !connectedPeers.contains(memberID)
        }.orEmpty()
        _channelMembers[channel]?.removeAll(removed.toSet())
        removed.forEach { _channelRoles[channel]?.remove(it) }
    }
    
    fun cleanupAllDisconnectedMembers(connectedPeers: List<String>, myPeerID: String) {
        _channelMembers.forEach { (channel, members) ->
            val removed = members.filter { memberID ->
                memberID != myPeerID && !connectedPeers.contains(memberID)
            }
            members.removeAll(removed.toSet())
            removed.forEach { _channelRoles[channel]?.remove(it) }
        }
    }

    fun setChannelRole(channel: String, peerID: String, role: String) {
        val normalized = when (role) {
            ChannelRoles.OWNER, ChannelRoles.ADMIN, ChannelRoles.MEMBER -> role
            else -> ChannelRoles.MEMBER
        }
        if (!_channelRoles.containsKey(channel)) {
            _channelRoles[channel] = mutableMapOf()
        }
        _channelRoles[channel]?.set(peerID, normalized)
    }

    fun getChannelRole(channel: String, peerID: String): String {
        return _channelRoles[channel]?.get(peerID)
            ?: if (_channelCreators[channel] == peerID) ChannelRoles.OWNER else ChannelRoles.MEMBER
    }

    fun isChannelAdmin(channel: String, peerID: String): Boolean {
        val role = getChannelRole(channel, peerID)
        return role == ChannelRoles.OWNER || role == ChannelRoles.ADMIN
    }

    fun getChannelMembers(channel: String): Set<String> {
        return _channelMembers[channel]?.toSet() ?: emptySet()
    }
    
    // MARK: - Favorites Management
    
    fun loadFavorites() {
        val savedFavorites = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        _favoritePeers.addAll(savedFavorites)
        Log.d(TAG, "Loaded ${savedFavorites.size} favorite users from storage: $savedFavorites")
    }
    
    fun saveFavorites() {
        prefs.edit().putStringSet("favorites", _favoritePeers).apply()
        Log.d(TAG, "Saved ${_favoritePeers.size} favorite users to storage: $_favoritePeers")
    }
    
    fun addFavorite(fingerprint: String) {
        val wasAdded = _favoritePeers.add(fingerprint)
        Log.d(TAG, "addFavorite: fingerprint=$fingerprint, wasAdded=$wasAdded")
        saveFavorites()
        logAllFavorites()
    }
    
    fun removeFavorite(fingerprint: String) {
        val wasRemoved = _favoritePeers.remove(fingerprint)
        Log.d(TAG, "removeFavorite: fingerprint=$fingerprint, wasRemoved=$wasRemoved")
        saveFavorites()
        logAllFavorites()
    }
    
    fun isFavorite(fingerprint: String): Boolean {
        val result = _favoritePeers.contains(fingerprint)
        Log.d(TAG, "isFavorite check: fingerprint=$fingerprint, result=$result")
        return result
    }
    
    fun logAllFavorites() {
        Log.i(TAG, "=== ALL FAVORITE USERS ===")
        Log.i(TAG, "Total favorites: ${_favoritePeers.size}")
        _favoritePeers.forEach { fingerprint ->
            Log.i(TAG, "Favorite fingerprint: $fingerprint")
        }
        Log.i(TAG, "========================")
    }
    
    // MARK: - Blocked Users Management
    
    fun loadBlockedUsers() {
        val savedBlockedUsers = prefs.getStringSet("blocked_users", emptySet()) ?: emptySet()
        _blockedUsers.addAll(savedBlockedUsers)
    }
    
    fun saveBlockedUsers() {
        prefs.edit().putStringSet("blocked_users", _blockedUsers).apply()
    }
    
    fun addBlockedUser(fingerprint: String) {
        _blockedUsers.add(fingerprint)
        saveBlockedUsers()
    }
    
    fun removeBlockedUser(fingerprint: String) {
        _blockedUsers.remove(fingerprint)
        saveBlockedUsers()
    }
    
    fun isUserBlocked(fingerprint: String): Boolean {
        return _blockedUsers.contains(fingerprint)
    }
    
    // MARK: - Geohash Blocked Users Management
    
    private val _geohashBlockedUsers = mutableSetOf<String>() // Set of nostr pubkey hex
    val geohashBlockedUsers: Set<String> get() = _geohashBlockedUsers.toSet()
    
    fun loadGeohashBlockedUsers() {
        val savedGeohashBlockedUsers = prefs.getStringSet("geohash_blocked_users", emptySet()) ?: emptySet()
        _geohashBlockedUsers.addAll(savedGeohashBlockedUsers)
    }
    
    fun saveGeohashBlockedUsers() {
        prefs.edit().putStringSet("geohash_blocked_users", _geohashBlockedUsers).apply()
    }
    
    fun addGeohashBlockedUser(pubkeyHex: String) {
        _geohashBlockedUsers.add(pubkeyHex)
        saveGeohashBlockedUsers()
    }
    
    fun removeGeohashBlockedUser(pubkeyHex: String) {
        _geohashBlockedUsers.remove(pubkeyHex)
        saveGeohashBlockedUsers()
    }
    
    fun isGeohashUserBlocked(pubkeyHex: String): Boolean {
        return _geohashBlockedUsers.contains(pubkeyHex)
    }
    
    // MARK: - Emergency Clear
    
    fun clearAllData() {
        _channelCreators.clear()
        _favoritePeers.clear()
        _blockedUsers.clear()
        _geohashBlockedUsers.clear()
        _channelMembers.clear()
        _channelRoles.clear()
        prefs.edit().clear().apply()
    }
}
