package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.solana.TokenGateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles channel management including creation, joining, leaving, and encryption
 */
class ChannelManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val dataManager: DataManager,
    private val coroutineScope: CoroutineScope
) {
    
    // Solana token gate service (set lazily from ChatViewModel via Hilt EntryPoint)
    var tokenGateService: TokenGateService? = null

    // Channel encryption and security
    private val channelKeys = mutableMapOf<String, SecretKeySpec>()
    private val channelPasswords = mutableMapOf<String, String>()
    private val channelKeyCommitments = mutableMapOf<String, String>()
    private val retentionEnabledChannels = mutableSetOf<String>()
    private val tokenGatedChannels = mutableSetOf<String>()
    
    // MARK: - Channel Lifecycle
    
    fun joinChannel(
        channel: String,
        password: String? = null,
        myPeerID: String,
        timeline: com.bitchat.android.geohash.ChannelID? = null
    ): Boolean {
        val channelTag = if (channel.startsWith("#")) channel else "#$channel"

        // Create composite key based on timeline
        val key = ChannelKeys.create(timeline, channelTag)

        // Check if already joined
        if (state.getJoinedChannelsValue().contains(key)) {
            if (state.getPasswordProtectedChannelsValue().contains(key) && !channelKeys.containsKey(key)) {
                // Need password verification
                if (password != null) {
                    return verifyChannelPassword(key, password)
                } else {
                    state.setPasswordPromptChannel(key)
                    state.setShowPasswordPrompt(true)
                    return false
                }
            }
            switchToChannel(key)
            return true
        }

        // If password protected and no key yet
        if (state.getPasswordProtectedChannelsValue().contains(key) && !channelKeys.containsKey(key)) {
            if (dataManager.isChannelCreator(key, myPeerID)) {
                // Channel creator bypass
            } else if (password != null) {
                if (!verifyChannelPassword(key, password)) {
                    return false
                }
            } else {
                state.setPasswordPromptChannel(key)
                state.setShowPasswordPrompt(true)
                return false
            }
        }

        // Token gate validation (if service available and channel is gated)
        val tgs = tokenGateService
        if (tgs != null) {
            try {
                val isGated = runBlocking { tgs.isTokenGated(key) }
                if (isGated) {
                    tokenGatedChannels.add(key)
                    val validation = runBlocking { tgs.validateEligibility(key) }
                    validation.onSuccess { result ->
                        if (!result.isEligible) {
                            val displayAmount = tgs.formatTokenAmount(result.requiredBalance, result.tokenDecimals)
                            val symbol = result.tokenSymbol.ifEmpty { "tokens" }
                            val msg = BitchatMessage(
                                sender = "system",
                                content = "token gate: you need at least $displayAmount $symbol to join $channelTag. check your wallet.",
                                timestamp = Date(),
                                isRelay = false
                            )
                            messageManager.addMessage(msg)
                            return false
                        }
                    }.onFailure { error ->
                        val msg = BitchatMessage(
                            sender = "system",
                            content = "token gate check failed: ${error.message}",
                            timestamp = Date(),
                            isRelay = false
                        )
                        messageManager.addMessage(msg)
                        return false
                    }
                }
            } catch (_: Exception) { }
        }

        // Join the channel
        val updatedChannels = state.getJoinedChannelsValue().toMutableSet()
        updatedChannels.add(key)
        state.setJoinedChannels(updatedChannels)

        // Set as creator if new channel
        if (!dataManager.channelCreators.containsKey(key) && !state.getPasswordProtectedChannelsValue().contains(key)) {
            dataManager.addChannelCreator(key, myPeerID)
        }

        // Add ourselves as member
        dataManager.addChannelMember(key, myPeerID)

        // Initialize channel messages if needed
        if (!state.getChannelMessagesValue().containsKey(key)) {
            val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
            updatedChannelMessages[key] = emptyList()
            state.setChannelMessages(updatedChannelMessages)
        }

        switchToChannel(key)
        saveChannelData()
        return true
    }
    
    fun leaveChannel(channel: String) {
        val updatedChannels = state.getJoinedChannelsValue().toMutableSet()
        updatedChannels.remove(channel)
        state.setJoinedChannels(updatedChannels)
        
        // Exit channel if currently in it
        if (state.getCurrentChannelValue() == channel) {
            state.setCurrentChannel(null)
        }
        
        // Cleanup
        messageManager.removeChannelMessages(channel)
        dataManager.removeChannelMembers(channel)
        channelKeys.remove(channel)
        channelPasswords.remove(channel)
        dataManager.removeChannelCreator(channel)
        
        saveChannelData()
    }
    
    fun switchToChannel(channel: String?) {
        state.setCurrentChannel(channel)
        state.setSelectedPrivateChatPeer(null)
        
        // Clear unread count
        channel?.let { ch ->
            messageManager.clearChannelUnreadCount(ch)
        }
    }
    
    // MARK: - Channel Password and Encryption
    
    private fun verifyChannelPassword(channel: String, password: String): Boolean {
        // TODO: REMOVE THIS - FOR TESTING ONLY
        return true
    }
    
    private fun deriveChannelKey(password: String, channelName: String): SecretKeySpec {
        // PBKDF2 key derivation (same as iOS version)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(),
            channelName.toByteArray(),
            100000, // 100,000 iterations (same as iOS)
            256 // 256-bit key
        )
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }
    
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return decryptChannelMessage(encryptedContent, channel, null)
    }
    
    private fun decryptChannelMessage(encryptedContent: ByteArray, channel: String, testKey: SecretKeySpec?): String? {
        val key = testKey ?: channelKeys[channel] ?: return null
        
        try {
            if (encryptedContent.size < 16) return null // 12 bytes IV + minimum ciphertext
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = encryptedContent.sliceArray(0..11)
            val ciphertext = encryptedContent.sliceArray(12 until encryptedContent.size)
            
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
            
            val decryptedData = cipher.doFinal(ciphertext)
            return String(decryptedData, Charsets.UTF_8)
            
        } catch (e: Exception) {
            return null
        }
    }
    
    fun sendEncryptedChannelMessage(
        content: String, 
        mentions: List<String>, 
        channel: String, 
        senderNickname: String?, 
        myPeerID: String,
        onEncryptedPayload: (ByteArray) -> Unit,
        onFallback: () -> Unit
    ) {
        // TODO: REIMPLEMENT – REMOVED FOR NOW
        return
    }
    
    // MARK: - Channel Management
    
    fun addChannelMessage(channel: String, message: BitchatMessage, senderPeerID: String?) {
        messageManager.addChannelMessage(channel, message)
        
        // Track as channel member
        senderPeerID?.let { peerID ->
            dataManager.addChannelMember(channel, peerID)
        }
    }
    
    fun removeChannelMember(channel: String, peerID: String) {
        dataManager.removeChannelMember(channel, peerID)
    }
    
    fun cleanupDisconnectedMembers(connectedPeers: List<String>, myPeerID: String) {
        dataManager.cleanupAllDisconnectedMembers(connectedPeers, myPeerID)
    }
    
    // MARK: - Channel Information
    
    fun isChannelPasswordProtected(channel: String): Boolean {
        return state.getPasswordProtectedChannelsValue().contains(channel)
    }
    
    fun hasChannelKey(channel: String): Boolean {
        return channelKeys.containsKey(channel)
    }
    
    fun getChannelPassword(channel: String): String? {
        return channelPasswords[channel]
    }
    
    fun isChannelCreator(channel: String, peerID: String): Boolean {
        return dataManager.isChannelCreator(channel, peerID)
    }
    
    fun isChannelTokenGated(channel: String): Boolean {
        return tokenGatedChannels.contains(channel)
    }

    fun getJoinedChannelsList(): List<String> {
        return state.getJoinedChannelsValue().toList().sorted()
    }
    
    // MARK: - Data Persistence
    
    private fun saveChannelData() {
        dataManager.saveChannelData(state.getJoinedChannelsValue(), state.getPasswordProtectedChannelsValue())
    }
    
    fun loadChannelData(): Pair<Set<String>, Set<String>> {
        return dataManager.loadChannelData()
    }
    
    // MARK: - Password Management
    
    fun hidePasswordPrompt() {
        state.setShowPasswordPrompt(false)
        state.setPasswordPromptChannel(null)
    }

    fun setChannelPassword(channel: String, password: String) {

        channelPasswords[channel] = password

        channelKeys[channel] = deriveChannelKey(password, channel)

        state.setPasswordProtectedChannels(
            state.getPasswordProtectedChannelsValue().toMutableSet().apply { add(channel) }
        )

        dataManager.saveChannelData(
            state.getJoinedChannelsValue(),
            state.getPasswordProtectedChannelsValue()
        )
    }
    
    // MARK: - Emergency Clear
    
    fun clearAllChannels() {
        state.setJoinedChannels(emptySet())
        state.setCurrentChannel(null)
        state.setPasswordProtectedChannels(emptySet())
        state.setShowPasswordPrompt(false)
        state.setPasswordPromptChannel(null)
        
        channelKeys.clear()
        channelPasswords.clear()
        channelKeyCommitments.clear()
        retentionEnabledChannels.clear()
    }
}
