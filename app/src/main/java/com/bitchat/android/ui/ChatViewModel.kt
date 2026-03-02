package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.bitchat.android.di.SolanaEntryPoint
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.ChannelRolePolicyPayload
import com.bitchat.android.protocol.BitchatPacket
import dagger.hilt.android.EntryPointAccessors


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.bitchat.android.util.NotificationIntervalManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Date
import kotlin.random.Random

/**
 * Refactored ChatViewModel - Main coordinator for bitchat functionality
 * Delegates specific responsibilities to specialized managers while maintaining 100% iOS compatibility
 */
class ChatViewModel(
    application: Application,
    val meshService: BluetoothMeshService
) : AndroidViewModel(application), BluetoothMeshDelegate {
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }
    private var notarizationService: com.bitchat.android.solana.MessageNotarizationService? = null
    private var nftAvatarService: com.bitchat.android.solana.NftAvatarService? = null
    private var solanaPaymentManager: com.bitchat.android.solana.SolanaPaymentManager? = null
    private var lastAnnouncedWalletAddress: String? = null
    private var walletLinkAnnounceJob: kotlinx.coroutines.Job? = null
    private var tokenGateRevalidationJob: kotlinx.coroutines.Job? = null
    private var tokenGatePolicySyncJob: kotlinx.coroutines.Job? = null
    private var channelRolePolicySyncJob: kotlinx.coroutines.Job? = null
    private val tokenGateDenyStrikes = mutableMapOf<String, Int>()

    companion object {
        private const val TAG = "ChatViewModel"
        private const val TOKEN_GATE_REVALIDATION_INTERVAL_MS = 60_000L
        private const val TOKEN_GATE_POLICY_SYNC_INTERVAL_MS = 5 * 60_000L
        private const val CHANNEL_ROLE_POLICY_SYNC_INTERVAL_MS = 90_000L
        private const val TOKEN_GATE_DENY_STRIKES_TO_KICK = 2
    }

    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendVoiceNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendFileNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendImageNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    // MARK: - State management
    private val state = ChatState()

    // Transfer progress tracking
    private val transferMessageMap = mutableMapOf<String, String>()
    private val messageTransferMap = mutableMapOf<String, String>()

    // Specialized managers
    private val dataManager = DataManager(application.applicationContext)
    private val messageManager = MessageManager(state)
    private val channelManager = ChannelManager(state, messageManager, dataManager, viewModelScope)

    // Create Noise session delegate for clean dependency injection
    private val noiseSessionDelegate = object : NoiseSessionDelegate {
        override fun hasEstablishedSession(peerID: String): Boolean = meshService.hasEstablishedSession(peerID)
        override fun initiateHandshake(peerID: String) = meshService.initiateNoiseHandshake(peerID)
        override fun getMyPeerID(): String = meshService.myPeerID
    }

    val privateChatManager = PrivateChatManager(state, messageManager, dataManager, noiseSessionDelegate)
    private val commandProcessor = CommandProcessor(state, messageManager, channelManager, privateChatManager)
    private val notificationManager = NotificationManager(
      application.applicationContext,
      NotificationManagerCompat.from(application.applicationContext),
      NotificationIntervalManager()
    )

    // Media file sending manager
    private val mediaSendingManager = MediaSendingManager(state, messageManager, channelManager, meshService)
    
    // Delegate handler for mesh callbacks
    private val meshDelegateHandler = MeshDelegateHandler(
        state = state,
        messageManager = messageManager,
        channelManager = channelManager,
        privateChatManager = privateChatManager,
        notificationManager = notificationManager,
        coroutineScope = viewModelScope,
        onHapticFeedback = { ChatViewModelUtils.triggerHapticFeedback(application.applicationContext) },
        getMyPeerID = { meshService.myPeerID },
        getMeshService = { meshService }
    )
    
    // New Geohash architecture ViewModel (replaces God object service usage in UI path)
    val geohashViewModel = GeohashViewModel(
        application = application,
        state = state,
        messageManager = messageManager,
        privateChatManager = privateChatManager,
        meshDelegateHandler = meshDelegateHandler,
        dataManager = dataManager,
        notificationManager = notificationManager
    )




    // Expose state through LiveData (maintaining the same interface)
    val messages: LiveData<List<BitchatMessage>> = state.messages
    val connectedPeers: LiveData<List<String>> = state.connectedPeers
    val nickname: LiveData<String> = state.nickname
    val isConnected: LiveData<Boolean> = state.isConnected
    val privateChats: LiveData<Map<String, List<BitchatMessage>>> = state.privateChats
    val selectedPrivateChatPeer: LiveData<String?> = state.selectedPrivateChatPeer
    val unreadPrivateMessages: LiveData<Set<String>> = state.unreadPrivateMessages
    val joinedChannels: LiveData<Set<String>> = state.joinedChannels
    val currentChannel: LiveData<String?> = state.currentChannel
    val channelMessages: LiveData<Map<String, List<BitchatMessage>>> = state.channelMessages
    val unreadChannelMessages: LiveData<Map<String, Int>> = state.unreadChannelMessages
    val passwordProtectedChannels: LiveData<Set<String>> = state.passwordProtectedChannels
    val showPasswordPrompt: LiveData<Boolean> = state.showPasswordPrompt
    val passwordPromptChannel: LiveData<String?> = state.passwordPromptChannel
    val showSidebar: LiveData<Boolean> = state.showSidebar
    val hasUnreadChannels = state.hasUnreadChannels
    val hasUnreadPrivateMessages = state.hasUnreadPrivateMessages
    val showCommandSuggestions: LiveData<Boolean> = state.showCommandSuggestions
    val commandSuggestions: LiveData<List<CommandSuggestion>> = state.commandSuggestions
    val showMentionSuggestions: LiveData<Boolean> = state.showMentionSuggestions
    val mentionSuggestions: LiveData<List<String>> = state.mentionSuggestions
    val favoritePeers: LiveData<Set<String>> = state.favoritePeers
    val peerSessionStates: LiveData<Map<String, String>> = state.peerSessionStates
    val peerFingerprints: LiveData<Map<String, String>> = state.peerFingerprints
    val peerNicknames: LiveData<Map<String, String>> = state.peerNicknames
    val peerRSSI: LiveData<Map<String, Int>> = state.peerRSSI
    val peerDirect: LiveData<Map<String, Boolean>> = state.peerDirect
    val showAppInfo: LiveData<Boolean> = state.showAppInfo
    val selectedLocationChannel: LiveData<com.bitchat.android.geohash.ChannelID?> = state.selectedLocationChannel
    val isTeleported: LiveData<Boolean> = state.isTeleported
    val geohashPeople: LiveData<List<GeoPerson>> = state.geohashPeople
    val teleportedGeo: LiveData<Set<String>> = state.teleportedGeo
    val geohashParticipantCounts: LiveData<Map<String, Int>> = state.geohashParticipantCounts

    // Feed state
    val selectedTab: LiveData<String> = state.selectedTab
    val feedPosts: LiveData<List<com.bitchat.android.data.local.entities.FeedPostEntity>> = state.feedPosts
    val expandedPostId: LiveData<String?> = state.expandedPostId
    val feedReactions: LiveData<Map<String, List<com.bitchat.android.data.local.entities.FeedReactionEntity>>> = state.feedReactions
    val feedReplies: LiveData<Map<String, List<com.bitchat.android.data.local.entities.FeedReplyEntity>>> = state.feedReplies
    val showNewPostComposer: LiveData<Boolean> = state.showNewPostComposer

    private var feedService: com.bitchat.android.feed.FeedService? = null

    init {
        // Note: Mesh service delegate is now set by MainActivity
        loadAndInitialize()
        // Subscribe to BLE transfer progress and reflect in message deliveryStatus
        viewModelScope.launch {
            com.bitchat.android.mesh.TransferProgressManager.events.collect { evt ->
                mediaSendingManager.handleTransferProgressEvent(evt)
            }
        }
        
        // Removed background location notes subscription. Notes now load only when sheet opens.
    }

    fun cancelMediaSend(messageId: String) {
        // Delegate to MediaSendingManager which tracks transfer IDs and cleans up UI state
        mediaSendingManager.cancelMediaSend(messageId)
    }
    
    private fun loadAndInitialize() {
        // Load nickname
        val nickname = dataManager.loadNickname()
        state.setNickname(nickname)
        
        // Load data
        val (joinedChannels, protectedChannels) = channelManager.loadChannelData()
        state.setJoinedChannels(joinedChannels)
        state.setPasswordProtectedChannels(protectedChannels)
        
        // Initialize channel messages
        joinedChannels.forEach { channel ->
            if (!state.getChannelMessagesValue().containsKey(channel)) {
                val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
                updatedChannelMessages[channel] = emptyList()
                state.setChannelMessages(updatedChannelMessages)
            }
        }
        
        // Load other data
        dataManager.loadFavorites()
        state.setFavoritePeers(dataManager.favoritePeers.toSet())
        dataManager.loadBlockedUsers()
        dataManager.loadGeohashBlockedUsers()

        // Log all favorites at startup
        dataManager.logAllFavorites()
        logCurrentFavoriteState()
        
        // Initialize session state monitoring
        initializeSessionStateMonitoring()

        // Bridge DebugSettingsManager -> Chat messages when verbose logging is on
        viewModelScope.launch {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().debugMessages.collect { msgs ->
                if (com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().verboseLoggingEnabled.value) {
                    // Only show debug logs in the Mesh chat timeline to avoid leaking into geohash chats
                    val selectedLocation = state.selectedLocationChannel.value
                    if (selectedLocation is com.bitchat.android.geohash.ChannelID.Mesh) {
                        // Append only latest debug message as system message to avoid flooding
                        msgs.lastOrNull()?.let { dm ->
                            messageManager.addSystemMessage(dm.content)
                        }
                    }
                }
            }
        }
        
        // Initialize new geohash architecture
        geohashViewModel.initialize()

        // Initialize favorites persistence service
        com.bitchat.android.favorites.FavoritesPersistenceService.initialize(getApplication())


        // Ensure NostrTransport knows our mesh peer ID for embedded packets
        try {
            val nostrTransport = com.bitchat.android.nostr.NostrTransport.getInstance(getApplication())
            nostrTransport.senderPeerID = meshService.myPeerID
        } catch (_: Exception) { }

        // Note: Mesh service is now started by MainActivity

        // BLE receives are inserted by MessageHandler path; no VoiceNoteBus for Tor in this branch.

        // Wire up Solana services to CommandProcessor via Hilt EntryPoint
        try {
            val solanaEntryPoint = EntryPointAccessors.fromApplication(
                getApplication(), SolanaEntryPoint::class.java
            )
            commandProcessor.walletService = solanaEntryPoint.solanaWalletService()
            commandProcessor.paymentManager = solanaEntryPoint.solanaPaymentManager()
            val tokenGateService = solanaEntryPoint.tokenGateService()
            commandProcessor.tokenGateService = tokenGateService

            // Wire up token gate service to channel manager for join validation
            channelManager.tokenGateService = tokenGateService
            startTokenGateRevalidationLoop(tokenGateService)
            startTokenGatePolicySyncLoop(tokenGateService)
            meshService.onTokenGatePolicyReceived = { fromPeer, payload ->
                viewModelScope.launch {
                    val senderSolanaAddress = meshService.getPeerInfo(fromPeer)?.solanaAddress
                    tokenGateService.applySyncedPolicy(
                        payload = payload,
                        senderSolanaAddress = senderSolanaAddress
                    )
                        .onFailure { error ->
                            Log.d(TAG, "Failed applying token-gate policy sync: ${error.message}")
                        }
                }
            }

            val walletService = solanaEntryPoint.solanaWalletService()
            val rpcService = solanaEntryPoint.solanaRpcService()
            // Set local Solana address on mesh service for identity announcements
            meshService.solanaAddress = walletService.getPublicKeyBase58()
            lastAnnouncedWalletAddress = meshService.solanaAddress
            // Build automatic wallet-link proof for announcements (username <-> wallet binding)
            meshService.buildSolanaLinkProof = { nickname, signingPublicKey ->
                try {
                    val address = walletService.getPublicKeyBase58()
                    if (address.isNullOrBlank()) {
                        null
                    } else {
                        val message = com.bitchat.android.solana.SolanaIdentityProofUtil.buildLinkMessage(
                            nickname = nickname,
                            solanaAddress = address,
                            signingPublicKey = signingPublicKey
                        )
                        val signature = walletService.sign(message)
                        if (signature == null) null else Pair(address, signature)
                    }
                } catch (_: Exception) {
                    null
                }
            }
            meshService.buildSolanaOwnershipProofs = { nickname, signingPublicKey, solanaAddress ->
                try {
                    val now = System.currentTimeMillis()
                    val proofs = mutableListOf<com.bitchat.android.model.SolanaOwnershipProof>()
                    for (config in tokenGateService.getAllTokenGates()) {
                        if (proofs.size >= 12) break
                        val claimType = when (config.gateType) {
                            com.bitchat.android.data.local.entities.TokenGateType.SPL_TOKEN ->
                                com.bitchat.android.model.SolanaOwnershipProof.ClaimType.SPL_TOKEN
                            com.bitchat.android.data.local.entities.TokenGateType.NFT_SPECIFIC ->
                                com.bitchat.android.model.SolanaOwnershipProof.ClaimType.NFT_MINT
                            com.bitchat.android.data.local.entities.TokenGateType.NFT_COLLECTION ->
                                com.bitchat.android.model.SolanaOwnershipProof.ClaimType.NFT_COLLECTION
                            else -> null
                        } ?: continue

                        val validation = tokenGateService
                            .validateEligibility(
                                channelKey = config.channelKey,
                                mode = com.bitchat.android.solana.ValidationMode.PREFER_CACHE_THEN_ONLINE
                            )
                            .getOrNull() ?: continue

                        if (validation.decision != com.bitchat.android.solana.GateDecision.ALLOW) continue

                        val expiresAt = validation.validUntil.coerceAtLeast(now + 60_000L)
                        val unsignedProof = com.bitchat.android.model.SolanaOwnershipProof(
                            claimType = claimType,
                            targetAddress = config.tokenMintAddress,
                            minRequired = config.minBalance,
                            observedBalance = validation.userBalance,
                            validatedAtMs = now,
                            expiresAtMs = expiresAt,
                            signature = ByteArray(64)
                        )
                        val message = com.bitchat.android.solana.SolanaOwnershipProofUtil.buildProofMessage(
                            nickname = nickname,
                            solanaAddress = solanaAddress,
                            signingPublicKey = signingPublicKey,
                            proof = unsignedProof
                        )
                        val signature = walletService.sign(message) ?: continue
                        proofs.add(unsignedProof.copy(signature = signature))
                    }
                    proofs
                } catch (_: Exception) {
                    emptyList()
                }
            }
            meshService.verifyOwnershipProofsOnline = { _, solanaAddress, proofs ->
                var hadDeterminateResult = false
                val kept = mutableListOf<com.bitchat.android.model.SolanaOwnershipProof>()
                for (proof in proofs) {
                    val holds: Boolean? = when (proof.claimType) {
                        com.bitchat.android.model.SolanaOwnershipProof.ClaimType.SPL_TOKEN,
                        com.bitchat.android.model.SolanaOwnershipProof.ClaimType.NFT_MINT -> {
                            rpcService.getTokenBalance(solanaAddress, proof.targetAddress)
                                .getOrNull()
                                ?.let { balance -> balance >= proof.minRequired }
                        }
                        com.bitchat.android.model.SolanaOwnershipProof.ClaimType.NFT_COLLECTION -> {
                            rpcService.hasNftFromCollection(solanaAddress, proof.targetAddress)
                                .getOrNull()
                                ?.let { ownsCollection ->
                                    val observed = if (ownsCollection) 1L else 0L
                                    observed >= proof.minRequired
                                }
                        }
                    }

                    when (holds) {
                        true -> {
                            hadDeterminateResult = true
                            kept.add(proof)
                        }
                        false -> {
                            hadDeterminateResult = true
                            // Drop proof when online check deterministically fails.
                        }
                        null -> {
                            // If online check is unavailable/fails, keep offline-verified proof.
                            kept.add(proof)
                        }
                    }
                }
                if (hadDeterminateResult) kept else null
            }

            // Keep username->wallet mapping current when active wallet changes.
            // We only re-announce when address changes (not for balance updates).
            viewModelScope.launch {
                walletService.observeActiveWallet().collect { activeWallet ->
                    val newAddress = activeWallet?.publicKey
                    if (newAddress != null && newAddress != lastAnnouncedWalletAddress) {
                        meshService.solanaAddress = newAddress
                        lastAnnouncedWalletAddress = newAddress
                        walletLinkAnnounceJob?.cancel()
                        walletLinkAnnounceJob = viewModelScope.launch {
                            // Burst announce to reduce stale username->wallet windows after a switch.
                            meshService.sendBroadcastAnnounce()
                            delay(1_000)
                            if (lastAnnouncedWalletAddress == newAddress) meshService.sendBroadcastAnnounce()
                            delay(2_000)
                            if (lastAnnouncedWalletAddress == newAddress) meshService.sendBroadcastAnnounce()
                        }
                    }
                }
            }

            // Wire up Solana relay handler for mesh transaction relay
            val relayHandler = solanaEntryPoint.solanaRelayHandler()
            meshService.solanaRelayHandler = relayHandler
            relayHandler.onSendRelayReceipt = { receipt ->
                meshService.broadcastSolanaRelayReceipt(receipt)
            }
            relayHandler.onSendRelayClaim = { claim ->
                meshService.broadcastSolanaRelayClaim(claim)
            }
            relayHandler.onSendRelayAck = { ack ->
                meshService.broadcastSolanaRelayAck(ack)
            }
            // Keep relay internals off the main chat timeline to avoid spam.
            relayHandler.onRelayEvent = null

            // Wire mesh relay fallback into payment manager
            val paymentManager = solanaEntryPoint.solanaPaymentManager()
            solanaPaymentManager = paymentManager
            relayHandler.onClaimObserved = { claim ->
                paymentManager.handleRelayClaimObserved(claim)
            }
            relayHandler.onAckObserved = { ack ->
                paymentManager.handleRelayAckObserved(ack)
            }
            paymentManager.onRequestMeshRelay = { request ->
                // Track the outgoing request so we can match the receipt when it arrives
                relayHandler.trackOutgoingRequest(request.requestId)
                meshService.broadcastSolanaRelayRequest(request)
            }

            // Wire 2-step blockhash handshake for truly offline signing
            paymentManager.onRequestBlockhashIntent = { intent ->
                meshService.broadcastSolanaIntentRequest(intent)
            }
            paymentManager.onRequestBalanceIntent = { intent ->
                meshService.broadcastSolanaBalanceIntent(intent)
            }
            relayHandler.onSendBlockhashResponse = { response ->
                meshService.broadcastSolanaBlockhashResponse(response)
            }
            relayHandler.onSendBalanceResponse = { response ->
                meshService.broadcastSolanaBalanceResponse(response)
            }
            meshService.onBlockhashResponseReceived = { response ->
                viewModelScope.launch {
                    paymentManager.handleBlockhashResponse(response)
                }
            }
            meshService.onBalanceResponseReceived = { response ->
                viewModelScope.launch {
                    paymentManager.handleBalanceResponse(response)
                }
            }

            // Surface high-signal payment progress so mesh/offline flow does not
            // look stalled, while still keeping relay-debug chatter out of chat.
            paymentManager.onStatusEvent = { event ->
                val lowered = event.lowercase()
                val shouldShow = lowered.contains("offline") ||
                    lowered.contains("requesting blockhash") ||
                    lowered.contains("signed tx sent") ||
                    lowered.contains("relay not available") ||
                    lowered.contains("blockhash request failed") ||
                    lowered.contains("received empty blockhash")
                if (shouldShow) {
                    viewModelScope.launch(Dispatchers.Main) {
                        val dedupKey = "status:$event"
                        if (notifiedStatusEvents.contains(dedupKey)) return@launch
                        notifiedStatusEvents.add(dedupKey)
                        messageManager.addMessage(
                            BitchatMessage(
                                sender = "system",
                                content = "payment status: $event",
                                timestamp = java.util.Date(),
                                isRelay = false
                            )
                        )
                    }
                }
            }

            // Wire up message notarization service
            notarizationService = solanaEntryPoint.messageNotarizationService()

            // Wire up NFT avatar service
            nftAvatarService = solanaEntryPoint.nftAvatarService()

            // Restore persisted NFT profile mint selection
            val nftPrefs = getApplication<android.app.Application>()
                .getSharedPreferences("nft_profile", android.content.Context.MODE_PRIVATE)
            meshService.nftProfileMint = nftPrefs.getString("nft_profile_mint", null)

            // Observe transaction status changes and show updates in chat
            observeTransactionStatus(solanaEntryPoint)
            observeIncomingBalanceCredits(solanaEntryPoint)
        } catch (e: Exception) {
            Log.w(TAG, "Solana services not available: ${e.message}")
        }

        startChannelRolePolicySyncLoop()
        meshService.onChannelRolePolicyReceived = { fromPeer, payload ->
            viewModelScope.launch {
                handleIncomingChannelRolePolicy(fromPeer, payload)
            }
        }

        // Wire up Feed service via Hilt EntryPoint
        try {
            val feedEntryPoint = EntryPointAccessors.fromApplication(
                getApplication(), com.bitchat.android.di.FeedEntryPoint::class.java
            )
            feedService = feedEntryPoint.feedService()
            meshService.feedService = feedService

            feedService?.onBroadcastFeedPost = { payload ->
                meshService.broadcastFeedPost(payload)
            }
            feedService?.onBroadcastFeedReaction = { payload ->
                meshService.broadcastFeedReaction(payload)
            }
            feedService?.onBroadcastFeedReply = { payload ->
                meshService.broadcastFeedReply(payload)
            }

            // Observe feed posts from Room
            viewModelScope.launch {
                feedService?.observePosts()?.collect { posts ->
                    state.postFeedPosts(posts)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Feed service not available: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        tokenGateRevalidationJob?.cancel()
        tokenGatePolicySyncJob?.cancel()
        channelRolePolicySyncJob?.cancel()
        walletLinkAnnounceJob?.cancel()
        tokenGateDenyStrikes.clear()
        meshService.onTokenGatePolicyReceived = null
        meshService.onChannelRolePolicyReceived = null
        meshService.verifyOwnershipProofsOnline = null
        // Note: Mesh service lifecycle is now managed by MainActivity
    }

    private fun startTokenGateRevalidationLoop(tokenGateService: com.bitchat.android.solana.TokenGateService) {
        tokenGateRevalidationJob?.cancel()
        tokenGateRevalidationJob = viewModelScope.launch {
            while (isActive) {
                runCatching {
                    revalidateJoinedTokenGatedChannels(tokenGateService)
                }.onFailure { error ->
                    Log.d(TAG, "Token gate revalidation skipped: ${error.message}")
                }
                delay(TOKEN_GATE_REVALIDATION_INTERVAL_MS)
            }
        }
    }

    private suspend fun revalidateJoinedTokenGatedChannels(tokenGateService: com.bitchat.android.solana.TokenGateService) {
        val joinedSnapshot = state.getJoinedChannelsValue().toList()
        if (joinedSnapshot.isEmpty()) return

        for (channelKey in joinedSnapshot) {
            val isGated = runCatching { tokenGateService.isTokenGated(channelKey) }.getOrDefault(false)
            if (!isGated) continue

            val validation = tokenGateService.validateEligibility(
                channelKey = channelKey,
                mode = com.bitchat.android.solana.ValidationMode.STRICT_ONLINE
            ).getOrNull() ?: continue

            if (validation.decision == com.bitchat.android.solana.GateDecision.DENY) {
                val strikes = (tokenGateDenyStrikes[channelKey] ?: 0) + 1
                tokenGateDenyStrikes[channelKey] = strikes
                if (strikes < TOKEN_GATE_DENY_STRIKES_TO_KICK) continue
            } else {
                tokenGateDenyStrikes.remove(channelKey)
                continue
            }
            if (!state.getJoinedChannelsValue().contains(channelKey)) continue

            channelManager.leaveChannel(channelKey)
            tokenGateDenyStrikes.remove(channelKey)
            val channelTag = ChannelKeys.parseChannelName(channelKey)
            val reason = tokenGateService.formatRequirementText(validation)
            messageManager.addSystemMessage(
                "auto-removed from $channelTag: token gate no longer satisfied ($reason)."
            )
        }
    }

    private fun startTokenGatePolicySyncLoop(tokenGateService: com.bitchat.android.solana.TokenGateService) {
        tokenGatePolicySyncJob?.cancel()
        tokenGatePolicySyncJob = viewModelScope.launch {
            while (isActive) {
                runCatching {
                    val policies = tokenGateService.getAllTokenGates()
                    for (config in policies) {
                        meshService.broadcastTokenGatePolicy(
                            com.bitchat.android.solana.TokenGatePolicyPayload.fromConfig(config)
                        )
                    }
                }.onFailure { error ->
                    Log.d(TAG, "Token gate policy sync skipped: ${error.message}")
                }
                delay(TOKEN_GATE_POLICY_SYNC_INTERVAL_MS)
            }
        }
    }

    private fun startChannelRolePolicySyncLoop() {
        channelRolePolicySyncJob?.cancel()
        channelRolePolicySyncJob = viewModelScope.launch {
            while (isActive) {
                runCatching {
                    val myPeerID = meshService.myPeerID
                    for (channelKey in state.getJoinedChannelsValue()) {
                        if (!channelManager.isChannelAdmin(channelKey, myPeerID)) continue
                        val payload = channelManager.buildChannelRolePolicy(channelKey) ?: continue
                        meshService.broadcastChannelRolePolicy(payload)
                    }
                }.onFailure { error ->
                    Log.d(TAG, "Channel role policy sync skipped: ${error.message}")
                }
                delay(CHANNEL_ROLE_POLICY_SYNC_INTERVAL_MS)
            }
        }
    }

    private fun handleIncomingChannelRolePolicy(fromPeer: String, payload: ChannelRolePolicyPayload) {
        val applied = channelManager.applySyncedRolePolicy(
            senderPeerID = fromPeer,
            payload = payload
        )
        if (!applied) return

        val channelTag = ChannelKeys.parseChannelName(payload.channelKey)
        messageManager.addSystemMessage("role sync applied for $channelTag (v${payload.roleVersion}).")
    }
    
    // MARK: - Nickname Management
    
    fun setNickname(newNickname: String) {
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        meshService.sendBroadcastAnnounce()
    }
    
    /**
     * Ensure Nostr DM subscription for a geohash conversation key if known
     * Minimal-change approach: reflectively access GeohashViewModel internals to reuse pipeline
     */
    private fun ensureGeohashDMSubscriptionIfNeeded(convKey: String) {
        try {
            val repoField = GeohashViewModel::class.java.getDeclaredField("repo")
            repoField.isAccessible = true
            val repo = repoField.get(geohashViewModel) as com.bitchat.android.nostr.GeohashRepository
            val gh = repo.getConversationGeohash(convKey)
            if (!gh.isNullOrEmpty()) {
                val subMgrField = GeohashViewModel::class.java.getDeclaredField("subscriptionManager")
                subMgrField.isAccessible = true
                val subMgr = subMgrField.get(geohashViewModel) as com.bitchat.android.nostr.NostrSubscriptionManager
                val identity = com.bitchat.android.nostr.NostrIdentityBridge.deriveIdentity(gh, getApplication())
                val subId = "geo-dm-$gh"
                val currentDmSubField = GeohashViewModel::class.java.getDeclaredField("currentDmSubId")
                currentDmSubField.isAccessible = true
                val currentId = currentDmSubField.get(geohashViewModel) as String?
                if (currentId != subId) {
                    (currentId)?.let { subMgr.unsubscribe(it) }
                    currentDmSubField.set(geohashViewModel, subId)
                    subMgr.subscribeGiftWraps(
                        pubkey = identity.publicKeyHex,
                        sinceMs = System.currentTimeMillis() - 172800000L,
                        id = subId,
                        handler = { event ->
                            val dmHandlerField = GeohashViewModel::class.java.getDeclaredField("dmHandler")
                            dmHandlerField.isAccessible = true
                            val dmHandler = dmHandlerField.get(geohashViewModel) as com.bitchat.android.nostr.NostrDirectMessageHandler
                            dmHandler.onGiftWrap(event, gh, identity)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureGeohashDMSubscriptionIfNeeded failed: ${e.message}")
        }
    }

    // MARK: - Channel Management (delegated)
    
    fun joinChannel(channel: String, password: String? = null): Boolean {
        // Get current timeline to ensure channel is scoped correctly
        val timeline = state.selectedLocationChannel.value
        return channelManager.joinChannel(channel, password, meshService.myPeerID, timeline)
    }
    
    fun switchToChannel(channel: String?) {
        channelManager.switchToChannel(channel)
    }

    /**
     * Switch to a channel and automatically update timeline context.
     * For geo channels, switches to the appropriate geohash timeline.
     * For mesh channels, switches to mesh timeline.
     */
    fun switchToChannelWithTimelineContext(channelKey: String) {
        // First switch timeline based on channel key
        when {
            ChannelKeys.isGeo(channelKey) -> {
                val geohash = ChannelKeys.parseGeohash(channelKey)
                if (geohash != null) {
                    val level = ChannelKeys.levelForGeohashLength(geohash.length)
                    val geohashChannel = com.bitchat.android.geohash.GeohashChannel(level, geohash)
                    selectLocationChannel(com.bitchat.android.geohash.ChannelID.Location(geohashChannel))
                }
            }
            ChannelKeys.isMesh(channelKey) -> {
                selectLocationChannel(com.bitchat.android.geohash.ChannelID.Mesh)
            }
        }

        // Then switch to the channel
        switchToChannel(channelKey)
    }
    
    fun leaveChannel(channel: String) {
        channelManager.leaveChannel(channel)
        meshService.sendMessage("left $channel")
    }
    
    // MARK: - Private Chat Management (delegated)
    
    fun startPrivateChat(peerID: String) {
        // For geohash conversation keys, ensure DM subscription is active
        if (peerID.startsWith("nostr_")) {
            ensureGeohashDMSubscriptionIfNeeded(peerID)
        }
        
        val success = privateChatManager.startPrivateChat(peerID, meshService)
        if (success) {
            // Notify notification manager about current private chat
            setCurrentPrivateChatPeer(peerID)
            // Clear notifications for this sender since user is now viewing the chat
            clearNotificationsForSender(peerID)

            // Persistently mark all messages in this conversation as read so Nostr fetches
            // after app restarts won't re-mark them as unread.
            try {
                val seen = com.bitchat.android.services.SeenMessageStore.getInstance(getApplication())
                val chats = state.getPrivateChatsValue()
                val messages = chats[peerID] ?: emptyList()
                messages.forEach { msg ->
                    try { seen.markRead(msg.id) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }
    
    fun endPrivateChat() {
        privateChatManager.endPrivateChat()
        // Notify notification manager that no private chat is active
        setCurrentPrivateChatPeer(null)
        // Clear mesh mention notifications since user is now back in mesh chat
        clearMeshMentionNotifications()
    }

    // MARK: - Open Latest Unread Private Chat

    fun openLatestUnreadPrivateChat() {
        try {
            val unreadKeys = state.getUnreadPrivateMessagesValue()
            if (unreadKeys.isEmpty()) return

            val me = state.getNicknameValue() ?: meshService.myPeerID
            val chats = state.getPrivateChatsValue()

            // Pick the latest incoming message among unread conversations
            var bestKey: String? = null
            var bestTime: Long = Long.MIN_VALUE

            unreadKeys.forEach { key ->
                val list = chats[key]
                if (!list.isNullOrEmpty()) {
                    // Prefer the latest incoming message (sender != me), fallback to last message
                    val latestIncoming = list.lastOrNull { it.sender != me }
                    val candidateTime = (latestIncoming ?: list.last()).timestamp.time
                    if (candidateTime > bestTime) {
                        bestTime = candidateTime
                        bestKey = key
                    }
                }
            }

            val targetKey = bestKey ?: unreadKeys.firstOrNull() ?: return

            val openPeer: String = if (targetKey.startsWith("nostr_")) {
                // Use the exact conversation key for geohash DMs and ensure DM subscription
                ensureGeohashDMSubscriptionIfNeeded(targetKey)
                targetKey
            } else {
                // Resolve to a canonical mesh peer if needed
                val canonical = com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                    selectedPeerID = targetKey,
                    connectedPeers = state.getConnectedPeersValue(),
                    meshNoiseKeyForPeer = { pid -> meshService.getPeerInfo(pid)?.noisePublicKey },
                    meshHasPeer = { pid -> meshService.getPeerInfo(pid)?.isConnected == true },
                    nostrPubHexForAlias = { alias -> com.bitchat.android.nostr.GeohashAliasRegistry.get(alias) },
                    findNoiseKeyForNostr = { key -> com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
                )
                canonical ?: targetKey
            }

            startPrivateChat(openPeer)

            // If sidebar visible, hide it to focus on the private chat
            if (state.getShowSidebarValue()) {
                state.setShowSidebar(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "openLatestUnreadPrivateChat failed: ${e.message}")
        }
    }

    // END - Open Latest Unread Private Chat

    
    // MARK: - Message Sending
    
    fun sendMessage(content: String): CommandResult? {
        if (content.isEmpty()) return null

        // Check for commands
        if (content.startsWith("/")) {
            val selectedLocationForCommand = state.selectedLocationChannel.value
            return commandProcessor.processCommand(content, meshService, meshService.myPeerID, { messageContent, mentions, channel ->
                if (selectedLocationForCommand is com.bitchat.android.geohash.ChannelID.Location) {
                    // Route command-generated public messages via Nostr in geohash channels
                    geohashViewModel.sendGeohashMessage(
                        messageContent,
                        selectedLocationForCommand.channel,
                        meshService.myPeerID,
                        state.getNicknameValue()
                    )
                } else {
                    // Default: route via mesh
                    meshService.sendMessage(messageContent, mentions, channel)
                }
            }, this)
        }
        
        val mentions = messageManager.parseMentions(content, meshService.getPeerNicknames().values.toSet(), state.getNicknameValue())
        // REMOVED: Auto-join mentioned channels feature that was incorrectly parsing hashtags from @mentions
        // This was causing messages like "test @jack#1234 test" to auto-join channel "#1234"
        
        var selectedPeer = state.getSelectedPrivateChatPeerValue()
        val currentChannelValue = state.getCurrentChannelValue()
        
        if (selectedPeer != null) {
            // If the selected peer is a temporary Nostr alias or a noise-hex identity, resolve to a canonical target
            selectedPeer = com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                selectedPeerID = selectedPeer,
                connectedPeers = state.getConnectedPeersValue(),
                meshNoiseKeyForPeer = { pid -> meshService.getPeerInfo(pid)?.noisePublicKey },
                meshHasPeer = { pid -> meshService.getPeerInfo(pid)?.isConnected == true },
                nostrPubHexForAlias = { alias -> com.bitchat.android.nostr.GeohashAliasRegistry.get(alias) },
                findNoiseKeyForNostr = { key -> com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
            ).also { canonical ->
                if (canonical != state.getSelectedPrivateChatPeerValue()) {
                    privateChatManager.startPrivateChat(canonical, meshService)
                }
            }
            // Send private message
            val recipientNickname = meshService.getPeerNicknames()[selectedPeer]
            privateChatManager.sendPrivateMessage(
                content, 
                selectedPeer, 
                recipientNickname,
                state.getNicknameValue(),
                meshService.myPeerID
            ) { messageContent, peerID, recipientNicknameParam, messageId ->
                // Route via MessageRouter (mesh when connected+established, else Nostr)
                val router = com.bitchat.android.services.MessageRouter.getInstance(getApplication(), meshService)
                router.sendPrivate(messageContent, peerID, recipientNicknameParam, messageId)
            }
        } else {
            // Check if we're in a location channel
            val selectedLocationChannel = state.selectedLocationChannel.value
            if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                // Determine message content - prepend channel name if in a channel
                val messageContent = if (currentChannelValue != null && ChannelKeys.isGeo(currentChannelValue)) {
                    val channelName = ChannelKeys.parseChannelName(currentChannelValue)
                    "$channelName $content"
                } else {
                    content
                }

                // Send to geohash channel via Nostr ephemeral event
                geohashViewModel.sendGeohashMessage(messageContent, selectedLocationChannel.channel, meshService.myPeerID, state.getNicknameValue())
            } else {
                // Send public/channel message via mesh
                val message = BitchatMessage(
                    sender = state.getNicknameValue() ?: meshService.myPeerID,
                    content = content,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = meshService.myPeerID,
                    mentions = if (mentions.isNotEmpty()) mentions else null,
                    channel = currentChannelValue
                )

                if (currentChannelValue != null) {
                    // For channel messages, prepend channel name to content for wire format
                    val channelName = ChannelKeys.parseChannelName(currentChannelValue)
                    val wireContent = "$channelName $content"

                    channelManager.addChannelMessage(currentChannelValue, message, meshService.myPeerID)

                    // Check if encrypted channel
                    if (channelManager.hasChannelKey(currentChannelValue)) {
                        channelManager.sendEncryptedChannelMessage(
                            wireContent,
                            mentions,
                            currentChannelValue,
                            state.getNicknameValue(),
                            meshService.myPeerID,
                            onEncryptedPayload = { encryptedData ->
                                // This would need proper mesh service integration
                                meshService.sendMessage(wireContent, mentions, null)
                            },
                            onFallback = {
                                meshService.sendMessage(wireContent, mentions, null)
                            }
                        )
                    } else {
                        meshService.sendMessage(wireContent, mentions, null)
                    }
                } else {
                    messageManager.addMessage(message)
                    meshService.sendMessage(content, mentions, null)
                }
            }
        }
        return null
    }

    // MARK: - Utility Functions

    fun getPeerIDForNickname(nickname: String): String? {
        val displayMatch = (state.peerNicknames.value ?: emptyMap()).entries
            .firstOrNull { it.value == nickname }
            ?.key
        if (displayMatch != null) return displayMatch

        val base = nickname.substringBefore("#")
        return meshService.getPeerNicknames().entries.find { it.value == base }?.key
    }
    
    fun toggleFavorite(peerID: String) {
        Log.d("ChatViewModel", "toggleFavorite called for peerID: $peerID")
        privateChatManager.toggleFavorite(peerID)

        // Persist relationship in FavoritesPersistenceService
        try {
            var noiseKey: ByteArray? = null
            var nickname: String = meshService.getPeerNicknames()[peerID] ?: peerID

            // Case 1: Live mesh peer with known info
            val peerInfo = meshService.getPeerInfo(peerID)
            if (peerInfo?.noisePublicKey != null) {
                noiseKey = peerInfo.noisePublicKey
                nickname = peerInfo.nickname
            } else {
                // Case 2: Offline favorite entry using 64-hex noise public key as peerID
                if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                    try {
                        noiseKey = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        // Prefer nickname from favorites store if available
                        val rel = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey!!)
                        if (rel != null) nickname = rel.peerNickname
                    } catch (_: Exception) { }
                }
            }

            if (noiseKey != null) {
                // Determine current favorite state from DataManager using fingerprint
                val identityManager = com.bitchat.android.identity.SecureIdentityStateManager(getApplication())
                val fingerprint = identityManager.generateFingerprint(noiseKey!!)
                val isNowFavorite = dataManager.favoritePeers.contains(fingerprint)

                com.bitchat.android.favorites.FavoritesPersistenceService.shared.updateFavoriteStatus(
                    noisePublicKey = noiseKey!!,
                    nickname = nickname,
                    isFavorite = isNowFavorite
                )

                // Send favorite notification via mesh or Nostr with our npub if available
                try {
                    val myNostr = com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(getApplication())
                    val announcementContent = if (isNowFavorite) "[FAVORITED]:${myNostr?.npub ?: ""}" else "[UNFAVORITED]:${myNostr?.npub ?: ""}"
                    // Prefer mesh if session established, else try Nostr
                    if (meshService.hasEstablishedSession(peerID)) {
                        // Reuse existing private message path for notifications
                        meshService.sendPrivateMessage(
                            announcementContent,
                            peerID,
                            nickname,
                            java.util.UUID.randomUUID().toString()
                        )
                    } else {
                        val nostrTransport = com.bitchat.android.nostr.NostrTransport.getInstance(getApplication())
                        nostrTransport.senderPeerID = meshService.myPeerID
                        nostrTransport.sendFavoriteNotification(peerID, isNowFavorite)
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // Log current state after toggle
        logCurrentFavoriteState()
    }
    
    private fun logCurrentFavoriteState() {
        Log.i("ChatViewModel", "=== CURRENT FAVORITE STATE ===")
        Log.i("ChatViewModel", "LiveData favorite peers: ${favoritePeers.value}")
        Log.i("ChatViewModel", "DataManager favorite peers: ${dataManager.favoritePeers}")
        Log.i("ChatViewModel", "Peer fingerprints: ${privateChatManager.getAllPeerFingerprints()}")
        Log.i("ChatViewModel", "==============================")
    }
    
    /**
     * Initialize session state monitoring for reactive UI updates
     */
    private fun initializeSessionStateMonitoring() {
        viewModelScope.launch {
            while (true) {
                delay(1000) // Check session states every second
                updateReactiveStates()
            }
        }
    }
    
    // Location notes subscription management moved to LocationNotesViewModelExtensions.kt
    
    /**
     * Update reactive states for all connected peers (session states, fingerprints, nicknames, RSSI)
     */
    private fun updateReactiveStates() {
        val currentPeers = state.getConnectedPeersValue()
        
        // Update session states
        val prevStates = state.getPeerSessionStatesValue()
        val sessionStates = currentPeers.associateWith { peerID ->
            meshService.getSessionState(peerID).toString()
        }
        state.setPeerSessionStates(sessionStates)
        // Detect new established sessions and flush router outbox for them and their noiseHex aliases
        sessionStates.forEach { (peerID, newState) ->
            val old = prevStates[peerID]
            if (old != "established" && newState == "established") {
                com.bitchat.android.services.MessageRouter
                    .getInstance(getApplication(), meshService)
                    .onSessionEstablished(peerID)
            }
        }
        // Update fingerprint mappings from centralized manager
        val fingerprints = privateChatManager.getAllPeerFingerprints()
        state.setPeerFingerprints(fingerprints)

        val nicknames = meshService.getPeerNicknames()
        state.setPeerNicknames(buildCollisionAwarePeerNicknames(nicknames, currentPeers))

        val rssiValues = meshService.getPeerRSSI()
        state.setPeerRSSI(rssiValues)

        // Update directness per peer (driven by PeerManager state)
        try {
            val directMap = state.getConnectedPeersValue().associateWith { pid ->
                meshService.getPeerInfo(pid)?.isDirectConnection == true
            }
            state.setPeerDirect(directMap)
        } catch (_: Exception) { }
    }

    // MARK: - Debug and Troubleshooting
    
    fun getDebugStatus(): String {
        return meshService.getDebugStatus()
    }
    
    // Note: Mesh service restart is now handled by MainActivity
    // This function is no longer needed
    
    fun setAppBackgroundState(inBackground: Boolean) {
        // Forward to notification manager for notification logic
        notificationManager.setAppBackgroundState(inBackground)
    }
    
    fun setCurrentPrivateChatPeer(peerID: String?) {
        // Update notification manager with current private chat peer
        notificationManager.setCurrentPrivateChatPeer(peerID)
    }
    
    fun setCurrentGeohash(geohash: String?) {
        // Update notification manager with current geohash for notification logic
        notificationManager.setCurrentGeohash(geohash)
    }

    fun clearNotificationsForSender(peerID: String) {
        // Clear notifications when user opens a chat
        notificationManager.clearNotificationsForSender(peerID)
    }
    
    fun clearNotificationsForGeohash(geohash: String) {
        // Clear notifications when user opens a geohash chat
        notificationManager.clearNotificationsForGeohash(geohash)
    }

    /**
     * Clear mesh mention notifications when user opens mesh chat
     */
    fun clearMeshMentionNotifications() {
        notificationManager.clearMeshMentionNotifications()
    }

    // MARK: - Command Autocomplete (delegated)
    
    fun updateCommandSuggestions(input: String) {
        commandProcessor.updateCommandSuggestions(input, meshService.myPeerID)
    }
    
    fun selectCommandSuggestion(suggestion: CommandSuggestion): CommandResult {
        return commandProcessor.selectCommandSuggestion(suggestion)
    }
    
    // MARK: - Mention Autocomplete
    
    fun updateMentionSuggestions(input: String) {
        commandProcessor.updateMentionSuggestions(input, meshService, this)
    }
    
    fun selectMentionSuggestion(nickname: String, currentText: String): String {
        return commandProcessor.selectMentionSuggestion(nickname, currentText)
    }
    
    // MARK: - BluetoothMeshDelegate Implementation (delegated)
    
    override fun didReceiveMessage(message: BitchatMessage) {
        meshDelegateHandler.didReceiveMessage(message)
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        meshDelegateHandler.didUpdatePeerList(peers)
        // When new peers connect, try to broadcast any pending Solana transactions via mesh relay
        if (peers.isNotEmpty()) {
            solanaPaymentManager?.tryBroadcastPending()
            broadcastCurrentChannelRoleSnapshots()
        }
    }
    
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        meshDelegateHandler.didReceiveChannelLeave(channel, fromPeer)
    }
    
    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveDeliveryAck(messageID, recipientPeerID)
    }
    
    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveReadReceipt(messageID, recipientPeerID)
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return meshDelegateHandler.decryptChannelMessage(encryptedContent, channel)
    }
    
    override fun getNickname(): String? {
        return meshDelegateHandler.getNickname()
    }
    
    override fun isFavorite(peerID: String): Boolean {
        return meshDelegateHandler.isFavorite(peerID)
    }

    private fun broadcastCurrentChannelRoleSnapshots() {
        val myPeerID = meshService.myPeerID
        for (channelKey in state.getJoinedChannelsValue()) {
            if (!channelManager.isChannelAdmin(channelKey, myPeerID)) continue
            val payload = channelManager.buildChannelRolePolicy(channelKey) ?: continue
            meshService.broadcastChannelRolePolicy(payload)
        }
    }
    
    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager
    
    // MARK: - Emergency Clear
    
    fun panicClearAllData() {
        Log.w(TAG, "🚨 PANIC MODE ACTIVATED - Clearing all sensitive data")
        
        // Clear all UI managers
        messageManager.clearAllMessages()
        channelManager.clearAllChannels()
        privateChatManager.clearAllPrivateChats()
        dataManager.clearAllData()
        
        // Clear all mesh service data
        clearAllMeshServiceData()
        
        // Clear all cryptographic data
        clearAllCryptographicData()
        
        // Clear all notifications
        notificationManager.clearAllNotifications()
        
        // Clear Nostr/geohash state, keys, connections, bookmarks, and reinitialize from scratch
        try {
            // Clear geohash bookmarks too (panic should remove everything)
            try {
                val store = com.bitchat.android.geohash.GeohashBookmarksStore.getInstance(getApplication())
                store.clearAll()
            } catch (_: Exception) { }

            geohashViewModel.panicReset()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset Nostr/geohash: ${e.message}")
        }

        // Reset nickname
        val newNickname = "anon${Random.nextInt(1000, 9999)}"
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        
        Log.w(TAG, "🚨 PANIC MODE COMPLETED - All sensitive data cleared")
        
        // Note: Mesh service restart is now handled by MainActivity
        // This method now only clears data, not mesh service lifecycle
    }
    
    /**
     * Clear all mesh service related data
     */
    private fun clearAllMeshServiceData() {
        try {
            // Request mesh service to clear all its internal data
            meshService.clearAllInternalData()
            
            Log.d(TAG, "✅ Cleared all mesh service data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing mesh service data: ${e.message}")
        }
    }
    
    /**
     * Clear all cryptographic data including persistent identity
     */
    private fun clearAllCryptographicData() {
        try {
            // Clear encryption service persistent identity (Ed25519 signing keys)
            meshService.clearAllEncryptionData()
            
            // Clear secure identity state (if used)
            try {
                val identityManager = com.bitchat.android.identity.SecureIdentityStateManager(getApplication())
                identityManager.clearIdentityData()
                // Also clear secure values used by FavoritesPersistenceService (favorites + peerID index)
                try {
                    identityManager.clearSecureValues("favorite_relationships", "favorite_peerid_index")
                } catch (_: Exception) { }
                Log.d(TAG, "✅ Cleared secure identity state and secure favorites store")
            } catch (e: Exception) {
                Log.d(TAG, "SecureIdentityStateManager not available or already cleared: ${e.message}")
            }

            // Clear FavoritesPersistenceService persistent relationships
            try {
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.clearAllFavorites()
                Log.d(TAG, "✅ Cleared FavoritesPersistenceService relationships")
            } catch (_: Exception) { }
            
            Log.d(TAG, "✅ Cleared all cryptographic data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing cryptographic data: ${e.message}")
        }
    }

    /**
     * Get participant count for a specific geohash (5-minute activity window)
     */
    fun geohashParticipantCount(geohash: String): Int {
        return geohashViewModel.geohashParticipantCount(geohash)
    }

    /**
     * Begin sampling multiple geohashes for participant activity
     */
    fun beginGeohashSampling(geohashes: List<String>) {
        geohashViewModel.beginGeohashSampling(geohashes)
    }

    /**
     * End geohash sampling
     */
    fun endGeohashSampling() {
        // No-op in refactored architecture; sampling subscriptions are short-lived
    }

    /**
     * Check if a geohash person is teleported (iOS-compatible)
     */
    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return geohashViewModel.isPersonTeleported(pubkeyHex)
    }

    /**
     * Start geohash DM with pubkey hex (iOS-compatible)
     */
    fun startGeohashDM(pubkeyHex: String) {
        geohashViewModel.startGeohashDM(pubkeyHex) { convKey ->
            startPrivateChat(convKey)
        }
    }

    fun selectLocationChannel(channel: com.bitchat.android.geohash.ChannelID) {
        geohashViewModel.selectLocationChannel(channel)
    }

    /**
     * Block a user in geohash channels by their nickname
     */
    fun blockUserInGeohash(targetNickname: String) {
        geohashViewModel.blockUserInGeohash(targetNickname)
    }

    /**
     * Look up a peer's Solana address by nickname.
     */
    fun getPeerSolanaAddress(nickname: String): String? {
        return when (val resolution = resolveTipRecipient(nickname)) {
            is TipRecipientResolution.Unique -> resolution.address
            else -> null
        }
    }

    sealed class TipRecipientResolution {
        data class Unique(val nickname: String, val peerID: String, val address: String) : TipRecipientResolution()
        data class Ambiguous(val nickname: String, val options: List<String>) : TipRecipientResolution()
        data object NotFound : TipRecipientResolution()
    }

    fun resolveTipRecipient(rawTarget: String): TipRecipientResolution {
        val target = rawTarget.removePrefix("@").trim()
        if (target.isBlank()) return TipRecipientResolution.NotFound

        val parts = target.split("#", limit = 2)
        val nickname = parts[0]
        val explicitTag = parts.getOrNull(1)?.uppercase()

        val candidates = getWalletCandidatesForNickname(nickname)
        if (candidates.isEmpty()) return TipRecipientResolution.NotFound

        if (explicitTag != null) {
            val matched = candidates.firstOrNull { it.tag == explicitTag }
            if (matched == null) {
                // Tag can become stale shortly after re-announce or collision-length expansion.
                return if (candidates.size == 1) {
                    val only = candidates.first()
                    TipRecipientResolution.Unique(
                        nickname = nickname,
                        peerID = only.peerID,
                        address = only.address
                    )
                } else {
                    TipRecipientResolution.Ambiguous(
                        nickname = nickname,
                        options = candidates.map { "${nickname}#${it.tag}" }
                    )
                }
            }
            return TipRecipientResolution.Unique(
                nickname = nickname,
                peerID = matched.peerID,
                address = matched.address
            )
        }

        if (candidates.size == 1) {
            val only = candidates.first()
            return TipRecipientResolution.Unique(
                nickname = nickname,
                peerID = only.peerID,
                address = only.address
            )
        }

        return TipRecipientResolution.Ambiguous(
            nickname = nickname,
            options = candidates.map { "${nickname}#${it.tag}" }
        )
    }

    /**
     * Get a peer's verified Solana ownership proofs by nickname.
     */
    fun getPeerOwnershipProofs(nickname: String): List<com.bitchat.android.model.SolanaOwnershipProof> {
        val peerID = getMostRecentPeerIdForNickname(nickname) ?: return emptyList()
        return meshService.getPeerInfo(peerID)?.solanaOwnershipProofs ?: emptyList()
    }

    /**
     * Look up a peer's NFT profile mint by nickname.
     */
    fun getPeerNftProfileMint(nickname: String): String? {
        val peerID = getMostRecentPeerIdForNickname(nickname) ?: return null
        return meshService.getPeerInfo(peerID)?.nftProfileMint
    }

    /**
     * Fetch NFT avatar bitmap for a peer. Returns null if peer has no NFT profile
     * or if the image is unavailable.
     */
    suspend fun getPeerNftAvatar(nickname: String): android.graphics.Bitmap? {
        val peerID = getMostRecentPeerIdForNickname(nickname) ?: return null
        val peerInfo = meshService.getPeerInfo(peerID) ?: return null
        val mint = peerInfo.nftProfileMint ?: return null
        val owner = peerInfo.solanaAddress ?: return null
        return nftAvatarService?.getAvatar(mint, owner)
    }

    /**
     * Set the NFT mint address for our profile avatar.
     * Persists the selection and triggers a re-announce to propagate.
     */
    fun setNftProfileMint(mintAddress: String?) {
        meshService.nftProfileMint = mintAddress
        val prefs = getApplication<android.app.Application>()
            .getSharedPreferences("nft_profile", android.content.Context.MODE_PRIVATE)
        if (mintAddress != null) {
            prefs.edit().putString("nft_profile_mint", mintAddress).apply()
        } else {
            prefs.edit().remove("nft_profile_mint").apply()
        }
        meshService.sendBroadcastAnnounce()
    }

    /**
     * Get all peers that have a known Solana address.
     * Returns list of (nickname, solanaAddress) pairs.
     */
    fun getPeersWithSolanaAddresses(): List<Pair<String, String>> {
        val nicknames = meshService.getPeerNicknames()
        return nicknames.mapNotNull { (peerID, nickname) ->
            val addr = meshService.getPeerInfo(peerID)?.solanaAddress
            if (addr != null) Pair(nickname, addr) else null
        }
    }

    private fun getMostRecentPeerIdForNickname(nickname: String): String? {
        return getWalletCandidatesForNickname(nickname).firstOrNull()?.peerID
    }

    private data class WalletCandidate(
        val peerID: String,
        val address: String,
        val lastSeen: Long,
        val tag: String
    )

    private fun getWalletCandidatesForNickname(nickname: String): List<WalletCandidate> {
        val nicknames = meshService.getPeerNicknames()
        val baseCandidates = nicknames.entries
            .asSequence()
            .filter { it.value == nickname }
            .mapNotNull { entry ->
                val info = meshService.getPeerInfo(entry.key) ?: return@mapNotNull null
                val address = info.solanaAddress ?: return@mapNotNull null
                WalletCandidate(
                    peerID = entry.key,
                    address = address,
                    lastSeen = info.lastSeen,
                    tag = buildPeerTag(entry.key, 4)
                )
            }
            .sortedByDescending { it.lastSeen }
            .toList()

        val hasTagCollision = baseCandidates
            .map { it.tag }
            .groupingBy { it }
            .eachCount()
            .any { it.value > 1 }

        if (!hasTagCollision) return baseCandidates

        return baseCandidates.map { candidate ->
            candidate.copy(tag = buildPeerTag(candidate.peerID, 6))
        }
    }

    private fun buildPeerTag(peerID: String, length: Int): String {
        val clean = peerID.filter { it.isLetterOrDigit() }
        return clean.takeLast(length).uppercase().ifBlank { "0".repeat(length) }
    }

    private fun buildCollisionAwarePeerNicknames(
        rawNicknames: Map<String, String>,
        connectedPeerIds: List<String>
    ): Map<String, String> {
        if (rawNicknames.isEmpty()) return emptyMap()

        val connectedSet = connectedPeerIds.toSet()
        val connectedEntries = rawNicknames.entries.filter { connectedSet.contains(it.key) }
        val grouped = connectedEntries.groupBy { it.value }
        val decorated = mutableMapOf<String, String>()

        grouped.forEach { (nickname, entries) ->
            if (entries.size <= 1) {
                val only = entries.firstOrNull()
                if (only != null) decorated[only.key] = nickname
                return@forEach
            }

            // Duplicate nicknames in this mesh: suffix with 4 chars; expand to 6 only on collision.
            val fourTags = entries.associate { it.key to buildPeerTag(it.key, 4) }
            val hasTagCollision = fourTags.values.groupingBy { it }.eachCount().any { it.value > 1 }
            val tags = if (hasTagCollision) {
                entries.associate { it.key to buildPeerTag(it.key, 6) }
            } else {
                fourTags
            }

            entries.forEach { entry ->
                val suffix = tags[entry.key] ?: buildPeerTag(entry.key, if (hasTagCollision) 6 else 4)
                decorated[entry.key] = "$nickname#$suffix"
            }
        }

        // Preserve non-connected peers as plain names for fallback callers.
        rawNicknames.forEach { (peerID, nickname) ->
            if (!decorated.containsKey(peerID)) {
                decorated[peerID] = nickname
            }
        }

        return decorated
    }

    // MARK: - Message Notarization

    /**
     * Notarize a message on the Solana blockchain.
     * Returns the SHA-256 hash on success.
     */
    fun notarizeMessage(message: BitchatMessage) {
        val service = notarizationService ?: run {
            messageManager.addMessage(BitchatMessage(
                sender = "system",
                content = "wallet required for notarization",
                timestamp = java.util.Date(),
                isRelay = false
            ))
            return
        }
        viewModelScope.launch {
            val result = service.queueNotarization(message)
            result.onSuccess { hash ->
                messageManager.addMessage(BitchatMessage(
                    sender = "system",
                    content = "message queued for notarization\nhash: ${hash.take(16)}...",
                    timestamp = java.util.Date(),
                    isRelay = false
                ))
            }
            result.onFailure { error ->
                messageManager.addMessage(BitchatMessage(
                    sender = "system",
                    content = "notarization failed: ${error.message}",
                    timestamp = java.util.Date(),
                    isRelay = false
                ))
            }
        }
    }

    fun inspectNotarization(message: BitchatMessage) {
        val service = notarizationService ?: run {
            messageManager.addSystemMessage("wallet required for notarization")
            return
        }
        viewModelScope.launch {
            val proof = service.getProof(message.id, refreshMetadata = true)
            if (proof == null) {
                messageManager.addSystemMessage("notarization: no record for this message")
                return@launch
            }
            val details = when (proof.status) {
                com.bitchat.android.data.local.entities.NotarizationStatus.CONFIRMED -> {
                    val tx = proof.txSignature?.take(12)?.plus("...") ?: "n/a"
                    val slot = proof.slot?.toString() ?: "n/a"
                    val blockTime = proof.blockTime?.let { java.util.Date(it * 1000).toString() } ?: "n/a"
                    "notarization confirmed\nhash: ${proof.messageHash.take(16)}...\ntx: $tx\nslot: $slot\nblock time: $blockTime"
                }
                com.bitchat.android.data.local.entities.NotarizationStatus.BROADCASTING ->
                    "notarization pending confirmation\nhash: ${proof.messageHash.take(16)}..."
                com.bitchat.android.data.local.entities.NotarizationStatus.QUEUED ->
                    "notarization queued (waiting for connectivity)\nhash: ${proof.messageHash.take(16)}..."
                com.bitchat.android.data.local.entities.NotarizationStatus.FAILED ->
                    "notarization failed: ${proof.errorMessage ?: "unknown error"}"
                else ->
                    "notarization status: ${proof.status.lowercase()}"
            }
            messageManager.addSystemMessage(details)
        }
    }

    fun processNotarizationQueueNow() {
        val service = notarizationService ?: run {
            messageManager.addSystemMessage("wallet required for notarization")
            return
        }
        viewModelScope.launch {
            val result = service.processBatch()
            result.onSuccess { count ->
                messageManager.addSystemMessage(
                    if (count > 0) "processed notarization batch ($count messages)"
                    else "no queued notarizations to process"
                )
            }
            result.onFailure { error ->
                messageManager.addSystemMessage("notarization processing failed: ${error.message}")
            }
        }
    }

    fun retryFailedNotarizations() {
        val service = notarizationService ?: run {
            messageManager.addSystemMessage("wallet required for notarization")
            return
        }
        viewModelScope.launch {
            service.retryFailed()
            messageManager.addSystemMessage("retrying failed notarizations")
        }
    }

    /**
     * Check if a message has been notarized and get the proof.
     */
    suspend fun getNotarizationProof(messageId: String): com.bitchat.android.data.local.entities.MessageNotarizationEntity? {
        return notarizationService?.getProof(messageId, refreshMetadata = true)
    }

    // MARK: - Social Feed

    fun selectTab(tab: String) {
        state.setSelectedTab(tab)
    }

    fun createFeedPost(content: String, imageBytes: ByteArray?) {
        val service = feedService ?: return
        viewModelScope.launch {
            service.createPost(
                content, imageBytes,
                meshService.myPeerID,
                state.getNicknameValue() ?: meshService.myPeerID,
                getApplication()
            )
        }
    }

    fun toggleFeedReaction(postId: String, emoji: String) {
        val service = feedService ?: return
        viewModelScope.launch {
            service.toggleReaction(
                postId, emoji,
                meshService.myPeerID,
                state.getNicknameValue() ?: meshService.myPeerID
            )
            refreshReactionsForPost(postId)
        }
    }

    fun createFeedReply(parentPostId: String, content: String) {
        val service = feedService ?: return
        viewModelScope.launch {
            service.createReply(
                parentPostId, content,
                meshService.myPeerID,
                state.getNicknameValue() ?: meshService.myPeerID
            )
            refreshRepliesForPost(parentPostId)
        }
    }

    fun expandPost(postId: String?) {
        state.setExpandedPostId(postId)
        if (postId != null) {
            viewModelScope.launch {
                refreshReactionsForPost(postId)
                refreshRepliesForPost(postId)
            }
        }
    }

    fun showNewPostComposer() { state.setShowNewPostComposer(true) }
    fun hideNewPostComposer() { state.setShowNewPostComposer(false) }

    private suspend fun refreshReactionsForPost(postId: String) {
        try {
            val service = feedService ?: return
            val reactions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                service.observeReactions(postId)
            }
            reactions.collect { reactionList ->
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    val current = state.getFeedReactionsValue().toMutableMap()
                    current[postId] = reactionList
                    state.setFeedReactions(current)
                }
            }
        } catch (_: Exception) { }
    }

    private suspend fun refreshRepliesForPost(postId: String) {
        try {
            val service = feedService ?: return
            val replies = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                service.observeReplies(postId)
            }
            replies.collect { replyList ->
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    val current = state.getFeedRepliesValue().toMutableMap()
                    current[postId] = replyList
                    state.setFeedReplies(current)
                }
            }
        } catch (_: Exception) { }
    }

    // MARK: - Solana Transaction Status Observer

    /**
     * Track confirmed/failed transaction IDs to avoid duplicate system messages.
     */
    private val notifiedTransactionIds = mutableSetOf<String>()
    private val notifiedStatusEvents = mutableSetOf<String>()
    private var lastObservedWalletAddress: String? = null
    private var lastObservedWalletLamports: Long? = null
    private val notifiedIncomingCreditKeys = mutableSetOf<String>()

    private fun observeTransactionStatus(entryPoint: SolanaEntryPoint) {
        val paymentManager = entryPoint.solanaPaymentManager()
        val myWalletAddress = entryPoint.solanaWalletService().getPublicKeyBase58()
        viewModelScope.launch {
            paymentManager.observeRecentTransactions()
                .collect { transactions ->
                    for (tx in transactions) {
                        val key = "${tx.id}:${tx.status}"
                        if (notifiedTransactionIds.contains(key)) continue

                        val amountSol = paymentManager.lamportsToSolDisplay(tx.amountLamports)
                        val shortRecipient = if (tx.recipientPublicKey.length > 12) {
                            "${tx.recipientPublicKey.take(8)}...${tx.recipientPublicKey.takeLast(4)}"
                        } else tx.recipientPublicKey
                        val shortSender = if (tx.senderPublicKey.length > 12) {
                            "${tx.senderPublicKey.take(8)}...${tx.senderPublicKey.takeLast(4)}"
                        } else tx.senderPublicKey

                        val statusMessage = when (tx.status) {
                            com.bitchat.android.data.models.TransactionStatus.AWAITING_BLOCKHASH.value -> {
                                notifiedTransactionIds.add(key)
                                "payment preparing: $amountSol SOL to $shortRecipient (awaiting blockhash)"
                            }
                            com.bitchat.android.data.models.TransactionStatus.BROADCASTING.value -> {
                                notifiedTransactionIds.add(key)
                                "payment broadcasting: $amountSol SOL to $shortRecipient"
                            }
                            com.bitchat.android.data.models.TransactionStatus.CONFIRMED.value -> {
                                notifiedTransactionIds.add(key)
                                val isIncoming = myWalletAddress != null &&
                                    tx.recipientPublicKey == myWalletAddress &&
                                    tx.senderPublicKey != myWalletAddress
                                if (isIncoming) {
                                    "payment received: $amountSol SOL from $shortSender" +
                                        if (!tx.txSignature.isNullOrEmpty()) " (tx: ${tx.txSignature!!.take(12)}...)" else ""
                                } else {
                                    "payment confirmed: $amountSol SOL to $shortRecipient" +
                                        if (!tx.txSignature.isNullOrEmpty()) " (tx: ${tx.txSignature!!.take(12)}...)" else ""
                                }
                            }
                            com.bitchat.android.data.models.TransactionStatus.FAILED.value -> {
                                notifiedTransactionIds.add(key)
                                "payment failed: $amountSol SOL to $shortRecipient" +
                                    if (!tx.errorMessage.isNullOrEmpty()) " - ${tx.errorMessage}" else ""
                            }
                            else -> null
                        }

                        if (statusMessage != null) {
                            // Ensure we're on the main thread for LiveData updates
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                val systemMsg = BitchatMessage(
                                    sender = "system",
                                    content = statusMessage,
                                    timestamp = java.util.Date(),
                                    isRelay = false
                                )
                                messageManager.addMessage(systemMsg)
                            }
                        }
                    }
                }
        }
    }

    private fun observeIncomingBalanceCredits(entryPoint: SolanaEntryPoint) {
        val walletService = entryPoint.solanaWalletService()
        val paymentManager = entryPoint.solanaPaymentManager()
        viewModelScope.launch {
            walletService.observeActiveWallet().collect { wallet ->
                if (wallet == null) {
                    lastObservedWalletAddress = null
                    lastObservedWalletLamports = null
                    return@collect
                }

                if (lastObservedWalletAddress != wallet.publicKey) {
                    lastObservedWalletAddress = wallet.publicKey
                    lastObservedWalletLamports = wallet.lastBalanceLamports
                    return@collect
                }

                val previous = lastObservedWalletLamports
                val current = wallet.lastBalanceLamports
                if (previous != null && current > previous) {
                    val delta = current - previous
                    val dedupKey = "credit:${wallet.publicKey}:${wallet.lastBalanceUpdatedAt}:$current"
                    if (!notifiedIncomingCreditKeys.contains(dedupKey)) {
                        notifiedIncomingCreditKeys.add(dedupKey)
                        val deltaSol = paymentManager.lamportsToSolDisplay(delta)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            messageManager.addMessage(
                                BitchatMessage(
                                    sender = "system",
                                    content = "payment received: $deltaSol SOL",
                                    timestamp = Date(),
                                    isRelay = false
                                )
                            )
                        }
                    }
                }
                lastObservedWalletLamports = current
            }
        }
    }

    // MARK: - Navigation Management
    
    fun showAppInfo() {
        state.setShowAppInfo(true)
    }
    
    fun hideAppInfo() {
        state.setShowAppInfo(false)
    }
    
    fun showSidebar() {
        state.setShowSidebar(true)
    }
    
    fun hideSidebar() {
        state.setShowSidebar(false)
    }
    
    /**
     * Handle Android back navigation
     * Returns true if the back press was handled, false if it should be passed to the system
     */
    fun handleBackPressed(): Boolean {
        return when {
            // Close app info dialog
            state.getShowAppInfoValue() -> {
                hideAppInfo()
                true
            }
            // Close sidebar
            state.getShowSidebarValue() -> {
                hideSidebar()
                true
            }
            // Close password dialog
            state.getShowPasswordPromptValue() -> {
                state.setShowPasswordPrompt(false)
                state.setPasswordPromptChannel(null)
                true
            }
            // Exit private chat
            state.getSelectedPrivateChatPeerValue() != null -> {
                endPrivateChat()
                true
            }
            // Exit channel view
            state.getCurrentChannelValue() != null -> {
                switchToChannel(null)
                true
            }
            // No special navigation state - let system handle (usually exits app)
            else -> false
        }
    }

    // MARK: - iOS-Compatible Color System

    /**
     * Get consistent color for a mesh peer by ID (iOS-compatible)
     */
    fun colorForMeshPeer(peerID: String): androidx.compose.ui.graphics.Color {
        val seed = "noise:${peerID.lowercase()}"
        return colorForPeerSeed(seed).copy()
    }

    fun colorForNostrPubkey(pubkeyHex: String): androidx.compose.ui.graphics.Color {
        return geohashViewModel.colorForNostrPubkey(pubkeyHex)
    }
}
