package com.bitchat.android.ui

import com.bitchat.android.data.local.entities.TokenGateType
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.solana.SolanaPaymentManager
import com.bitchat.android.solana.SolanaWalletService
import com.bitchat.android.solana.TokenGateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Handles processing of IRC-style commands
 */
class CommandProcessor(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val privateChatManager: PrivateChatManager
) {
    // Solana services (set lazily from ChatViewModel via Hilt EntryPoint)
    var walletService: SolanaWalletService? = null
    var paymentManager: SolanaPaymentManager? = null
    var tokenGateService: TokenGateService? = null

    private val commandScope = CoroutineScope(Dispatchers.Main)

    // Available commands list
    private val baseCommands = listOf(
        CommandSuggestion("/block", emptyList(), "name", "block someone"),
        CommandSuggestion("/channels", emptyList(), null, "list your channels"),
        CommandSuggestion("/clear", emptyList(), null, "clear chat"),
        CommandSuggestion("/create", emptyList(), "channel", "make a new channel"),
        CommandSuggestion("/hug", emptyList(), "name", "hug someone"),
        CommandSuggestion("/j", listOf("/join"), "channel", "join a channel"),
        CommandSuggestion("/m", listOf("/msg"), "name", "private message"),
        CommandSuggestion("/slap", emptyList(), "name", "slap someone with a trout"),
        CommandSuggestion("/tip", emptyList(), "name amount", "send SOL"),
        CommandSuggestion("/unblock", emptyList(), "name", "unblock someone"),
        CommandSuggestion("/w", emptyList(), null, "who's online"),
        CommandSuggestion("/wallet", emptyList(), null, "your wallet")
    )
    
    // MARK: - Command Processing
    
    fun processCommand(command: String, meshService: BluetoothMeshService, myPeerID: String, onSendMessage: (String, List<String>, String?) -> Unit, viewModel: ChatViewModel? = null): CommandResult? {
        if (!command.startsWith("/")) return null

        val parts = command.split(" ")
        val cmd = parts.first().lowercase()
        return when (cmd) {
            "/j", "/join" -> handleJoinCommand(parts, myPeerID, viewModel)
            "/create" -> handleCreateCommand(parts, myPeerID, viewModel)
            "/m", "/msg" -> handleMessageCommand(parts, meshService)
            "/tip" -> handleTipCommand(parts, meshService, viewModel)
            "/w" -> { handleWhoCommand(meshService, viewModel); null }
            "/clear" -> { handleClearCommand(); null }
            "/pass" -> { handlePassCommand(parts, myPeerID); null }
            "/block" -> { handleBlockCommand(parts, meshService); null }
            "/unblock" -> { handleUnblockCommand(parts, meshService); null }
            "/hug" -> { handleActionCommand(parts, "gives", "a warm hug 🫂", meshService, myPeerID, onSendMessage); null }
            "/slap" -> { handleActionCommand(parts, "slaps", "around a bit with a large trout 🐟", meshService, myPeerID, onSendMessage); null }
            "/channels" -> { handleChannelsCommand(); null }
            "/wallet" -> { handleWalletCommand(); null }
            else -> { handleUnknownCommand(cmd); null }
        }
    }
    
    private fun handleJoinCommand(parts: List<String>, myPeerID: String, viewModel: ChatViewModel?): CommandResult? {
        if (parts.size > 1) {
            val channelName = parts[1]
            val channel = if (channelName.startsWith("#")) channelName else "#$channelName"
            val password = if (parts.size > 2) parts[2] else null

            // Get current timeline from viewModel
            val timeline = viewModel?.selectedLocationChannel?.value

            val success = channelManager.joinChannel(channel, password, myPeerID, timeline)
            if (success) {
                val key = ChannelKeys.create(timeline, channel)
                val isGated = channelManager.isChannelTokenGated(key)
                val suffix = if (isGated) " (token-gated)" else ""
                addSystemMessage("joined channel $channel$suffix")
            }
            return null
        } else {
            return CommandResult(prefillText = "/join #", hintText = "type a channel name")
        }
    }

    private fun handleCreateCommand(parts: List<String>, myPeerID: String, viewModel: ChatViewModel?): CommandResult? {
        if (parts.size < 2) {
            return CommandResult(prefillText = "/create #", hintText = "type a channel name")
        }

        val channelName = parts[1]
        val channel = if (channelName.startsWith("#")) channelName else "#$channelName"
        val timeline = viewModel?.selectedLocationChannel?.value

        // Check for --token-gate flag
        val tokenGateIndex = parts.indexOf("--token-gate")
        if (tokenGateIndex != -1) {
            val tgs = tokenGateService
            val ws = walletService

            if (tgs == null || ws == null) {
                addSystemMessage("wallet not available yet — try again in a moment.")
                return null
            }

            if (!ws.hasWallet()) {
                addSystemMessage("you need a wallet first — open settings to create one.")
                return null
            }

            // Parse: --token-gate <mint_address> <min_amount> [symbol] [decimals]
            if (parts.size < tokenGateIndex + 3) {
                addSystemMessage("to token-gate a channel, add the token address and amount:\n/create #vip --token-gate TokenAddress 100 USDC")
                return null
            }

            val mintAddress = parts[tokenGateIndex + 1]
            val minAmountStr = parts[tokenGateIndex + 2]
            val minAmount = minAmountStr.toLongOrNull()
            if (minAmount == null || minAmount <= 0) {
                addSystemMessage("invalid minimum amount: $minAmountStr")
                return null
            }

            val symbol = if (parts.size > tokenGateIndex + 3) parts[tokenGateIndex + 3] else ""
            val decimals = if (parts.size > tokenGateIndex + 4) parts[tokenGateIndex + 4].toIntOrNull() ?: 0 else 0

            // First join/create the channel
            val success = channelManager.joinChannel(channel, null, myPeerID, timeline)
            if (!success) return null

            // Now create the token gate
            val key = ChannelKeys.create(timeline, channel)
            commandScope.launch {
                val result = tgs.createTokenGate(
                    channelKey = key,
                    gateType = TokenGateType.SPL_TOKEN,
                    tokenMintAddress = mintAddress,
                    minBalance = minAmount,
                    tokenSymbol = symbol,
                    tokenDecimals = decimals
                )
                result.onSuccess {
                    val displaySymbol = symbol.ifEmpty { "tokens" }
                    addSystemMessage("created token-gated channel $channel: requires $minAmount $displaySymbol (mint: ${mintAddress.take(8)}...)")
                }.onFailure { error ->
                    addSystemMessage("channel created but token gate setup failed: ${error.message}")
                }
            }
        } else {
            // Regular channel creation (same as join)
            val success = channelManager.joinChannel(channel, null, myPeerID, timeline)
            if (success) {
                addSystemMessage("created channel $channel")
            }
        }
        return null
    }

    private fun handleMessageCommand(parts: List<String>, meshService: BluetoothMeshService): CommandResult? {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            val peerID = getPeerIDForNickname(targetName, meshService)

            if (peerID != null) {
                val success = privateChatManager.startPrivateChat(peerID, meshService)

                if (success) {
                    if (parts.size > 2) {
                        val messageContent = parts.drop(2).joinToString(" ")
                        val recipientNickname = getPeerNickname(peerID, meshService)
                        privateChatManager.sendPrivateMessage(
                            messageContent,
                            peerID,
                            recipientNickname,
                            state.getNicknameValue(),
                            getMyPeerID(meshService)
                        ) { content, peerIdParam, recipientNicknameParam, messageId ->
                            // This would trigger the actual mesh service send
                            sendPrivateMessageVia(meshService, content, peerIdParam, recipientNicknameParam, messageId)
                        }
                    } else {
                        val systemMessage = BitchatMessage(
                            sender = "system",
                            content = "started private chat with $targetName",
                            timestamp = Date(),
                            isRelay = false
                        )
                        messageManager.addMessage(systemMessage)
                    }
                }
            } else {
                addSystemMessage("can't find '$targetName' — are they online? check /w")
            }
            return null
        } else {
            return CommandResult(prefillText = "/m @", hintText = "who do you want to message?")
        }
    }
    
    private fun handleWhoCommand(meshService: BluetoothMeshService, viewModel: ChatViewModel? = null) {
        // Channel-aware who command (matches iOS behavior)
        val (peerList, contextDescription) = if (viewModel != null) {
            when (val selectedChannel = viewModel.selectedLocationChannel.value) {
                is com.bitchat.android.geohash.ChannelID.Mesh,
                null -> {
                    // Mesh channel: show Bluetooth-connected peers
                    val connectedPeers = state.getConnectedPeersValue()
                    val peerList = connectedPeers.joinToString(", ") { peerID ->
                        getPeerNickname(peerID, meshService)
                    }
                    Pair(peerList, "online users")
                }
                
                is com.bitchat.android.geohash.ChannelID.Location -> {
                    // Location channel: show geohash participants
                    val geohashPeople = viewModel.geohashPeople.value ?: emptyList()
                    val currentNickname = state.getNicknameValue()
                    
                    val participantList = geohashPeople.mapNotNull { person ->
                        val displayName = person.displayName
                        // Exclude self from list
                        if (displayName.startsWith("${currentNickname}#")) {
                            null
                        } else {
                            displayName
                        }
                    }.joinToString(", ")
                    
                    Pair(participantList, "participants in ${selectedChannel.channel.geohash}")
                }
            }
        } else {
            // Fallback to mesh behavior
            val connectedPeers = state.getConnectedPeersValue()
            val peerList = connectedPeers.joinToString(", ") { peerID ->
                getPeerNickname(peerID, meshService)
            }
            Pair(peerList, "online users")
        }
        
        val systemMessage = BitchatMessage(
            sender = "system",
            content = if (peerList.isEmpty()) {
                "no one else is around right now."
            } else {
                "$contextDescription: $peerList"
            },
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }
    
    private fun handleClearCommand() {
        when {
            state.getSelectedPrivateChatPeerValue() != null -> {
                // Clear private chat
                val peerID = state.getSelectedPrivateChatPeerValue()!!
                messageManager.clearPrivateMessages(peerID)
            }
            state.getCurrentChannelValue() != null -> {
                // Clear channel messages
                val channel = state.getCurrentChannelValue()!!
                messageManager.clearChannelMessages(channel)
            }
            else -> {
                // Clear main messages
                messageManager.clearMessages()
            }
        }
    }

    private fun handlePassCommand(parts: List<String>, peerID: String) {
        val currentChannel = state.getCurrentChannelValue()

        if (currentChannel == null) {
            addSystemMessage("join a channel first to set a password.")
            return
        }

        if (parts.size == 2){
            if(!channelManager.isChannelCreator(channel = currentChannel, peerID = peerID)){
                val systemMessage = BitchatMessage(
                    sender = "system",
                    content = "only the channel creator can set a password.",
                    timestamp = Date(),
                    isRelay = false
                )
                channelManager.addChannelMessage(currentChannel,systemMessage,null)
                return
            }
            val newPassword = parts[1]
            channelManager.setChannelPassword(currentChannel, newPassword)
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "password updated for $currentChannel",
                timestamp = Date(),
                isRelay = false
            )
            channelManager.addChannelMessage(currentChannel,systemMessage,null)
        }
        else{
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "type a password after /pass, e.g. /pass secret123",
                timestamp = Date(),
                isRelay = false
            )
            channelManager.addChannelMessage(currentChannel,systemMessage,null)
        }
    }
    
    private fun handleBlockCommand(parts: List<String>, meshService: BluetoothMeshService) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            privateChatManager.blockPeerByNickname(targetName, meshService)
        } else {
            // List blocked users
            val blockedInfo = privateChatManager.listBlockedUsers()
            val systemMessage = BitchatMessage(
                sender = "system",
                content = blockedInfo,
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleUnblockCommand(parts: List<String>, meshService: BluetoothMeshService) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            privateChatManager.unblockPeerByNickname(targetName, meshService)
        } else {
            addSystemMessage("type a name after /unblock, e.g. /unblock alice")
        }
    }
    
    private fun handleActionCommand(
        parts: List<String>, 
        verb: String, 
        object_: String, 
        meshService: BluetoothMeshService,
        myPeerID: String,
        onSendMessage: (String, List<String>, String?) -> Unit
    ) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            val actionMessage = "* ${state.getNicknameValue() ?: "someone"} $verb $targetName $object_ *"

            // If we're in a geohash location channel, don't add a local echo here.
            // GeohashViewModel.sendGeohashMessage() will add the local echo with proper metadata.
            val isInLocationChannel = state.selectedLocationChannel.value is com.bitchat.android.geohash.ChannelID.Location

            // Send as regular message
            if (state.getSelectedPrivateChatPeerValue() != null) {
                val peerID = state.getSelectedPrivateChatPeerValue()!!
                privateChatManager.sendPrivateMessage(
                    actionMessage,
                    peerID,
                    getPeerNickname(peerID, meshService),
                    state.getNicknameValue(),
                    myPeerID
                ) { content, peerIdParam, recipientNicknameParam, messageId ->
                    sendPrivateMessageVia(meshService, content, peerIdParam, recipientNicknameParam, messageId)
                }
            } else if (isInLocationChannel) {
                // Let the transport layer add the echo; just send it out
                onSendMessage(actionMessage, emptyList(), null)
            } else {
                val message = BitchatMessage(
                    sender = state.getNicknameValue() ?: myPeerID,
                    content = actionMessage,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = myPeerID,
                    channel = state.getCurrentChannelValue()
                )
                
                if (state.getCurrentChannelValue() != null) {
                    channelManager.addChannelMessage(state.getCurrentChannelValue()!!, message, myPeerID)
                    onSendMessage(actionMessage, emptyList(), state.getCurrentChannelValue())
                } else {
                    messageManager.addMessage(message)
                    onSendMessage(actionMessage, emptyList(), null)
                }
            }
        } else {
            addSystemMessage("type a name, e.g. /${parts[0].removePrefix("/")} alice")
        }
    }
    
    private fun handleTipCommand(parts: List<String>, meshService: BluetoothMeshService, viewModel: ChatViewModel? = null): CommandResult? {
        val ws = walletService
        val pm = paymentManager

        if (ws == null || pm == null) {
            addSystemMessage("wallet not available yet — try again in a moment.")
            return null
        }

        if (!ws.hasWallet()) {
            addSystemMessage("you need a wallet first — open settings to create one.")
            return null
        }

        // Step 1: need a recipient
        if (parts.size < 2) {
            return CommandResult(prefillText = "/tip @", hintText = "who do you want to tip?")
        }

        // Step 2: need an amount
        if (parts.size < 3) {
            val prefill = "${parts[0]} ${parts[1]} "
            return CommandResult(prefillText = prefill, hintText = "how much SOL?")
        }

        val targetName = parts[1].removePrefix("@")
        val amountStr = parts[2]
        val memo = if (parts.size > 3) parts.drop(3).joinToString(" ") else null

        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            val prefill = "${parts[0]} ${parts[1]} "
            return CommandResult(prefillText = prefill, hintText = "'$amountStr' isn't valid — try a number like 0.5")
        }

        // Look up the peer's Solana address
        // Check if target looks like a base58 Solana address, or resolve by nickname
        val recipientAddress: String = if (targetName.length >= 32 && targetName.all { it.isLetterOrDigit() }) {
            // Looks like a Solana address
            targetName
        } else {
            // Look up peer's Solana address from mesh identity announcements
            val peerAddress = viewModel?.getPeerSolanaAddress(targetName)
            if (peerAddress != null) {
                peerAddress
            } else {
                // Check if peer exists at all
                val peerExists = meshService.getPeerNicknames().values.any { it == targetName }
                if (peerExists) {
                    addSystemMessage("$targetName hasn't set up a wallet yet.")
                } else {
                    addSystemMessage("can't find '$targetName' — are they online? check /w")
                }
                return null
            }
        }

        addSystemMessage("sending $amount SOL to $targetName...")

        commandScope.launch {
            val result = pm.queuePayment(recipientAddress, amount, memo)
            result.onSuccess { txId ->
                addSystemMessage("$amount SOL sent to $targetName! confirming...")
            }.onFailure { error ->
                addSystemMessage("couldn't send payment: ${error.message}")
            }
        }
        return null
    }

    private fun handleWalletCommand() {
        val ws = walletService

        if (ws == null) {
            addSystemMessage("wallet not available yet.")
            return
        }

        if (!ws.hasWallet()) {
            addSystemMessage("no wallet yet — open settings to create one.")
            return
        }

        val address = ws.getPublicKeyBase58() ?: "unknown"
        val shortAddr = ws.getShortAddress() ?: address

        commandScope.launch {
            val cachedBalance = ws.getCachedBalanceLamports()
            val balanceSol = ws.lamportsToSol(cachedBalance)
            addSystemMessage("$balanceSol SOL | $shortAddr\nfull address: $address")
        }
    }

    private fun addSystemMessage(content: String) {
        val systemMessage = BitchatMessage(
            sender = "system",
            content = content,
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }

    private fun handleChannelsCommand() {
        val allChannels = channelManager.getJoinedChannelsList()
        val channelList = if (allChannels.isEmpty()) {
            "no channels joined"
        } else {
            // Extract channel names from composite keys for display
            val channelNames = allChannels
                .map { ChannelKeys.parseChannelName(it) }
                .distinct()
                .sorted()
                .joinToString(", ")
            "joined channels: $channelNames"
        }

        val systemMessage = BitchatMessage(
            sender = "system",
            content = channelList,
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }
    
    private fun handleUnknownCommand(cmd: String) {
        addSystemMessage("$cmd isn't a command — type / to see what's available.")
    }
    
    // MARK: - Command Autocomplete

    fun updateCommandSuggestions(input: String) {
        if (!input.startsWith("/")) {
            state.setShowCommandSuggestions(false)
            state.setCommandSuggestions(emptyList())
            return
        }
        
        // Get all available commands based on context
        val allCommands = getAllAvailableCommands()
        
        // Filter commands based on input
        val filteredCommands = filterCommands(allCommands, input.lowercase())
        
        if (filteredCommands.isNotEmpty()) {
            state.setCommandSuggestions(filteredCommands)
            state.setShowCommandSuggestions(true)
        } else {
            state.setShowCommandSuggestions(false)
            state.setCommandSuggestions(emptyList())
        }
    }
    
    private fun getAllAvailableCommands(): List<CommandSuggestion> {
        // Add channel-specific commands if in a channel
        val channelCommands = if (state.getCurrentChannelValue() != null) {
            listOf(
                CommandSuggestion("/pass", emptyList(), "[password]", "change channel password"),
                CommandSuggestion("/save", emptyList(), null, "save channel messages locally"),
                CommandSuggestion("/transfer", emptyList(), "<nickname>", "transfer channel ownership")
            )
        } else {
            emptyList()
        }
        
        return baseCommands + channelCommands
    }
    
    private fun filterCommands(commands: List<CommandSuggestion>, input: String): List<CommandSuggestion> {
        return commands.filter { command ->
            // Check primary command
            command.command.startsWith(input) ||
            // Check aliases
            command.aliases.any { it.startsWith(input) }
        }.sortedBy { it.command }
    }
    
    fun selectCommandSuggestion(suggestion: CommandSuggestion): CommandResult {
        state.setShowCommandSuggestions(false)
        state.setCommandSuggestions(emptyList())

        // Pre-fill with contextual hint based on what the command expects
        return when (suggestion.command) {
            "/create" -> CommandResult(prefillText = "/create #", hintText = "type a channel name")
            "/j", "/join" -> CommandResult(prefillText = "/join #", hintText = "type a channel name")
            "/tip" -> CommandResult(prefillText = "/tip @", hintText = "who do you want to tip?")
            "/m", "/msg" -> CommandResult(prefillText = "/m @", hintText = "who do you want to message?")
            "/block" -> CommandResult(prefillText = "/block @", hintText = "who do you want to block?")
            "/unblock" -> CommandResult(prefillText = "/unblock @", hintText = "who do you want to unblock?")
            "/hug" -> CommandResult(prefillText = "/hug @", hintText = "who do you want to hug?")
            "/slap" -> CommandResult(prefillText = "/slap @", hintText = "who do you want to slap?")
            else -> CommandResult(prefillText = "${suggestion.command} ")
        }
    }
    
    // MARK: - Mention Autocomplete
    
    fun updateMentionSuggestions(input: String, meshService: BluetoothMeshService, viewModel: ChatViewModel? = null) {
        // Check if input contains @ and we're at the end of a word or at the end of input
        val atIndex = input.lastIndexOf('@')
        if (atIndex == -1) {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
            return
        }
        
        // Get the text after the @ symbol
        val textAfterAt = input.substring(atIndex + 1)
        
        // If there's a space after @, don't show suggestions
        if (textAfterAt.contains(' ')) {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
            return
        }
        
        // Get peer candidates based on active channel (matches iOS logic exactly)
        val peerCandidates: List<String> = if (viewModel != null) {
            when (val selectedChannel = viewModel.selectedLocationChannel.value) {
                is com.bitchat.android.geohash.ChannelID.Mesh,
                null -> {
                    // Mesh channel: use Bluetooth mesh peer nicknames
                    meshService.getPeerNicknames().values.filter { it != meshService.getPeerNicknames()[meshService.myPeerID] }
                }
                
                is com.bitchat.android.geohash.ChannelID.Location -> {
                    // Location channel: use geohash participants with collision-resistant suffixes
                    val geohashPeople = viewModel.geohashPeople.value ?: emptyList()
                    val currentNickname = state.getNicknameValue()
                    
                    geohashPeople.mapNotNull { person ->
                        val displayName = person.displayName
                        // Exclude self from suggestions
                        if (displayName.startsWith("${currentNickname}#")) {
                            null
                        } else {
                            displayName
                        }
                    }
                }
            }
        } else {
            // Fallback to mesh peers if no viewModel available
            meshService.getPeerNicknames().values.filter { it != meshService.getPeerNicknames()[meshService.myPeerID] }
        }
        
        // Filter nicknames based on the text after @
        val filteredNicknames = peerCandidates.filter { nickname ->
            nickname.startsWith(textAfterAt, ignoreCase = true)
        }.sorted()
        
        if (filteredNicknames.isNotEmpty()) {
            state.setMentionSuggestions(filteredNicknames)
            state.setShowMentionSuggestions(true)
        } else {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
        }
    }
    
    fun selectMentionSuggestion(nickname: String, currentText: String): String {
        state.setShowMentionSuggestions(false)
        state.setMentionSuggestions(emptyList())
        
        // Find the last @ symbol position
        val atIndex = currentText.lastIndexOf('@')
        if (atIndex == -1) {
            return "$currentText@$nickname "
        }
        
        // Replace the text from the @ symbol to the end with the mention
        val textBeforeAt = currentText.substring(0, atIndex)
        return "$textBeforeAt@$nickname "
    }
    
    // MARK: - Utility Functions
    
    private fun getPeerIDForNickname(nickname: String, meshService: BluetoothMeshService): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }
    
    private fun getPeerNickname(peerID: String, meshService: BluetoothMeshService): String {
        return meshService.getPeerNicknames()[peerID] ?: peerID
    }
    
    private fun getMyPeerID(meshService: BluetoothMeshService): String {
        return meshService.myPeerID
    }
    
    private fun sendPrivateMessageVia(meshService: BluetoothMeshService, content: String, peerID: String, recipientNickname: String, messageId: String) {
        meshService.sendPrivateMessage(content, peerID, recipientNickname, messageId)
    }
}
