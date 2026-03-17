package com.bitchat.android.ui

import com.bitchat.android.data.local.entities.LendingMemberStatus
import com.bitchat.android.data.local.entities.BorrowerType
import com.bitchat.android.data.local.entities.TokenGateType
import com.bitchat.android.data.local.entities.VoteChoice
import com.bitchat.android.lending.CastLoanVoteRequest
import com.bitchat.android.lending.AuthorizeSignerReviewRequest
import com.bitchat.android.lending.CancelLoanRequest
import com.bitchat.android.lending.ConfigureLendingSquadRequest
import com.bitchat.android.lending.CreateLoanRequest
import com.bitchat.android.lending.CreateLendingChannelRequest
import com.bitchat.android.lending.DEFAULT_CREDIBILITY_THRESHOLD
import com.bitchat.android.lending.DEFAULT_INTEREST_BPS
import com.bitchat.android.lending.ForwardLoanRequest
import com.bitchat.android.lending.ImportLendingChannelRequest
import com.bitchat.android.lending.LendingChannelConfigMessage
import com.bitchat.android.lending.LendingChannelConfigMessageCodec
import com.bitchat.android.lending.LendingChannelConfigRequestMessage
import com.bitchat.android.lending.LendingChannelConfigRequestMessageCodec
import com.bitchat.android.lending.LeaveLendingChannelRequest
import com.bitchat.android.lending.LendingChannelService
import com.bitchat.android.lending.LendingChannelInvite
import com.bitchat.android.lending.LendingChannelInviteCodec
import com.bitchat.android.lending.LendingCredibilityRequest
import com.bitchat.android.lending.LendingCredibilityService
import com.bitchat.android.lending.LendingEscrowService
import com.bitchat.android.lending.LendingMembershipMessage
import com.bitchat.android.lending.LendingMembershipMessageCodec
import com.bitchat.android.lending.LendingLoanRequestMessage
import com.bitchat.android.lending.LendingLoanRequestMessageCodec
import com.bitchat.android.lending.LendingLoanRepaymentMessage
import com.bitchat.android.lending.LendingLoanRepaymentMessageCodec
import com.bitchat.android.lending.LendingLoanVoteMessage
import com.bitchat.android.lending.LendingLoanVoteMessageCodec
import com.bitchat.android.lending.LendingLoanService
import com.bitchat.android.lending.OpenSignerReviewRequest
import com.bitchat.android.lending.RecordPendingMembershipRequest
import com.bitchat.android.lending.RecordLoanRepaymentRequest
import com.bitchat.android.lending.DisburseApprovedLoanRequest
import com.bitchat.android.lending.NATIVE_SOL_ASSET
import com.bitchat.android.lending.countLoanApprovals
import com.bitchat.android.lending.countLoanRejections
import com.bitchat.android.lending.isNativeSolStakeAsset
import com.bitchat.android.lending.requiredJoinDebitAmount
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.solana.GateDecision
import com.bitchat.android.solana.SolanaPaymentManager
import com.bitchat.android.solana.SolanaWalletService
import com.bitchat.android.solana.TokenGateService
import com.bitchat.android.solana.ValidationMode
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode
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
    var lendingChannelService: LendingChannelService? = null
    var lendingCredibilityService: LendingCredibilityService? = null
    var lendingLoanService: LendingLoanService? = null
    var lendingEscrowService: LendingEscrowService? = null

    private val commandScope = CoroutineScope(Dispatchers.Main)

    // Global commands (shown only when no channel is active)
    private val globalCommands = listOf(
        CommandSuggestion("/block", emptyList(), "name", "block someone"),
        CommandSuggestion("/channels", emptyList(), null, "list your channels"),
        CommandSuggestion("/clear", emptyList(), null, "clear chat"),
        CommandSuggestion("/create", emptyList(), "channel", "make a new channel"),
        CommandSuggestion("/gm", emptyList(), "name", "send a gm"),
        CommandSuggestion("/hug", emptyList(), "name", "hug someone"),
        CommandSuggestion("/j", listOf("/join"), "channel", "join a channel"),
        CommandSuggestion("/lending", emptyList(), "<action>", "community lending tools"),
        CommandSuggestion("/m", listOf("/msg"), "name", "private message"),
        CommandSuggestion("/tip", emptyList(), null, "send SOL"),
        CommandSuggestion("/unblock", emptyList(), "name", "unblock someone"),
        CommandSuggestion("/w", emptyList(), null, "who's online"),
        CommandSuggestion("/wallet", emptyList(), null, "your wallet")
    )

    private val lendingChannelOnlyCommands = listOf(
        CommandSuggestion("/lending status", emptyList(), "[#channel|lendingId]", "show lending status"),
        CommandSuggestion("/lending invite", emptyList(), "[#channel|lendingId]", "create a lending invite code"),
        CommandSuggestion("/lending import", emptyList(), "<invite_code>", "import a lending invite"),
        CommandSuggestion("/lending request", emptyList(), "[#channel|lendingId] <amount> [asset] <days> <purpose>", "request a loan in the current lending channel"),
        CommandSuggestion("/lending cancel", emptyList(), "<request_id>", "borrower or admin cancels an open loan request"),
        CommandSuggestion("/lending vote", emptyList(), "<request_id> approve", "approve a loan request"),
        CommandSuggestion("/lending review", emptyList(), "<request_id>", "admin opens signer review for a community-approved loan"),
        CommandSuggestion("/lending authorize", emptyList(), "<request_id>", "approver authorizes payout during signer review"),
        CommandSuggestion("/lending disburse", emptyList(), "<request_id>", "admin disburses an approved loan"),
        CommandSuggestion("/lending repay", emptyList(), "<request_id> <amount> [asset]", "repay a loan"),
        CommandSuggestion("/lending leave", emptyList(), "[#channel|lendingId]", "leave lending channel")
    )
    
    // MARK: - Command Processing
    
    fun processCommand(command: String, meshService: BluetoothMeshService, myPeerID: String, onSendMessage: (String, List<String>, String?) -> Unit, viewModel: ChatViewModel? = null): CommandResult? {
        if (!command.startsWith("/")) return null

        val parts = command.split(" ")
        val cmd = parts.first().lowercase()
        return when (cmd) {
            "/j", "/join" -> handleJoinCommand(parts, myPeerID, viewModel)
            "/lending" -> handleLendingCommand(parts, myPeerID, viewModel, meshService, onSendMessage)
            "/create" -> handleCreateCommand(parts, myPeerID, viewModel, meshService)
            "/channel" -> handleChannelCommand(parts, myPeerID, meshService, viewModel)
            "/gate" -> handleGateCommand(parts, myPeerID, viewModel, meshService)
            "/leave" -> handleLeaveCommand(parts, myPeerID, meshService, viewModel)
            "/users" -> handleUsersCommand(parts, myPeerID, meshService, viewModel)
            "/kick" -> handleKickCommand(parts, myPeerID, meshService, viewModel)
            "/transfer" -> handleTransferCommand(parts, myPeerID, meshService, viewModel)
            "/m", "/msg" -> handleMessageCommand(parts, meshService, viewModel)
            "/tip" -> handleTipCommand(parts, meshService, viewModel)
            "/w" -> { handleWhoCommand(meshService, viewModel); null }
            "/clear" -> { handleClearCommand(); null }
            "/pass" -> { handlePassCommand(parts, myPeerID); null }
            "/block" -> { handleBlockCommand(parts, meshService); null }
            "/unblock" -> { handleUnblockCommand(parts, meshService); null }
            "/gm" -> { handleActionCommand(parts, "says", "gm ☀️", meshService, myPeerID, onSendMessage); null }
            "/hug" -> { handleActionCommand(parts, "gives", "a warm hug 🫂", meshService, myPeerID, onSendMessage); null }
            "/channels" -> { handleChannelsCommand(); null }
            "/wallet" -> { handleWalletCommand(); null }
            else -> { handleUnknownCommand(cmd); null }
        }
    }

    private fun requireChannelAdmin(
        channelArg: String?,
        myPeerID: String,
        viewModel: ChatViewModel?,
        action: String
    ): Pair<String, String>? {
        val target = resolveGateTarget(channelArg, viewModel) ?: return null
        val (channelTag, channelKey) = target

        if (!channelManager.hasChannelCreator(channelKey)) {
            addSystemMessage("permission denied: $action in $channelTag requires channel admin role, but no channel owner is configured yet.")
            return null
        }

        if (!channelManager.isChannelAdmin(channelKey, myPeerID)) {
            val role = channelManager.getChannelRole(channelKey, myPeerID)
            addSystemMessage("permission denied: $action in $channelTag requires channel admin role (your role: $role).")
            return null
        }
        return target
    }

    private fun requireChannelOwner(
        channelArg: String?,
        myPeerID: String,
        viewModel: ChatViewModel?,
        action: String
    ): Pair<String, String>? {
        val target = resolveGateTarget(channelArg, viewModel) ?: return null
        val (channelTag, channelKey) = target

        if (!channelManager.hasChannelCreator(channelKey)) {
            addSystemMessage("permission denied: $action in $channelTag requires a channel owner.")
            return null
        }

        if (!channelManager.isChannelCreator(channelKey, myPeerID)) {
            addSystemMessage("permission denied: $action in $channelTag requires channel owner role.")
            return null
        }
        return target
    }

    private fun handleChannelCommand(
        parts: List<String>,
        myPeerID: String,
        meshService: BluetoothMeshService,
        viewModel: ChatViewModel?
    ): CommandResult? {
        if (parts.size < 2) {
            return CommandResult(
                prefillText = "/channel ",
                hintText = "usage: /channel users|gate show|exit ..."
            )
        }

        return when (parts[1].lowercase()) {
            "users" -> {
                val target = resolveGateTarget(parts.getOrNull(2), viewModel) ?: return null
                val (channelTag, channelKey) = target
                val members = channelManager.getChannelMembers(channelKey)
                if (members.isEmpty()) {
                    addSystemMessage("no tracked members in $channelTag yet.")
                    return null
                }
                val nicknames = members
                    .map { getPeerNickname(it, meshService, viewModel) }
                    .distinct()
                    .sorted()
                addSystemMessage("users in $channelTag (${nicknames.size}): ${nicknames.joinToString(", ")}")
                null
            }
            "gate" -> {
                if (parts.size < 3) {
                    return CommandResult(
                        prefillText = "/channel gate ",
                        hintText = "usage: /channel gate show|refresh|set|remove ..."
                    )
                }
                when (parts[2].lowercase()) {
                    "show" -> {
                        val gateParts = mutableListOf("/gate", "status")
                        parts.getOrNull(3)?.let { gateParts.add(it) }
                        handleGateCommand(gateParts, myPeerID, viewModel, meshService)
                    }
                    "refresh" -> {
                        val gateParts = mutableListOf("/gate", "refresh")
                        parts.getOrNull(3)?.let { gateParts.add(it) }
                        handleGateCommand(gateParts, myPeerID, viewModel, meshService)
                    }
                    "remove" -> {
                        val gateParts = mutableListOf("/gate", "remove")
                        parts.getOrNull(3)?.let { gateParts.add(it) }
                        handleGateCommand(gateParts, myPeerID, viewModel, meshService)
                    }
                    "set" -> {
                        if (parts.size < 5) {
                            return CommandResult(
                                prefillText = "/channel gate set #",
                                hintText = "usage: /channel gate set #vip <spl|sol|nft-specific|nft-collection> ..."
                            )
                        }
                        val channel = parts[3]
                        val gateParts = mutableListOf("/gate", "create", channel)
                        gateParts.addAll(parts.drop(4))
                        handleGateCommand(gateParts, myPeerID, viewModel, meshService)
                    }
                    else -> {
                        CommandResult(
                            prefillText = "/channel gate ",
                            hintText = "usage: /channel gate show|refresh|set|remove ..."
                        )
                    }
                }
            }
            "exit", "leave" -> {
                val target = resolveGateTarget(parts.getOrNull(2), viewModel) ?: return null
                val (channelTag, channelKey) = target
                if (!state.getJoinedChannelsValue().contains(channelKey)) {
                    addSystemMessage("you are not in $channelTag.")
                    return null
                }
                channelManager.leaveChannel(channelKey)
                addSystemMessage("left channel $channelTag.")
                null
            }
            "member" -> {
                if (parts.size < 3) {
                    return CommandResult(
                        prefillText = "/channel member ",
                        hintText = "usage: /channel member <remove|admin|member> @nickname"
                    )
                }

                when (parts[2].lowercase()) {
                    "remove" -> {
                        val subject = parts.getOrNull(3)
                        if (subject.isNullOrBlank()) {
                            return CommandResult(
                                prefillText = "/channel member remove @",
                                hintText = "which user should be removed?"
                            )
                        }

                        val looksLikeChannel =
                            subject.startsWith("#") || subject.startsWith("mesh:") || subject.startsWith("geo:")
                        val channelArg = if (looksLikeChannel) subject else null
                        val nicknameArg = if (looksLikeChannel) parts.getOrNull(4) else subject

                        if (nicknameArg.isNullOrBlank()) {
                            val prefill = if (channelArg != null) "/channel member remove $channelArg @" else "/channel member remove @"
                            return CommandResult(
                                prefillText = prefill,
                                hintText = "which user should be removed?"
                            )
                        }

                        val target = requireChannelAdmin(
                            channelArg = channelArg,
                            myPeerID = myPeerID,
                            viewModel = viewModel,
                            action = "remove channel members"
                        ) ?: return null
                        val (channelTag, channelKey) = target

                        val targetName = nicknameArg.removePrefix("@")
                        val targetPeerID = getPeerIDForNickname(targetName, meshService, viewModel)
                        if (targetPeerID == null) {
                            addSystemMessage("can't find '$targetName' — are they online? check /w")
                            return null
                        }

                        if (targetPeerID == myPeerID) {
                            addSystemMessage("use leave channel instead of removing yourself from $channelTag.")
                            return null
                        }

                        if (channelManager.isChannelCreator(channelKey, targetPeerID)) {
                            addSystemMessage("can't remove channel owner from $channelTag. transfer ownership first.")
                            return null
                        }

                        if (!channelManager.getChannelMembers(channelKey).contains(targetPeerID)) {
                            addSystemMessage("@${getPeerNickname(targetPeerID, meshService, viewModel)} is not a tracked member of $channelTag.")
                            return null
                        }

                        channelManager.removeChannelMember(channelKey, targetPeerID)
                        val nextVersion = channelManager.nextChannelRoleVersion(channelKey)
                        channelManager.buildChannelRolePolicy(channelKey, roleVersion = nextVersion)?.let { payload ->
                            meshService.broadcastChannelRolePolicy(payload)
                        }
                        addSystemMessage("removed @${getPeerNickname(targetPeerID, meshService, viewModel)} from $channelTag.")
                        null
                    }
                    "admin", "member", "endorser" -> {
                        val subject = parts.getOrNull(3)
                        if (subject.isNullOrBlank()) {
                            val prefill = "/channel member ${parts[2].lowercase()} @"
                            val hint = if (parts[2].lowercase() == "admin") {
                                "who should become admin?"
                            } else if (parts[2].lowercase() == "endorser") {
                                "who should become endorser?"
                            } else {
                                "who should be demoted to member?"
                            }
                            return CommandResult(prefillText = prefill, hintText = hint)
                        }

                        val looksLikeChannel =
                            subject.startsWith("#") || subject.startsWith("mesh:") || subject.startsWith("geo:")
                        if (looksLikeChannel) {
                            addSystemMessage("role changes are channel-local. switch to the channel first, then use /channel member ${parts[2].lowercase()} @nickname.")
                            return null
                        }

                        val target = requireChannelAdmin(
                            channelArg = null,
                            myPeerID = myPeerID,
                            viewModel = viewModel,
                            action = "change member roles"
                        ) ?: return null
                        val (channelTag, channelKey) = target

                        val targetName = subject.removePrefix("@")
                        val targetPeerID = getPeerIDForNickname(targetName, meshService, viewModel)
                        if (targetPeerID == null) {
                            addSystemMessage("can't find '$targetName' — are they online? check /w")
                            return null
                        }
                        if (!channelManager.getChannelMembers(channelKey).contains(targetPeerID)) {
                            addSystemMessage("@${getPeerNickname(targetPeerID, meshService, viewModel)} is not a tracked member of $channelTag.")
                            return null
                        }

                        val roleAction = parts[2].lowercase()
                        val changed = if (roleAction == "admin") {
                            channelManager.setChannelAdmin(channelKey, myPeerID, targetPeerID)
                        } else if (roleAction == "endorser") {
                            channelManager.setChannelEndorser(channelKey, myPeerID, targetPeerID)
                        } else {
                            channelManager.setChannelMember(channelKey, myPeerID, targetPeerID)
                        }

                        if (!changed) {
                            val action = if (roleAction == "member") "demote" else "promote"
                            addSystemMessage("couldn't $action @${getPeerNickname(targetPeerID, meshService, viewModel)} in $channelTag.")
                            return null
                        }

                        val nextVersion = channelManager.nextChannelRoleVersion(channelKey)
                        channelManager.buildChannelRolePolicy(channelKey, roleVersion = nextVersion)?.let { payload ->
                            meshService.broadcastChannelRolePolicy(payload)
                        }

                        if (roleAction == "admin") {
                            addSystemMessage("@${getPeerNickname(targetPeerID, meshService, viewModel)} is now admin in $channelTag.")
                        } else if (roleAction == "endorser") {
                            addSystemMessage("@${getPeerNickname(targetPeerID, meshService, viewModel)} is now endorser in $channelTag.")
                        } else {
                            addSystemMessage("@${getPeerNickname(targetPeerID, meshService, viewModel)} is now member in $channelTag.")
                        }
                        null
                    }
                    else -> CommandResult(
                        prefillText = "/channel member ",
                        hintText = "usage: /channel member <remove|admin|endorser|member> @nickname"
                    )
                }
            }
            "owner" -> {
                if (parts.size < 3 || parts[2].lowercase() != "transfer") {
                    return CommandResult(
                        prefillText = "/channel owner transfer ",
                        hintText = "usage: /channel owner transfer [#channel] @nickname"
                    )
                }

                val subject = parts.getOrNull(3)
                if (subject.isNullOrBlank()) {
                    return CommandResult(
                        prefillText = "/channel owner transfer @",
                        hintText = "who should become channel owner?"
                    )
                }

                val looksLikeChannel =
                    subject.startsWith("#") || subject.startsWith("mesh:") || subject.startsWith("geo:")
                val channelArg = if (looksLikeChannel) subject else null
                val nicknameArg = if (looksLikeChannel) parts.getOrNull(4) else subject

                if (nicknameArg.isNullOrBlank()) {
                    val prefill = if (channelArg != null) "/channel owner transfer $channelArg @" else "/channel owner transfer @"
                    return CommandResult(
                        prefillText = prefill,
                        hintText = "who should become channel owner?"
                    )
                }

                val target = requireChannelOwner(
                    channelArg = channelArg,
                    myPeerID = myPeerID,
                    viewModel = viewModel,
                    action = "transfer ownership"
                ) ?: return null

                val (channelTag, channelKey) = target
                val targetName = nicknameArg.removePrefix("@")
                val targetPeerID = getPeerIDForNickname(targetName, meshService, viewModel)
                if (targetPeerID == null) {
                    addSystemMessage("can't find '$targetName' — are they online? check /w")
                    return null
                }

                if (!channelManager.transferChannelOwnership(channelKey, targetPeerID)) {
                    addSystemMessage("couldn't transfer ownership for $channelTag.")
                    return null
                }

                val nextVersion = channelManager.nextChannelRoleVersion(channelKey)
                channelManager.buildChannelRolePolicy(channelKey, roleVersion = nextVersion)?.let { payload ->
                    meshService.broadcastChannelRolePolicy(payload)
                }

                val newOwnerName = getPeerNickname(targetPeerID, meshService, viewModel)
                addSystemMessage("ownership of $channelTag transferred to @$newOwnerName.")
                null
            }
            else -> CommandResult(
                prefillText = "/channel ",
                hintText = "usage: /channel users|gate show|exit ..."
            )
        }
    }

    private fun handleLeaveCommand(
        parts: List<String>,
        myPeerID: String,
        meshService: BluetoothMeshService,
        viewModel: ChatViewModel?
    ): CommandResult? {
        val bridged = mutableListOf("/channel", "exit")
        parts.getOrNull(1)?.let { bridged.add(it) }
        return handleChannelCommand(bridged, myPeerID, meshService, viewModel)
    }

    private fun handleUsersCommand(
        parts: List<String>,
        myPeerID: String,
        meshService: BluetoothMeshService,
        viewModel: ChatViewModel?
    ): CommandResult? {
        val bridged = mutableListOf("/channel", "users")
        parts.getOrNull(1)?.let { bridged.add(it) }
        return handleChannelCommand(bridged, myPeerID, meshService, viewModel)
    }

    private fun handleTransferCommand(
        parts: List<String>,
        myPeerID: String,
        meshService: BluetoothMeshService,
        viewModel: ChatViewModel?
    ): CommandResult? {
        if (parts.size < 2) {
            return CommandResult(
                prefillText = "/transfer @",
                hintText = "who should become channel owner?"
            )
        }

        val second = parts[1]
        val looksLikeChannel =
            second.startsWith("#") || second.startsWith("mesh:") || second.startsWith("geo:")
        val bridged = if (looksLikeChannel) {
            mutableListOf("/channel", "owner", "transfer", second).apply {
                parts.getOrNull(2)?.let { add(it) }
            }
        } else {
            mutableListOf("/channel", "owner", "transfer", second)
        }
        return handleChannelCommand(bridged, myPeerID, meshService, viewModel)
    }

    private fun handleKickCommand(
        parts: List<String>,
        myPeerID: String,
        meshService: BluetoothMeshService,
        viewModel: ChatViewModel?
    ): CommandResult? {
        if (parts.size < 2) {
            return CommandResult(
                prefillText = "/kick @",
                hintText = "who should be removed from the channel?"
            )
        }

        val second = parts[1]
        val looksLikeChannel =
            second.startsWith("#") || second.startsWith("mesh:") || second.startsWith("geo:")
        val bridged = if (looksLikeChannel) {
            mutableListOf("/channel", "member", "remove", second).apply {
                parts.getOrNull(2)?.let { add(it) }
            }
        } else {
            mutableListOf("/channel", "member", "remove", second)
        }
        return handleChannelCommand(bridged, myPeerID, meshService, viewModel)
    }

    private fun handleGateCommand(parts: List<String>, myPeerID: String, viewModel: ChatViewModel?, meshService: BluetoothMeshService): CommandResult? {
        if (parts.size < 2) {
            return CommandResult(
                prefillText = "/gate create #",
                hintText = "usage: /gate [create|status|refresh|remove] ..."
            )
        }

        return when (parts[1].lowercase()) {
            "create" -> {
                if (parts.size < 4) {
                    return CommandResult(
                        prefillText = "/gate create #",
                        hintText = "usage: /gate create #vip <spl|sol|nft-specific|nft-collection> ..."
                    )
                }
                requireChannelAdmin(
                    channelArg = parts[2],
                    myPeerID = myPeerID,
                    viewModel = viewModel,
                    action = "update token gate"
                ) ?: return null
                val channel = parts[2]
                val gateTypeArg = parts[3].lowercase()
                val createArgs = mutableListOf("/create", channel, "--token-gate")
                val shorthandSol = parts[3].toDoubleOrNull()

                when {
                    // Shorthand: /gate create #vip 0.2  => SOL gate with 0.2 SOL minimum
                    shorthandSol != null && shorthandSol > 0.0 -> {
                        createArgs.addAll(listOf("sol", parts[3]))
                    }
                    gateTypeArg == "spl" -> {
                        if (parts.size < 6) {
                            return CommandResult(
                                prefillText = "/gate create #",
                                hintText = "usage: /gate create #vip spl <mint> <amount> [symbol] [decimals]"
                            )
                        }
                        createArgs.addAll(parts.drop(3))
                    }
                    gateTypeArg == "sol" -> {
                        if (parts.size < 5) {
                            return CommandResult(
                                prefillText = "/gate create #",
                                hintText = "usage: /gate create #vip sol <min_sol>"
                            )
                        }
                        createArgs.addAll(listOf("sol", parts[4]))
                    }
                    gateTypeArg in setOf("nft-specific", "nft_specific") -> {
                        if (parts.size < 5) {
                            return CommandResult(
                                prefillText = "/gate create #",
                                hintText = "usage: /gate create #vip nft-specific <mint>"
                            )
                        }
                        createArgs.addAll(listOf("nft-specific", parts[4]))
                    }
                    gateTypeArg in setOf("nft-collection", "nft_collection") -> {
                        if (parts.size < 5) {
                            return CommandResult(
                                prefillText = "/gate create #",
                                hintText = "usage: /gate create #vip nft-collection <collection_mint>"
                            )
                        }
                        createArgs.addAll(listOf("nft-collection", parts[4]))
                    }
                    else -> {
                        return CommandResult(
                            prefillText = "/gate create #",
                            hintText = "usage: /gate create #vip <spl|sol|nft-specific|nft-collection> ..."
                        )
                    }
                }
                handleCreateCommand(createArgs, myPeerID, viewModel, meshService)
            }
            "status" -> {
                val tgs = tokenGateService
                if (tgs == null) {
                    addSystemMessage("token gate service not available yet.")
                    return null
                }
                val target = resolveGateTarget(parts.getOrNull(2), viewModel) ?: return null
                val (channelTag, channelKey) = target
                commandScope.launch {
                    val config = tgs.getTokenGate(channelKey)
                    if (config == null) {
                        addSystemMessage("$channelTag is not token-gated.")
                        return@launch
                    }
                    val symbol = config.tokenSymbol.ifEmpty { "tokens" }
                    val required = tgs.formatTokenAmount(config.minBalance, config.tokenDecimals)
                    val hashShort = if (config.gateHash.length >= 12) config.gateHash.take(12) else config.gateHash
                    val mintDescriptor = if (config.gateType == TokenGateType.SOL_BALANCE) {
                        "native SOL balance gate"
                    } else {
                        "${config.tokenMintAddress.take(8)}..."
                    }
                    addSystemMessage(
                        "gate status for $channelTag\n" +
                            "requirement: $required $symbol\n" +
                            "mint: $mintDescriptor\n" +
                            "policy: v${config.policyVersion} ($hashShort...)"
                    )
                }
                null
            }
            "refresh" -> {
                val tgs = tokenGateService
                if (tgs == null) {
                    addSystemMessage("token gate service not available yet.")
                    return null
                }
                val target = requireChannelAdmin(
                    channelArg = parts.getOrNull(2),
                    myPeerID = myPeerID,
                    viewModel = viewModel,
                    action = "refresh token gate status"
                ) ?: return null
                val (channelTag, channelKey) = target
                commandScope.launch {
                    val config = tgs.getTokenGate(channelKey)
                    if (config == null) {
                        addSystemMessage("$channelTag is not token-gated.")
                        return@launch
                    }
                    val result = tgs.validateEligibility(channelKey, ValidationMode.STRICT_ONLINE)
                    result.onSuccess { validation ->
                        when (validation.decision) {
                            GateDecision.ALLOW -> {
                                addSystemMessage("gate refresh for $channelTag: eligible (${tgs.formatRequirementText(validation)}).")
                            }
                            GateDecision.DENY -> {
                                addSystemMessage("gate refresh for $channelTag: not eligible (${tgs.formatRequirementText(validation)}).")
                            }
                            GateDecision.UNKNOWN_OFFLINE -> {
                                addSystemMessage("gate refresh for $channelTag: offline/unverified.")
                            }
                        }
                    }.onFailure { error ->
                        addSystemMessage("gate refresh failed for $channelTag: ${error.message}")
                    }
                }
                null
            }
            "remove" -> {
                val tgs = tokenGateService
                if (tgs == null) {
                    addSystemMessage("token gate service not available yet.")
                    return null
                }
                val target = requireChannelAdmin(
                    channelArg = parts.getOrNull(2),
                    myPeerID = myPeerID,
                    viewModel = viewModel,
                    action = "remove token gate"
                ) ?: return null
                val (channelTag, channelKey) = target
                commandScope.launch {
                    val config = tgs.getTokenGate(channelKey)
                    if (config == null) {
                        addSystemMessage("$channelTag is not token-gated.")
                        return@launch
                    }
                    val removePayload = com.bitchat.android.solana.TokenGatePolicyPayload.removeFromConfig(config)
                    tgs.removeTokenGate(channelKey)
                    meshService.broadcastTokenGatePolicy(removePayload)
                    addSystemMessage("removed token gate from $channelTag.")
                }
                null
            }
            else -> {
                addSystemMessage("unknown /gate action '${parts[1]}'. try: /gate create|status|refresh|remove")
                null
            }
        }
    }

    private fun resolveGateTarget(channelArg: String?, viewModel: ChatViewModel?): Pair<String, String>? {
        val timeline = viewModel?.selectedLocationChannel?.value

        if (!channelArg.isNullOrBlank()) {
            if (channelArg.startsWith("mesh:") || channelArg.startsWith("geo:")) {
                val key = ChannelKeys.normalize(channelArg)
                return Pair(ChannelKeys.parseChannelName(key), key)
            }
            val channelTag = if (channelArg.startsWith("#")) channelArg else "#$channelArg"
            val preferredKey = ChannelKeys.create(timeline, channelTag)
            val joinedChannels = state.getJoinedChannelsValue()
            if (joinedChannels.contains(preferredKey)) {
                return Pair(channelTag, preferredKey)
            }
            val matchedKey = joinedChannels.firstOrNull { joinedKey ->
                ChannelKeys.parseChannelName(joinedKey).equals(channelTag, ignoreCase = true)
            }
            return Pair(channelTag, matchedKey ?: preferredKey)
        }

        val current = state.getCurrentChannelValue()
        if (current.isNullOrBlank()) {
            addSystemMessage("specify a channel, e.g. /gate status #vip (or switch into a channel first).")
            return null
        }
        val key = ChannelKeys.normalize(current)
        return Pair(ChannelKeys.parseChannelName(key), key)
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

    private fun handleLendingCommand(
        parts: List<String>,
        myPeerID: String,
        viewModel: ChatViewModel?,
        meshService: BluetoothMeshService,
        onSendMessage: (String, List<String>, String?) -> Unit
    ): CommandResult? {
        if (parts.size < 2) {
            return CommandResult(
                prefillText = "/lending ",
                hintText = lendingHint()
            )
        }

        return when (parts[1].lowercase()) {
            "create" -> handleLendingCreate(parts, myPeerID, viewModel, meshService, onSendMessage)
            "join" -> handleLendingJoin(parts, myPeerID, viewModel, meshService, onSendMessage)
            "invite" -> withLendingChannelContext("invite") { handleLendingInvite(parts, viewModel) }
            "import" -> handleLendingImport(parts, myPeerID, viewModel)
            "status" -> withLendingChannelContext("status") { handleLendingStatus(parts, viewModel) }
            "squad" -> withLendingChannelContext("squad configuration") { handleLendingSquad(parts, viewModel, meshService) }
            "request" -> withLendingChannelContext("request") { handleLendingRequest(parts, myPeerID, viewModel, meshService, onSendMessage) }
            "cancel" -> withLendingChannelContext("cancel") { handleLendingCancel(parts, myPeerID, viewModel, onSendMessage) }
            "forward" -> withLendingChannelContext("forward") { handleLendingForward(parts, myPeerID, viewModel, onSendMessage) }
            "vote" -> withLendingChannelContext("vote") { handleLendingVote(parts, myPeerID, viewModel, onSendMessage) }
            "review" -> withLendingChannelContext("review") { handleLendingReview(parts, myPeerID, viewModel, onSendMessage) }
            "authorize" -> withLendingChannelContext("authorize") { handleLendingAuthorize(parts, myPeerID, viewModel, onSendMessage) }
            "disburse" -> withLendingChannelContext("disburse") { handleLendingDisburse(parts, myPeerID, viewModel, onSendMessage) }
            "repay" -> withLendingChannelContext("repay") { handleLendingRepay(parts, myPeerID, onSendMessage) }
            "leave" -> withLendingChannelContext("leave") { handleLendingLeave(parts, myPeerID, viewModel, meshService) }
            else -> CommandResult(
                prefillText = "/lending ",
                hintText = lendingHint()
            )
        }
    }

    private fun withLendingChannelContext(
        action: String,
        block: () -> CommandResult?
    ): CommandResult? {
        if (!state.getCurrentChannelIsLendingValue()) {
            return CommandResult(
                prefillText = "/lending ",
                hintText = "switch into a lending channel before using $action"
            )
        }
        return block()
    }

    private fun handleLendingCreate(
        parts: List<String>,
        myPeerID: String,
        viewModel: ChatViewModel?,
        meshService: BluetoothMeshService,
        onSendMessage: (String, List<String>, String?) -> Unit
    ): CommandResult? {
        if (parts.size < 6) {
            return CommandResult(
                prefillText = "/lending create #",
                hintText = "usage: /lending create #channel <stake_amount> <mint> <minimum_votes> [max_payback_days]"
            )
        }

        val lendingService = lendingChannelService
        val escrowService = lendingEscrowService
        val ws = walletService
        if (lendingService == null || escrowService == null || ws == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }
        if (!ws.hasWallet()) {
            addSystemMessage("you need a wallet first — open settings to create one.")
            return null
        }

        val channelName = parts[2]
        val channel = if (channelName.startsWith("#")) channelName else "#$channelName"
        val mint = parts[4]
        val minimumVotes = parts[5].toIntOrNull()
        val maxLoanDurationDays = parts.getOrNull(6)?.toIntOrNull() ?: 14
        val parsedStake = parseLendingStakeAmount(parts[3], mint)
        if (parsedStake == null || parsedStake.amountAtomic <= 0L) {
            return CommandResult(
                prefillText = "/lending create $channel ",
                hintText = "stake amount must be a positive integer"
            )
        }
        if (minimumVotes == null || minimumVotes <= 0) {
            return CommandResult(
                prefillText = "/lending create $channel ${parts[3]} $mint ",
                hintText = "minimum votes must be a positive integer"
            )
        }
        if (maxLoanDurationDays <= 0) {
            return CommandResult(
                prefillText = "/lending create $channel ${parts[3]} $mint ${parts[5]} ",
                hintText = "max payback days must be a positive integer"
            )
        }

        val timeline = viewModel?.selectedLocationChannel?.value
        val success = channelManager.joinChannel(channel, null, myPeerID, timeline)
        if (!success) return null
        val channelKey = ChannelKeys.create(timeline, channel)
        channelManager.assignChannelCreator(channelKey, myPeerID)
        val walletAddress = ws.getPublicKeyBase58().orEmpty()

        commandScope.launch {
            val lendingChannel = lendingService.createLocalChannel(
                CreateLendingChannelRequest(
                    channelKey = channelKey,
                    displayName = channel,
                    creatorPeerId = myPeerID,
                    creatorWalletAddress = walletAddress,
                    requiredStakeAmount = parsedStake.amountAtomic,
                    minimumVoteCount = minimumVotes,
                    maxLoanDurationDays = maxLoanDurationDays,
                    stakeTokenMint = parsedStake.mint,
                    stakeTokenSymbol = parsedStake.symbol,
                    stakeTokenDecimals = parsedStake.decimals
                )
            )
            if (isNativeSolStakeAsset(lendingChannel.stakeTokenMint, lendingChannel.stakeTokenSymbol) && viewModel != null) {
                publishLendingChannelConfigMessage(
                    buildLendingChannelConfigMessage(lendingChannel),
                    onSendMessage,
                    lendingChannel.channelKey
                )
                viewModel.switchToChannelWithTimelineContext(lendingChannel.channelKey)
                viewModel.requestLendingStakeApproval(lendingChannel.lendingId, myPeerID)
                addSystemMessage(
                    "created lending channel ${lendingChannel.displayName} • id ${lendingChannel.lendingId} • min votes ${lendingChannel.minimumVoteCount} • max payback ${lendingChannel.maxLoanDurationDays} days • review and sign the SOL stake in the drawer"
                )
            } else {
                try {
                    val membership = escrowService.activateMembership(lendingChannel.lendingId, myPeerID)
                    publishLendingChannelConfigMessage(
                        buildLendingChannelConfigMessage(lendingChannel),
                        onSendMessage,
                        lendingChannel.channelKey
                    )
                    publishLendingMembershipMessage(
                        LendingMembershipMessage(
                            lendingId = lendingChannel.lendingId,
                            memberPeerId = myPeerID,
                            walletAddress = ws.getPublicKeyBase58().orEmpty(),
                            stakeAmount = lendingChannel.requiredStakeAmount,
                            depositStatus = membership.depositStatus,
                            joinStatus = membership.joinStatus
                        ),
                        onSendMessage,
                        lendingChannel.channelKey
                    )
                    viewModel?.switchToChannelWithTimelineContext(lendingChannel.channelKey)
                    addSystemMessage(
                        "created lending channel ${lendingChannel.displayName} • id ${lendingChannel.lendingId} • stake ${lendingChannel.requiredStakeAmount} ${lendingChannel.stakeTokenSymbol.ifBlank { "token" }} • min votes ${lendingChannel.minimumVoteCount} • max payback ${lendingChannel.maxLoanDurationDays} days • creator deposit ${membership.depositStatus.lowercase()}"
                    )
                } catch (t: Throwable) {
                    addSystemMessage(
                        "created lending channel ${lendingChannel.displayName} • id ${lendingChannel.lendingId}, but creator stake failed: ${t.message ?: "stake_transfer_failed"}"
                    )
                }
            }
        }
        return null
    }

    private fun handleLendingJoin(
        parts: List<String>,
        myPeerID: String,
        viewModel: ChatViewModel?,
        meshService: BluetoothMeshService,
        onSendMessage: (String, List<String>, String?) -> Unit
    ): CommandResult? {
        if (parts.size < 3) {
            return CommandResult(
                prefillText = "/lending join #",
                hintText = "usage: /lending join [#channel|lendingId]"
            )
        }

        val lendingService = lendingChannelService
        val credibilityService = lendingCredibilityService
        val escrowService = lendingEscrowService
        val ws = walletService
        if (lendingService == null || credibilityService == null || escrowService == null || ws == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }
        if (!ws.hasWallet()) {
            addSystemMessage("you need a wallet first — open settings to create one.")
            return null
        }

        val rawIdentifier = parts[2]
        val channelLikeIdentifier = when {
            rawIdentifier.startsWith("#") ||
                rawIdentifier.startsWith("mesh:") ||
                rawIdentifier.startsWith("geo:") -> rawIdentifier
            else -> "#$rawIdentifier"
        }
        val preferredChannelKey = resolvePreferredLendingChannelKey(channelLikeIdentifier, viewModel)
        commandScope.launch {
            var lendingChannel = lendingService.getChannelByIdentifier(rawIdentifier, preferredChannelKey)
                ?: lendingService.getChannelByIdentifier(channelLikeIdentifier, preferredChannelKey)
            if (lendingChannel == null && channelLikeIdentifier.startsWith("#")) {
                val timeline = viewModel?.selectedLocationChannel?.value
                val joined = channelManager.joinChannel(channelLikeIdentifier, null, myPeerID, timeline)
                if (joined) {
                    val channelKey = ChannelKeys.create(timeline, channelLikeIdentifier)
                    publishLendingChannelConfigRequestMessage(
                        LendingChannelConfigRequestMessage(
                            displayName = channelLikeIdentifier,
                            channelKey = channelKey
                        ),
                        onSendMessage,
                        channelKey
                    )
                    delay(1500)
                    lendingChannel = lendingService.getChannelByIdentifier(rawIdentifier, channelKey)
                        ?: lendingService.getChannelByIdentifier(channelLikeIdentifier, channelKey)
                }
            }
            if (lendingChannel == null) {
                addSystemMessage("couldn't find a lending channel for '$rawIdentifier'.")
                return@launch
            }

            runCatching { escrowService.repairMembershipState(lendingChannel.lendingId, myPeerID) }
            val existingMembership = lendingService.getMemberships(lendingChannel.lendingId)
                .firstOrNull { it.memberPeerId == myPeerID }
            if (existingMembership?.joinStatus == LendingMemberStatus.ACTIVE &&
                existingMembership.depositStatus == com.bitchat.android.data.local.entities.EscrowTransferStatus.CONFIRMED
            ) {
                ensureLendingChannelVisible(lendingChannel.channelKey, myPeerID, viewModel)
                addSystemMessage(
                    "already joined ${lendingChannel.displayName} • id ${lendingChannel.lendingId}: deposit confirmed, status active"
                )
                return@launch
            }
            if (existingMembership?.depositStatus == com.bitchat.android.data.local.entities.EscrowTransferStatus.PENDING) {
                ensureLendingChannelVisible(lendingChannel.channelKey, myPeerID, viewModel)
                addSystemMessage(
                    "stake pending for ${lendingChannel.displayName} • id ${lendingChannel.lendingId}: wait for confirmation before retrying"
                )
                return@launch
            }

            val requiredDebitAmount = requiredJoinDebitAmount(lendingChannel)
            val balance = ws.refreshBalance().getOrElse {
                ws.getCachedBalanceLamports()
            }
            if (balance < requiredDebitAmount) {
                addSystemMessage(
                    "join denied for ${lendingChannel.displayName} • id ${lendingChannel.lendingId}: stake_balance_required (balance $balance, required $requiredDebitAmount)"
                )
                return@launch
            }
            val evaluation = credibilityService.evaluateAndPersist(
                LendingCredibilityRequest(
                    peerId = myPeerID,
                    stakeAmountRequired = requiredDebitAmount,
                    observedStakeBalance = balance,
                    stakeBalanceSatisfied = balance >= requiredDebitAmount
                )
            )

            if (!evaluation.passedHardGates) {
                val reasons = evaluation.hardGateFailures.ifEmpty { listOf("eligibility_failed") }
                addSystemMessage(
                    "join denied for ${lendingChannel.displayName} • id ${lendingChannel.lendingId}: ${reasons.joinToString(", ")} (score ${evaluation.profile.score}/100)"
                )
                return@launch
            }

            lendingService.recordPendingMembership(
                RecordPendingMembershipRequest(
                    lendingId = lendingChannel.lendingId,
                    memberPeerId = myPeerID,
                    walletAddress = ws.getPublicKeyBase58().orEmpty(),
                    stakeAmount = lendingChannel.requiredStakeAmount,
                    credibilityScore = evaluation.profile.score,
                    credibilitySnapshotJson = evaluation.profile.snapshotJson
                )
            )
            if (isNativeSolStakeAsset(lendingChannel.stakeTokenMint, lendingChannel.stakeTokenSymbol) && viewModel != null) {
                viewModel.requestLendingStakeApproval(lendingChannel.lendingId, myPeerID)
                addSystemMessage(
                    "join pending for ${lendingChannel.displayName} • id ${lendingChannel.lendingId}: review and sign the SOL stake in the drawer"
                )
            } else {
                try {
                    val activated = escrowService.activateMembership(lendingChannel.lendingId, myPeerID)
                    publishLendingMembershipMessage(
                        LendingMembershipMessage(
                            lendingId = lendingChannel.lendingId,
                            memberPeerId = myPeerID,
                            walletAddress = ws.getPublicKeyBase58().orEmpty(),
                            stakeAmount = lendingChannel.requiredStakeAmount,
                            depositStatus = activated.depositStatus,
                            joinStatus = activated.joinStatus
                        ),
                        onSendMessage,
                        lendingChannel.channelKey
                    )
                    viewModel?.switchToChannelWithTimelineContext(lendingChannel.channelKey)
                    addSystemMessage(
                        "joined ${lendingChannel.displayName} • id ${lendingChannel.lendingId}: score ${evaluation.profile.score}/100, deposit ${activated.depositStatus.lowercase()}, status ${activated.joinStatus.lowercase()}"
                    )
                } catch (t: Throwable) {
                    addSystemMessage(
                        "join failed for ${lendingChannel.displayName} • id ${lendingChannel.lendingId}: ${t.message ?: "stake_transfer_failed"}"
                    )
                }
            }
        }
        return null
    }

    private fun handleLendingInvite(
        parts: List<String>,
        viewModel: ChatViewModel?
    ): CommandResult? {
        val lendingService = lendingChannelService
        if (lendingService == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }
        val identifier = parts.getOrNull(2) ?: state.getCurrentChannelValue()?.let { ChannelKeys.parseChannelName(it) }
        if (identifier.isNullOrBlank()) {
            return CommandResult(
                prefillText = "/lending invite ",
                hintText = "usage: /lending invite [#channel|lendingId]"
            )
        }
        val preferredChannelKey = resolvePreferredLendingChannelKey(identifier, viewModel)
        commandScope.launch {
            val channel = lendingService.getChannelByIdentifier(identifier, preferredChannelKey)
            if (channel == null) {
                addSystemMessage("no lending channel found for '$identifier'.")
                return@launch
            }
            val creatorMembership = lendingService.getMemberships(channel.lendingId)
                .firstOrNull { it.memberPeerId == channel.creatorPeerId }
            val invite = LendingChannelInviteCodec.encode(
                LendingChannelInvite(
                    lendingId = channel.lendingId,
                    displayName = channel.displayName,
                    creatorPeerId = channel.creatorPeerId,
                    creatorWalletAddress = channel.creatorWalletAddress,
                    requiredStakeAmount = channel.requiredStakeAmount,
                    minimumVoteCount = channel.minimumVoteCount,
                    maxLoanDurationDays = channel.maxLoanDurationDays,
                    stakeTokenMint = channel.stakeTokenMint,
                    stakeTokenSymbol = channel.stakeTokenSymbol,
                    stakeTokenDecimals = channel.stakeTokenDecimals,
                    creatorMembershipConfirmed = creatorMembership?.joinStatus == LendingMemberStatus.ACTIVE &&
                        creatorMembership.depositStatus == com.bitchat.android.data.local.entities.EscrowTransferStatus.CONFIRMED
                )
            )
            addSystemMessage("lending invite for ${channel.displayName}: $invite")
        }
        return null
    }

    private fun handleLendingImport(
        parts: List<String>,
        myPeerID: String,
        viewModel: ChatViewModel?
    ): CommandResult? {
        if (parts.size < 3) {
            return CommandResult(
                prefillText = "/lending import ",
                hintText = "usage: /lending import <invite_code>"
            )
        }
        val lendingService = lendingChannelService
        if (lendingService == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }
        val invite = LendingChannelInviteCodec.decode(parts[2])
        if (invite == null) {
            addSystemMessage("invalid lending invite code.")
            return null
        }
        val channelName = if (invite.displayName.startsWith("#")) invite.displayName else "#${invite.displayName}"
        val timeline = viewModel?.selectedLocationChannel?.value
        val joined = channelManager.joinChannel(channelName, null, myPeerID, timeline)
        if (!joined) return null
        val channelKey = ChannelKeys.create(timeline, channelName)
        commandScope.launch {
            try {
                val channel = lendingService.importSharedChannel(
                    ImportLendingChannelRequest(
                        lendingId = invite.lendingId,
                        channelKey = channelKey,
                        displayName = channelName,
                        creatorPeerId = invite.creatorPeerId,
                        creatorWalletAddress = invite.creatorWalletAddress,
                        requiredStakeAmount = invite.requiredStakeAmount,
                        minimumVoteCount = invite.minimumVoteCount,
                        maxLoanDurationDays = invite.maxLoanDurationDays,
                        stakeTokenMint = invite.stakeTokenMint,
                        stakeTokenSymbol = invite.stakeTokenSymbol,
                        stakeTokenDecimals = invite.stakeTokenDecimals,
                        seedCreatorMembership = false
                    )
                )
                viewModel?.switchToChannelWithTimelineContext(channel.channelKey)
                addSystemMessage(
                    "imported lending channel ${channel.displayName} • id ${channel.lendingId} • min votes ${channel.minimumVoteCount} • max payback ${channel.maxLoanDurationDays} days. creator liquidity is unverified until treasury reconciliation; now run /lending join ${channel.displayName}"
                )
            } catch (error: Exception) {
                addSystemMessage("couldn't import lending channel: ${error.message ?: "import_failed"}")
            }
        }
        return null
    }

    private fun handleLendingStatus(
        parts: List<String>,
        viewModel: ChatViewModel?
    ): CommandResult? {
        val lendingService = lendingChannelService
        if (lendingService == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }

        val identifier = parts.getOrNull(2) ?: state.getCurrentChannelValue()?.let { ChannelKeys.parseChannelName(it) }
        if (identifier.isNullOrBlank()) {
            return CommandResult(
                prefillText = "/lending status ",
                hintText = "usage: /lending status [#channel|lendingId]"
            )
        }
        val preferredChannelKey = resolvePreferredLendingChannelKey(identifier, viewModel)
        commandScope.launch {
            val status = lendingService.getStatus(identifier, preferredChannelKey)
            if (status == null) {
                addSystemMessage("no lending channel found for '$identifier'.")
                return@launch
            }
            val activeMembers = status.memberships.count {
                it.joinStatus == LendingMemberStatus.ACTIVE || it.joinStatus == LendingMemberStatus.PENDING
            }
            val pool = status.poolSnapshot
            val escrowAccount = lendingEscrowService?.getEscrowAccount(status.channel.lendingId)
            addSystemMessage(
                buildString {
                    append("lending status for ${status.channel.displayName} • id ${status.channel.lendingId}\n")
                    append("stake: ${status.channel.requiredStakeAmount} ${status.channel.stakeTokenSymbol.ifBlank { status.channel.stakeTokenMint.take(8) }}\n")
                    append("minimum votes: ${status.channel.minimumVoteCount}\n")
                    append("max payback: ${status.channel.maxLoanDurationDays} days\n")
                    append("members: $activeMembers\n")
                    append("treasury: ${escrowAccount?.provider?.lowercase() ?: "app_treasury"}\n")
                    append("escrow: ${status.channel.escrowMultisigAddress.ifBlank { "app-treasury" }}\n")
                    append("vault: ${escrowAccount?.vaultAddress?.ifBlank { "pending-provision" } ?: "pending-provision"}\n")
                    append("pool: total ${pool?.totalStakedAmount ?: 0}, available ${pool?.availableLiquidityAmount ?: 0}, reserved ${pool?.reservedAmount ?: 0}, disbursed ${pool?.disbursedAmount ?: 0}\n")
                    append("active loans: ${status.activeLoanCount}\n")
                    if (status.unreconciledActiveMemberCount > 0) {
                        append("reconciliation warning: ${status.unreconciledActiveMemberCount} active member(s) missing recorded stake deposit\n")
                    }
                    append("credibility threshold: $DEFAULT_CREDIBILITY_THRESHOLD/100")
                }
            )
        }
        return null
    }

    private fun handleLendingSquad(
        parts: List<String>,
        viewModel: ChatViewModel?,
        meshService: BluetoothMeshService
    ): CommandResult? {
        val lendingService = lendingChannelService
        if (lendingService == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }
        if (parts.size < 3) {
            return CommandResult(
                prefillText = "/lending squad ",
                hintText = "usage: /lending squad [#channel|lendingId] <multisig> [vault]"
            )
        }

        val identifier: String
        val multisigAddress: String
        val vaultAddress: String?
        if (parts.size == 3) {
            identifier = state.getCurrentChannelValue()?.let { ChannelKeys.parseChannelName(it) }
                ?: return CommandResult(
                    prefillText = "/lending squad ",
                    hintText = "usage: /lending squad [#channel|lendingId] <multisig> [vault]"
                )
            multisigAddress = parts[2]
            vaultAddress = null
        } else {
            val explicitIdentifier = parts[2]
            val currentChannelName = state.getCurrentChannelValue()?.let { ChannelKeys.parseChannelName(it) }
            val looksLikeIdentifier = explicitIdentifier.startsWith("#") ||
                explicitIdentifier.startsWith("mesh:") ||
                explicitIdentifier.startsWith("geo:") ||
                explicitIdentifier.equals(currentChannelName, ignoreCase = true)
            if (looksLikeIdentifier) {
                identifier = explicitIdentifier
                multisigAddress = parts[3]
                vaultAddress = parts.getOrNull(4)
            } else {
                identifier = currentChannelName ?: return CommandResult(
                    prefillText = "/lending squad ",
                    hintText = "usage: /lending squad [#channel|lendingId] <multisig> [vault]"
                )
                multisigAddress = explicitIdentifier
                vaultAddress = parts.getOrNull(3)
            }
        }

        val preferredChannelKey = resolvePreferredLendingChannelKey(identifier, viewModel)
        commandScope.launch {
            try {
                val channel = lendingService.configureSquad(
                    ConfigureLendingSquadRequest(
                        identifier = identifier,
                        preferredChannelKey = preferredChannelKey,
                        actorPeerId = meshService.myPeerID,
                        multisigAddress = multisigAddress,
                        vaultAddress = vaultAddress
                    )
                )
                val escrowAccount = lendingEscrowService?.getEscrowAccount(channel.lendingId)
                addSystemMessage(
                    "configured Squad for ${channel.displayName} • multisig ${channel.escrowMultisigAddress} • vault ${escrowAccount?.vaultAddress ?: "unknown"}"
                )
            } catch (error: Exception) {
                addSystemMessage("couldn't configure Squad: ${friendlyLendingSquadError(error)}")
            }
        }
        return null
    }

    private fun handleLendingRequest(
        parts: List<String>,
        myPeerID: String,
        viewModel: ChatViewModel?,
        meshService: BluetoothMeshService,
        onSendMessage: (String, List<String>, String?) -> Unit
    ): CommandResult? {
        val loanService = lendingLoanService
        if (loanService == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }

        if (parts.size < 5) {
            return CommandResult(
                prefillText = "/lending request ",
                hintText = lendingRequestHint()
            )
        }

        val firstArg = parts.getOrNull(2)
        val firstArgLooksLikeAmount = firstArg
            ?.trim()
            ?.replace(",", "")
            ?.toBigDecimalOrNull()
            ?.let { it > BigDecimal.ZERO } == true
        val hasExplicitIdentifier = !firstArgLooksLikeAmount
        val target = resolveCurrentLendingTarget(
            identifier = if (hasExplicitIdentifier) firstArg else null,
            viewModel = viewModel
        )
        val identifier = target?.first
        val argsStart = if (hasExplicitIdentifier) 3 else 2
        if (identifier.isNullOrBlank() || parts.size <= argsStart + 1) {
            return CommandResult(
                prefillText = "/lending request ",
                hintText = lendingRequestHint()
            )
        }

        val preferredChannelKey = target?.second ?: resolvePreferredLendingChannelKey(identifier, viewModel)
        commandScope.launch {
            try {
                val lendingService = lendingChannelService
                    ?: throw IllegalStateException("lending_unavailable")
                val currentChannelKey = state.getCurrentChannelValue()
                    ?.let { ChannelKeys.normalize(it) }
                val channel = when {
                    identifier == null -> null
                    !currentChannelKey.isNullOrBlank() && preferredChannelKey == currentChannelKey ->
                        lendingService.getChannelByChannelKey(currentChannelKey)
                            ?: lendingService.getChannelByIdentifier(identifier, preferredChannelKey)
                    else -> lendingService.getChannelByIdentifier(identifier, preferredChannelKey)
                }
                    ?: throw IllegalArgumentException("lending_channel_not_found")
                val parsedAmount = parseLendingLoanAmount(parts.drop(argsStart), channel)
                    ?: throw IllegalArgumentException("invalid_loan_amount")
                val durationIndex = argsStart + parsedAmount.consumedTokenCount
                val durationDays = parts.getOrNull(durationIndex)?.toIntOrNull()
                    ?: throw IllegalArgumentException("invalid_loan_duration")
                if (durationDays <= 0) throw IllegalArgumentException("invalid_loan_duration")
                if (durationDays > channel.maxLoanDurationDays) {
                    throw IllegalArgumentException("loan_duration_exceeds_channel_max")
                }
                val endorserTokens = mutableListOf<String>()
                var cursor = durationIndex + 1
                while (cursor < parts.size && parts[cursor].startsWith("@")) {
                    endorserTokens += parts[cursor].removePrefix("@")
                    cursor++
                }
                val purpose = parts.drop(cursor).joinToString(" ").trim()
                if (purpose.isBlank()) {
                    throw IllegalArgumentException("missing_loan_purpose")
                }
                val endorserPeerIds = endorserTokens.distinct().map { endorserName ->
                    val peerId = getPeerIDForNickname(endorserName, meshService, viewModel)
                        ?: throw IllegalArgumentException("endorser_not_found:$endorserName")
                    val role = channelManager.getChannelRole(channel.channelKey, peerId)
                    if (role !in setOf(ChannelRoles.OWNER, ChannelRoles.ADMIN, ChannelRoles.ENDORSER)) {
                        throw IllegalArgumentException("endorser_role_required:$endorserName")
                    }
                    peerId
                }
                val loan = loanService.createLoanRequest(
                    CreateLoanRequest(
                        identifier = channel.lendingId,
                        preferredChannelKey = channel.channelKey,
                        requesterPeerId = myPeerID,
                        borrowerType = BorrowerType.INDIVIDUAL,
                        principalAmount = parsedAmount.amountAtomic,
                        durationDays = durationDays,
                        purpose = purpose,
                        endorserPeerIds = endorserPeerIds,
                        interestBps = DEFAULT_INTEREST_BPS
                    )
                )
                publishLendingLoanRequestMessage(
                    buildLoanRequestMessage(loan, channel, myPeerID, state.getNicknameValue()),
                    myPeerID = myPeerID,
                    onSendMessage = onSendMessage,
                    targetChannelKey = channel.channelKey
                )
                viewModel?.updateLendingLoanRequestStatus(loan.requestId, loan.status)
            } catch (error: Exception) {
                addSystemMessage("couldn't create loan request: ${friendlyLendingRequestError(error)}")
            }
        }
        return null
    }

    private fun handleLendingVote(
        parts: List<String>,
        myPeerID: String,
        viewModel: ChatViewModel?,
        onSendMessage: (String, List<String>, String?) -> Unit
    ): CommandResult? {
        val loanService = lendingLoanService
        if (loanService == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }
        if (parts.size < 4) {
            return CommandResult(
                prefillText = "/lending vote ",
                hintText = "usage: /lending vote <request_id> approve|deny"
            )
        }

        val requestId = parts[2]
        val choice = when (parts[3].lowercase()) {
            "yes", "approve", "upvote" -> VoteChoice.YES
            "no", "deny", "reject", "downvote" -> VoteChoice.NO
            else -> return CommandResult(
                prefillText = "/lending vote $requestId ",
                hintText = "vote must be approve or deny"
            )
        }

        commandScope.launch {
            try {
                val result = loanService.castVote(
                    CastLoanVoteRequest(
                        requestId = requestId,
                        voterPeerId = myPeerID,
                        voteChoice = choice
                    )
                )
                val approvalCount = countLoanApprovals(result.votes)
                val rejectionCount = countLoanRejections(result.votes)
                val suffix = when {
                    result.request.status == com.bitchat.android.data.local.entities.LoanRequestStatus.DISBURSED ->
                        "loan approved and disbursed"
                    result.approved -> "community approved"
                    result.rejected -> "community rejected"
                    result.quorumReached -> "quorum reached, voting still open"
                    else -> "vote recorded"
                }
                viewModel?.updateLendingLoanRequestStatus(result.request.requestId, result.request.status)
                publishLendingLoanVoteMessage(
                    LendingLoanVoteMessage(
                        requestId = result.request.requestId,
                        lendingId = result.request.lendingId,
                        voterPeerId = myPeerID,
                        voterLabel = state.getNicknameValue(),
                        voteChoice = choice,
                        yesVotes = approvalCount,
                        noVotes = 0,
                        requestStatus = result.request.status,
                        approvedAt = result.request.approvedAt,
                        disbursedAt = result.request.disbursedAt,
                        squadsMultisigAddress = result.request.squadsMultisigAddress,
                        squadsVaultAddress = result.request.squadsVaultAddress,
                        squadsProposalAddress = result.request.squadsProposalAddress,
                        squadsTransactionIndex = result.request.squadsTransactionIndex
                    ),
                    onSendMessage = onSendMessage,
                    targetChannelKey = lendingChannelService
                        ?.getChannelByLendingId(result.request.lendingId)
                        ?.channelKey
                )
                addSystemMessage(
                    "${result.request.requestId}: yes $approvalCount • no $rejectionCount • $suffix"
                )
            } catch (error: Exception) {
                addSystemMessage("couldn't record vote: ${friendlyLendingVoteError(error)}")
            }
        }
        return null
    }

    private fun handleLendingForward(
        parts: List<String>,
        myPeerID: String,
        viewModel: ChatViewModel?,
        onSendMessage: (String, List<String>, String?) -> Unit
    ): CommandResult? {
        val loanService = lendingLoanService
        val lendingService = lendingChannelService
        if (loanService == null || lendingService == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }
        if (parts.size < 4) {
            return CommandResult(
                prefillText = "/lending forward ",
                hintText = "usage: /lending forward <request_id> <#channel|lendingId>"
            )
        }

        val requestId = parts[2]
        val destinationIdentifier = parts[3]
        val preferredChannelKey = resolvePreferredLendingChannelKey(destinationIdentifier, viewModel)
        commandScope.launch {
            try {
                val forwarded = loanService.forwardLoanRequest(
                    ForwardLoanRequest(
                        requestId = requestId,
                        destinationIdentifier = destinationIdentifier,
                        preferredChannelKey = preferredChannelKey,
                        actorPeerId = myPeerID
                    )
                )
                val destinationChannel = lendingService.getChannelByLendingId(forwarded.lendingId)
                    ?: throw IllegalStateException("lending_channel_not_found")
                publishLendingLoanRequestMessage(
                    content = buildLoanRequestMessage(forwarded, destinationChannel, myPeerID),
                    myPeerID = myPeerID,
                    onSendMessage = onSendMessage,
                    targetChannelKey = destinationChannel.channelKey
                )
                viewModel?.updateLendingLoanRequestStatus(forwarded.requestId, forwarded.status)
                addSystemMessage("${forwarded.requestId}: forwarded to ${destinationChannel.displayName}")
            } catch (error: Exception) {
                addSystemMessage("couldn't forward loan request: ${friendlyLendingForwardError(error)}")
            }
        }
        return null
    }

    private fun handleLendingCancel(
        parts: List<String>,
        myPeerID: String,
        viewModel: ChatViewModel?,
        onSendMessage: (String, List<String>, String?) -> Unit
    ): CommandResult? {
        val loanService = lendingLoanService
        val lendingService = lendingChannelService
        if (loanService == null || lendingService == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }
        if (parts.size < 3) {
            return CommandResult(
                prefillText = "/lending cancel ",
                hintText = "usage: /lending cancel <request_id>"
            )
        }

        val requestId = parts[2]
        commandScope.launch {
            try {
                val loan = loanService.getLoanRequest(requestId)
                    ?: throw IllegalArgumentException("loan_request_not_found")
                val channel = lendingService.getChannelByLendingId(loan.lendingId)
                    ?: throw IllegalStateException("lending_channel_not_found")
                val actorIsBorrower = loan.borrowerPeerId == myPeerID
                val actorIsAdmin = channelManager.isChannelAdmin(channel.channelKey, myPeerID)
                if (!actorIsBorrower && !actorIsAdmin) {
                    throw IllegalStateException("borrower_or_admin_only_cancellation")
                }
                val result = loanService.cancelLoanRequest(
                    CancelLoanRequest(
                        requestId = requestId,
                        actorPeerId = myPeerID,
                        actorIsAdmin = actorIsAdmin
                    )
                )
                result.affectedRequests.forEach { cancelled ->
                    viewModel?.updateLendingLoanRequestStatus(cancelled.requestId, cancelled.status)
                    lendingService.getChannelByLendingId(cancelled.lendingId)?.let { linkedChannel ->
                        publishLendingLoanRequestMessage(
                            content = buildLoanRequestMessage(cancelled, linkedChannel, myPeerID, myPeerID),
                            myPeerID = myPeerID,
                            onSendMessage = onSendMessage,
                            targetChannelKey = linkedChannel.channelKey
                        )
                    }
                }
                addSystemMessage("${result.request.requestId}: loan request cancelled")
            } catch (error: Exception) {
                addSystemMessage("couldn't cancel loan request: ${friendlyLendingCancelError(error)}")
            }
        }
        return null
    }

    private fun handleLendingReview(
        parts: List<String>,
        myPeerID: String,
        viewModel: ChatViewModel?,
        onSendMessage: (String, List<String>, String?) -> Unit
    ): CommandResult? {
        val loanService = lendingLoanService
        val lendingService = lendingChannelService
        if (loanService == null || lendingService == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }
        if (parts.size < 3) {
            return CommandResult(
                prefillText = "/lending review ",
                hintText = "usage: /lending review <request_id>"
            )
        }

        val requestId = parts[2]
        commandScope.launch {
            try {
                val loan = loanService.getLoanRequest(requestId)
                    ?: throw IllegalArgumentException("loan_request_not_found")
                val channel = lendingService.getChannelByLendingId(loan.lendingId)
                    ?: throw IllegalStateException("lending_channel_not_found")
                if (!channelManager.isChannelAdmin(channel.channelKey, myPeerID)) {
                    throw IllegalStateException("admin_only_signer_review")
                }
                val result = loanService.openSignerReview(
                    OpenSignerReviewRequest(
                        requestId = requestId,
                        actorPeerId = myPeerID,
                        actorIsAdmin = true
                    )
                )
                viewModel?.updateLendingLoanRequestStatus(result.request.requestId, result.request.status)
                publishLendingLoanRequestMessage(
                    content = buildLoanRequestMessage(result.request, channel, myPeerID, myPeerID),
                    myPeerID = myPeerID,
                    onSendMessage = onSendMessage,
                    targetChannelKey = channel.channelKey
                )
                val reviewState = if (result.created) {
                    "signer review opened"
                } else {
                    "signer review already open"
                }
                val proposalSuffix = result.review.squadsProposalAddress?.let { " • proposal $it" }.orEmpty()
                addSystemMessage("${result.request.requestId}: $reviewState$proposalSuffix")
            } catch (error: Exception) {
                addSystemMessage("couldn't open signer review: ${friendlyLendingSignerReviewError(error)}")
            }
        }
        return null
    }

    private fun handleLendingAuthorize(
        parts: List<String>,
        myPeerID: String,
        viewModel: ChatViewModel?,
        onSendMessage: (String, List<String>, String?) -> Unit
    ): CommandResult? {
        val loanService = lendingLoanService
        val lendingService = lendingChannelService
        if (loanService == null || lendingService == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }
        if (parts.size < 3) {
            return CommandResult(
                prefillText = "/lending authorize ",
                hintText = "usage: /lending authorize <request_id>"
            )
        }

        val requestId = parts[2]
        commandScope.launch {
            try {
                val loan = loanService.getLoanRequest(requestId)
                    ?: throw IllegalArgumentException("loan_request_not_found")
                val channel = lendingService.getChannelByLendingId(loan.lendingId)
                    ?: throw IllegalStateException("lending_channel_not_found")
                val actorRole = channelManager.getChannelRole(channel.channelKey, myPeerID)
                if (actorRole !in setOf(ChannelRoles.OWNER, ChannelRoles.ADMIN, ChannelRoles.ENDORSER)) {
                    throw IllegalStateException("approver_only_signer_authorization")
                }
                val result = loanService.authorizeSignerReview(
                    AuthorizeSignerReviewRequest(
                        requestId = requestId,
                        actorPeerId = myPeerID,
                        actorIsApprover = true
                    )
                )
                viewModel?.updateLendingLoanRequestStatus(result.request.requestId, result.request.status)
                publishLendingLoanRequestMessage(
                    content = buildLoanRequestMessage(result.request, channel, myPeerID, myPeerID),
                    myPeerID = myPeerID,
                    onSendMessage = onSendMessage,
                    targetChannelKey = channel.channelKey
                )
                val statusLabel = when (result.request.status) {
                    com.bitchat.android.data.local.entities.LoanRequestStatus.SIGNER_APPROVED -> "signer authorization threshold reached"
                    else -> "signer authorization recorded"
                }
                val proposalSuffix = result.review.squadsProposalAddress?.let { " • proposal $it" }.orEmpty()
                addSystemMessage("${result.request.requestId}: $statusLabel$proposalSuffix")
            } catch (error: Exception) {
                addSystemMessage("couldn't authorize signer review: ${friendlyLendingSignerAuthorizationError(error)}")
            }
        }
        return null
    }

    private fun handleLendingRepay(
        parts: List<String>,
        myPeerID: String,
        onSendMessage: (String, List<String>, String?) -> Unit
    ): CommandResult? {
        val loanService = lendingLoanService
        if (loanService == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }
        if (parts.size < 4) {
            return CommandResult(
                prefillText = "/lending repay ",
                hintText = "usage: /lending repay <request_id> <amount> [asset] • e.g. /lending repay abc123 0.25 SOL"
            )
        }

        val requestId = parts[2]

        commandScope.launch {
            try {
                val request = loanService.getLoanRequest(requestId)
                    ?: throw IllegalArgumentException("loan_request_not_found")
                val channel = lendingChannelService?.getChannelByLendingId(request.lendingId)
                    ?: throw IllegalStateException("lending_channel_not_found")
                val parsedAmount = parseLendingRepaymentAmount(parts.drop(3), channel)
                    ?: throw IllegalArgumentException("invalid_repayment_amount")
                val result = loanService.repayLoan(
                    RecordLoanRepaymentRequest(
                        requestId = requestId,
                        payerPeerId = myPeerID,
                        amount = parsedAmount.amountAtomic
                    )
                )
                publishLendingLoanRepaymentMessage(
                    LendingLoanRepaymentMessage(
                        repaymentId = result.repayment.repaymentId,
                        requestId = result.updatedRequest.requestId,
                        lendingId = result.updatedRequest.lendingId,
                        payerPeerId = myPeerID,
                        payerLabel = state.getNicknameValue(),
                        amount = result.repayment.amount,
                        txSignature = result.repayment.txSignature,
                        txStatus = result.repayment.txStatus,
                        totalRepaidAmount = result.totalRepaidAmount,
                        remainingBalance = result.remainingBalance,
                        requestStatus = result.updatedRequest.status,
                        paidAt = result.repayment.paidAt
                    ),
                    onSendMessage = onSendMessage,
                    targetChannelKey = channel.channelKey
                )
                val repaymentState = if (result.repayment.txStatus == com.bitchat.android.data.local.entities.EscrowTransferStatus.CONFIRMED) {
                    "recorded"
                } else {
                    "queued"
                }
                addSystemMessage(
                    "repayment $repaymentState for ${result.updatedRequest.requestId}: paid ${formatDisplayAmount(result.repayment.amount, channel.stakeTokenDecimals)} ${repaymentAssetSymbol(channel)}, confirmed remaining ${formatDisplayAmount(result.remainingBalance, channel.stakeTokenDecimals)} ${repaymentAssetSymbol(channel)}, status ${result.updatedRequest.status.lowercase()}"
                )
            } catch (error: Exception) {
                addSystemMessage("couldn't record repayment: ${friendlyLendingRepaymentError(error, requestId)}")
            }
        }
        return null
    }

    private fun handleLendingDisburse(
        parts: List<String>,
        myPeerID: String,
        viewModel: ChatViewModel?,
        onSendMessage: (String, List<String>, String?) -> Unit
    ): CommandResult? {
        val loanService = lendingLoanService
        val lendingService = lendingChannelService
        if (loanService == null || lendingService == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }
        if (parts.size < 3) {
            return CommandResult(
                prefillText = "/lending disburse ",
                hintText = "usage: /lending disburse <request_id>"
            )
        }

        val requestId = parts[2]
        commandScope.launch {
            try {
                val loan = loanService.getLoanRequest(requestId)
                    ?: throw IllegalArgumentException("loan_request_not_found")
                val channel = lendingService.getChannelByLendingId(loan.lendingId)
                    ?: throw IllegalStateException("lending_channel_not_found")
                if (!channelManager.isChannelAdmin(channel.channelKey, myPeerID)) {
                    throw IllegalStateException("admin_only_disbursement")
                }
                val updated = loanService.disburseApprovedLoan(
                    DisburseApprovedLoanRequest(
                        requestId = requestId,
                        actorPeerId = myPeerID,
                        actorIsAdmin = true
                    )
                )
                val linkedRequests = loanService.getLinkedLoanRequests(updated.requestId)
                linkedRequests.forEach { linked ->
                    viewModel?.updateLendingLoanRequestStatus(linked.requestId, linked.status)
                    lendingService.getChannelByLendingId(linked.lendingId)?.let { linkedChannel ->
                        publishLendingLoanRequestMessage(
                            content = buildLoanRequestMessage(linked, linkedChannel, myPeerID, myPeerID),
                            myPeerID = myPeerID,
                            onSendMessage = onSendMessage,
                            targetChannelKey = linkedChannel.channelKey
                        )
                    }
                }
                val statusLabel = when (updated.status) {
                    com.bitchat.android.data.local.entities.LoanRequestStatus.DISBURSED -> "loan disbursed"
                    else -> "disbursement queued"
                }
                viewModel?.updateLendingLoanRequestStatus(updated.requestId, updated.status)
                addSystemMessage("${updated.requestId}: $statusLabel")
            } catch (error: Exception) {
                addSystemMessage("couldn't disburse loan: ${friendlyLendingDisbursementError(error)}")
            }
        }
        return null
    }

    private fun handleLendingLeave(
        parts: List<String>,
        myPeerID: String,
        viewModel: ChatViewModel?,
        meshService: BluetoothMeshService
    ): CommandResult? {
        val loanService = lendingLoanService
        val lendingService = lendingChannelService
        if (loanService == null || lendingService == null) {
            addSystemMessage("lending is not available yet — try again in a moment.")
            return null
        }

        val identifier = parts.getOrNull(2) ?: state.getCurrentChannelValue()?.let { ChannelKeys.parseChannelName(it) }
        if (identifier.isNullOrBlank()) {
            return CommandResult(
                prefillText = "/lending leave ",
                hintText = "usage: /lending leave [#channel|lendingId]"
            )
        }

        val preferredChannelKey = resolvePreferredLendingChannelKey(identifier, viewModel)
        commandScope.launch {
            try {
                val result = loanService.leaveChannel(
                    LeaveLendingChannelRequest(
                        identifier = identifier,
                        preferredChannelKey = preferredChannelKey,
                        memberPeerId = myPeerID
                    )
                )
                lendingService.getChannelByLendingId(result.membership.lendingId)?.let { channel ->
                    channelManager.leaveChannel(channel.channelKey)
                }
                addSystemMessage(
                    "left lending channel ${result.membership.lendingId}: stake ${result.membership.depositStatus.lowercase()}, membership ${result.membership.joinStatus.lowercase()}"
                )
            } catch (error: Exception) {
                val channel = runCatching {
                    lendingService.getChannelByIdentifier(identifier, preferredChannelKey)
                }.getOrNull()
                if (channel != null && viewModel != null) {
                    viewModel.leaveChannel(channel.channelKey)
                    addSystemMessage("prepared leave for ${channel.displayName}: review the refund approval in the drawer.")
                } else {
                    addSystemMessage("couldn't leave lending channel: ${error.message}")
                }
            }
        }
        return null
    }

    private fun resolvePreferredLendingChannelKey(identifier: String, viewModel: ChatViewModel?): String? {
        if (!identifier.startsWith("#")) return null
        val timeline = viewModel?.selectedLocationChannel?.value
        return ChannelKeys.create(timeline, identifier)
    }

    private fun resolveCurrentLendingTarget(
        identifier: String?,
        viewModel: ChatViewModel?
    ): Pair<String, String?>? {
        if (!identifier.isNullOrBlank()) {
            if (identifier.startsWith("mesh:") || identifier.startsWith("geo:")) {
                val key = ChannelKeys.normalize(identifier)
                return Pair(ChannelKeys.parseChannelName(key), key)
            }
            val channelTag = if (identifier.startsWith("#")) identifier else "#$identifier"
            val currentChannel = state.getCurrentChannelValue()
            if (!currentChannel.isNullOrBlank() &&
                ChannelKeys.parseChannelName(ChannelKeys.normalize(currentChannel)).equals(channelTag, ignoreCase = true)
            ) {
                return Pair(channelTag, ChannelKeys.normalize(currentChannel))
            }
            return Pair(channelTag, resolvePreferredLendingChannelKey(channelTag, viewModel))
        }

        val current = state.getCurrentChannelValue() ?: return null
        val key = ChannelKeys.normalize(current)
        return Pair(ChannelKeys.parseChannelName(key), key)
    }

    private fun ensureLendingChannelVisible(
        channelKey: String,
        myPeerID: String,
        viewModel: ChatViewModel?
    ) {
        val normalizedKey = ChannelKeys.normalize(channelKey)
        val timeline = when {
            ChannelKeys.isGeo(normalizedKey) -> {
                val geohash = ChannelKeys.parseGeohash(normalizedKey) ?: return
                ChannelID.Location(GeohashChannel(ChannelKeys.levelForGeohashLength(geohash.length), geohash))
            }
            else -> ChannelID.Mesh
        }
        channelManager.joinChannel(ChannelKeys.parseChannelName(normalizedKey), null, myPeerID, timeline)
        viewModel?.switchToChannelWithTimelineContext(normalizedKey)
    }

    private fun handleCreateCommand(parts: List<String>, myPeerID: String, viewModel: ChatViewModel?, meshService: BluetoothMeshService): CommandResult? {
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

            // Parse:
            // SPL: --token-gate spl <mint_address> <min_amount> [symbol] [decimals]
            // SOL: --token-gate sol <min_sol>
            // SPL (legacy): --token-gate <mint_address> <min_amount> [symbol] [decimals]
            // NFT specific: --token-gate nft-specific <mint_address>
            // NFT collection: --token-gate nft-collection <collection_mint>
            if (parts.size < tokenGateIndex + 2) {
                addSystemMessage("usage:\n/create #vip --token-gate spl <mint> <amount> [symbol] [decimals]\n/create #vip --token-gate sol <min_sol>\n/create #vip --token-gate nft-specific <mint>\n/create #vip --token-gate nft-collection <collection_mint>")
                return null
            }

            val firstArg = parts[tokenGateIndex + 1]
            val parsedGateType = when (firstArg.lowercase()) {
                "spl" -> TokenGateType.SPL_TOKEN
                "sol" -> TokenGateType.SOL_BALANCE
                "nft-specific", "nft_specific" -> TokenGateType.NFT_SPECIFIC
                "nft-collection", "nft_collection" -> TokenGateType.NFT_COLLECTION
                else -> TokenGateType.SPL_TOKEN // legacy format
            }

            val mintArgIndex = if (parsedGateType == TokenGateType.SPL_TOKEN && firstArg.lowercase() !in setOf("spl")) {
                tokenGateIndex + 1
            } else {
                tokenGateIndex + 2
            }
            val mintAddress = when (parsedGateType) {
                TokenGateType.SOL_BALANCE -> "SOL"
                else -> parts.getOrNull(mintArgIndex).orEmpty()
            }
            if (parsedGateType != TokenGateType.SOL_BALANCE && mintAddress.isBlank()) {
                addSystemMessage("missing mint/collection address for token gate.")
                return null
            }

            val minAmount: Long
            val symbol: String
            val decimals: Int
            when (parsedGateType) {
                TokenGateType.SPL_TOKEN -> {
                    val amountIndex = mintArgIndex + 1
                    val minAmountStr = parts.getOrNull(amountIndex)
                    val parsedAmount = minAmountStr?.toLongOrNull()
                    if (parsedAmount == null || parsedAmount <= 0) {
                        addSystemMessage("invalid minimum amount: ${minAmountStr ?: "(missing)"}")
                        return null
                    }
                    minAmount = parsedAmount
                    symbol = parts.getOrNull(amountIndex + 1) ?: ""
                    decimals = parts.getOrNull(amountIndex + 2)?.toIntOrNull() ?: 0
                }
                TokenGateType.SOL_BALANCE -> {
                    val amountSol = parts.getOrNull(tokenGateIndex + 2)?.toDoubleOrNull()
                    if (amountSol == null || amountSol <= 0.0) {
                        addSystemMessage("invalid SOL amount: ${parts.getOrNull(tokenGateIndex + 2) ?: "(missing)"}")
                        return null
                    }
                    minAmount = (amountSol * 1_000_000_000.0).toLong()
                    symbol = "SOL"
                    decimals = 9
                }
                TokenGateType.NFT_SPECIFIC -> {
                    minAmount = 1L
                    symbol = "NFT"
                    decimals = 0
                }
                TokenGateType.NFT_COLLECTION -> {
                    minAmount = 1L
                    symbol = "NFT"
                    decimals = 0
                }
                else -> {
                    addSystemMessage("unknown token gate type.")
                    return null
                }
            }

            // First join/create the channel
            val success = channelManager.joinChannel(channel, null, myPeerID, timeline)
            if (!success) return null
            channelManager.assignChannelCreator(ChannelKeys.create(timeline, channel), myPeerID)

            // Now create the token gate
            val key = ChannelKeys.create(timeline, channel)
            commandScope.launch {
                val result = tgs.createTokenGate(
                    channelKey = key,
                    gateType = parsedGateType,
                    tokenMintAddress = mintAddress,
                    minBalance = minAmount,
                    tokenSymbol = symbol,
                    tokenDecimals = decimals
                )
                result.onSuccess {
                    meshService.broadcastTokenGatePolicy(
                        com.bitchat.android.solana.TokenGatePolicyPayload.fromConfig(it)
                    )
                    val descriptor = when (parsedGateType) {
                        TokenGateType.SPL_TOKEN -> {
                            val displaySymbol = symbol.ifEmpty { "tokens" }
                            "requires $minAmount $displaySymbol"
                        }
                        TokenGateType.SOL_BALANCE -> {
                            val requiredSol = tgs.formatTokenAmount(minAmount, 9)
                            "requires at least $requiredSol SOL"
                        }
                        TokenGateType.NFT_SPECIFIC -> "requires holding NFT mint ${mintAddress.take(8)}..."
                        TokenGateType.NFT_COLLECTION -> "requires holding any NFT in collection ${mintAddress.take(8)}..."
                        else -> "token gate enabled"
                    }
                    addSystemMessage("created token-gated channel $channel: $descriptor")
                }.onFailure { error ->
                    addSystemMessage("channel created but token gate setup failed: ${error.message}")
                }
            }
        } else {
            // Regular channel creation (same as join)
            val success = channelManager.joinChannel(channel, null, myPeerID, timeline)
            if (success) {
                channelManager.assignChannelCreator(ChannelKeys.create(timeline, channel), myPeerID)
                addSystemMessage("created channel $channel")
            }
        }
        return null
    }

    private fun handleMessageCommand(parts: List<String>, meshService: BluetoothMeshService, viewModel: ChatViewModel? = null): CommandResult? {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            val peerID = getPeerIDForNickname(targetName, meshService, viewModel)

            if (peerID != null) {
                val success = privateChatManager.startPrivateChat(peerID, meshService)

                if (success) {
                    if (parts.size > 2) {
                        val messageContent = parts.drop(2).joinToString(" ")
                        val recipientNickname = getPeerNickname(peerID, meshService, viewModel)
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
                        getPeerNickname(peerID, meshService, viewModel)
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
                getPeerNickname(peerID, meshService, viewModel)
            }
            Pair(peerList, "online users")
        }
        
        addSystemMessage(
            if (peerList.isEmpty()) {
                "no one else is around right now."
            } else {
                "$contextDescription: $peerList"
            }
        )
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

        val targetInput = parts[1].removePrefix("@")
        val amountStr = parts[2]
        val memo = if (parts.size > 3) parts.drop(3).joinToString(" ") else null

        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            val prefill = "${parts[0]} ${parts[1]} "
            return CommandResult(prefillText = prefill, hintText = "'$amountStr' isn't valid — try a number like 0.5")
        }

        // Look up the peer's Solana address
        // Check if target looks like a base58 Solana address, or resolve by nickname
        val recipientAddress: String = if (targetInput.length >= 32 && targetInput.all { it.isLetterOrDigit() }) {
            // Looks like a Solana address
            targetInput
        } else {
            // Look up peer's Solana address from mesh identity announcements
            val resolution = viewModel?.resolveTipRecipient(targetInput)
            when (resolution) {
                is ChatViewModel.TipRecipientResolution.Unique -> resolution.address
                is ChatViewModel.TipRecipientResolution.Ambiguous -> {
                    addSystemMessage(
                        "multiple users named ${resolution.nickname}. choose one: ${
                            resolution.options.joinToString(", ") { "@$it" }
                        }"
                    )
                    return null
                }
                is ChatViewModel.TipRecipientResolution.NotFound, null -> {
                    // Check if peer exists at all
                    val baseName = targetInput.substringBefore("#")
                    val peerExists = meshService.getPeerNicknames().values.any { it == baseName }
                    if (peerExists) {
                        addSystemMessage("wallet link for $baseName is updating. try again in 2-3 seconds.")
                    } else {
                        addSystemMessage("can't find '$targetInput' — are they online? check /w")
                    }
                    return null
                }
            }
        }

        addSystemMessage("sending $amount SOL to $targetInput...")

        commandScope.launch {
            val result = pm.queuePayment(recipientAddress, amount, memo)
            result.onSuccess { txId ->
                addSystemMessage("$amount SOL sent to $targetInput! confirming...")
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
        val currentChannel = state.getCurrentChannelValue()
        if (!currentChannel.isNullOrBlank()) {
            channelManager.addChannelMessage(currentChannel, systemMessage, null)
        } else {
            messageManager.addMessage(systemMessage)
        }
    }

    private fun publishLendingLoanRequestMessage(
        content: LendingLoanRequestMessage,
        myPeerID: String,
        onSendMessage: (String, List<String>, String?) -> Unit,
        targetChannelKey: String? = null
    ) {
        val encoded = LendingLoanRequestMessageCodec.encode(content)
        val currentChannel = targetChannelKey ?: state.getCurrentChannelValue()
        val message = BitchatMessage(
            sender = state.getNicknameValue() ?: myPeerID,
            content = encoded,
            timestamp = Date(),
            isRelay = false,
            senderPeerID = myPeerID,
            channel = currentChannel
        )
        if (!currentChannel.isNullOrBlank()) {
            channelManager.addChannelMessage(currentChannel, message, myPeerID)
            val wireContent = "${ChannelKeys.parseChannelName(currentChannel)} $encoded"
            onSendMessage(wireContent, emptyList(), currentChannel)
        } else {
            messageManager.addMessage(message)
            onSendMessage(encoded, emptyList(), null)
        }
    }

    private fun publishLendingMembershipMessage(
        content: LendingMembershipMessage,
        onSendMessage: (String, List<String>, String?) -> Unit,
        targetChannelKey: String? = null
    ) {
        val currentChannel = targetChannelKey ?: state.getCurrentChannelValue()
        val encoded = LendingMembershipMessageCodec.encode(content)
        if (!currentChannel.isNullOrBlank()) {
            val wireContent = "${ChannelKeys.parseChannelName(currentChannel)} $encoded"
            onSendMessage(wireContent, emptyList(), currentChannel)
        } else {
            onSendMessage(encoded, emptyList(), null)
        }
    }

    private fun publishLendingChannelConfigMessage(
        content: LendingChannelConfigMessage,
        onSendMessage: (String, List<String>, String?) -> Unit,
        targetChannelKey: String
    ) {
        val encoded = LendingChannelConfigMessageCodec.encode(content)
        val wireContent = "${ChannelKeys.parseChannelName(targetChannelKey)} $encoded"
        onSendMessage(wireContent, emptyList(), targetChannelKey)
    }

    private fun publishLendingChannelConfigRequestMessage(
        content: LendingChannelConfigRequestMessage,
        onSendMessage: (String, List<String>, String?) -> Unit,
        targetChannelKey: String
    ) {
        val encoded = LendingChannelConfigRequestMessageCodec.encode(content)
        val wireContent = "${ChannelKeys.parseChannelName(targetChannelKey)} $encoded"
        onSendMessage(wireContent, emptyList(), targetChannelKey)
    }

    private fun buildLendingChannelConfigMessage(
        channel: com.bitchat.android.data.local.entities.LendingChannelEntity
    ): LendingChannelConfigMessage {
        return LendingChannelConfigMessage(
            lendingId = channel.lendingId,
            channelKey = channel.channelKey,
            displayName = channel.displayName,
            creatorPeerId = channel.creatorPeerId,
            creatorWalletAddress = channel.creatorWalletAddress,
            requiredStakeAmount = channel.requiredStakeAmount,
            minimumVoteCount = channel.minimumVoteCount,
            maxLoanDurationDays = channel.maxLoanDurationDays,
            stakeTokenMint = channel.stakeTokenMint,
            stakeTokenSymbol = channel.stakeTokenSymbol,
            stakeTokenDecimals = channel.stakeTokenDecimals
        )
    }

    private fun buildLoanRequestMessage(
        loan: com.bitchat.android.data.local.entities.LoanRequestEntity,
        channel: com.bitchat.android.data.local.entities.LendingChannelEntity,
        actorPeerId: String? = null,
        fallbackBorrowerLabel: String? = null
    ): LendingLoanRequestMessage {
        return LendingLoanRequestMessage(
            requestId = loan.requestId,
            lendingId = loan.lendingId,
            actorPeerId = actorPeerId,
            channelDisplayName = channel.displayName,
            principalAmount = loan.principalAmount,
            assetSymbol = channel.stakeTokenSymbol.ifBlank { channel.stakeTokenMint.take(8).uppercase() },
            assetDecimals = channel.stakeTokenDecimals,
            durationDays = loan.durationDays,
            interestBps = loan.interestBps,
            purpose = loan.purpose,
            requestedAt = loan.requestedAt,
            borrowerPeerId = loan.borrowerPeerId,
            borrowerWalletAddress = loan.borrowerWalletAddress,
            borrowerLabel = fallbackBorrowerLabel,
            status = loan.status,
            parentRequestId = loan.parentRequestId,
            originLendingId = loan.originLendingId,
            forwardedFromRequestId = loan.forwardedFromRequestId,
            fundingLendingId = loan.fundingLendingId,
            requestKind = loan.requestKind
        )
    }

    private fun publishLendingLoanVoteMessage(
        content: LendingLoanVoteMessage,
        onSendMessage: (String, List<String>, String?) -> Unit,
        targetChannelKey: String? = null
    ) {
        val currentChannel = targetChannelKey ?: state.getCurrentChannelValue()
        val encoded = LendingLoanVoteMessageCodec.encode(content)
        if (!currentChannel.isNullOrBlank()) {
            val wireContent = "${ChannelKeys.parseChannelName(currentChannel)} $encoded"
            onSendMessage(wireContent, emptyList(), currentChannel)
        } else {
            onSendMessage(encoded, emptyList(), null)
        }
    }

    private fun publishLendingLoanRepaymentMessage(
        content: LendingLoanRepaymentMessage,
        onSendMessage: (String, List<String>, String?) -> Unit,
        targetChannelKey: String? = null
    ) {
        val currentChannel = targetChannelKey ?: state.getCurrentChannelValue()
        val encoded = LendingLoanRepaymentMessageCodec.encode(content)
        if (!currentChannel.isNullOrBlank()) {
            val wireContent = "${ChannelKeys.parseChannelName(currentChannel)} $encoded"
            onSendMessage(wireContent, emptyList(), currentChannel)
        } else {
            onSendMessage(encoded, emptyList(), null)
        }
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

        addSystemMessage(channelList)
    }
    
    private fun handleUnknownCommand(cmd: String) {
        addSystemMessage("$cmd isn't a command — type / to see what's available.")
    }
    
    // MARK: - Command Autocomplete

    fun updateCommandSuggestions(input: String, myPeerID: String) {
        if (!input.startsWith("/")) {
            state.setShowCommandSuggestions(false)
            state.setCommandSuggestions(emptyList())
            return
        }
        
        // Get all available commands based on context
        val allCommands = getAllAvailableCommands(myPeerID)
        
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

    fun getAllSlashCommands(myPeerID: String): List<CommandSuggestion> {
        return filterCommands(getAllAvailableCommands(myPeerID), "/")
    }
    
    private fun getAllAvailableCommands(myPeerID: String): List<CommandSuggestion> {
        val currentChannel = state.getCurrentChannelValue()
        if (currentChannel.isNullOrBlank()) {
            // Global context: only global commands.
            return globalCommands
        }

        // Channel context: do not show global commands.
        val commands = mutableListOf(
            CommandSuggestion("/leave", emptyList(), "[#channel]", "exit channel"),
            CommandSuggestion("/lending create", emptyList(), "#channel <stake_amount> <mint> <minimum_votes> [max_payback_days]", "create lending channel"),
            CommandSuggestion("/lending invite", emptyList(), "[#channel|lendingId]", "create a lending invite code"),
            CommandSuggestion("/lending import", emptyList(), "<invite_code>", "import a lending invite"),
            CommandSuggestion("/lending join", emptyList(), "[#channel|channel|lendingId]", "join lending channel"),
            CommandSuggestion("/users", emptyList(), "[#channel]", "list tracked users in channel"),
            CommandSuggestion("/gm", emptyList(), "@user", "send a gm"),
            CommandSuggestion("/tip", emptyList(), "@user <amount>", "send SOL"),
            CommandSuggestion("/w", emptyList(), null, "who's online"),
            CommandSuggestion("/wallet", emptyList(), null, "your wallet"),
            CommandSuggestion("/channel exit", emptyList(), "[#channel]", "leave channel"),
            CommandSuggestion("/channel users", emptyList(), "[#channel]", "list tracked users in channel"),
            CommandSuggestion("/channel gate show", emptyList(), "[#channel]", "show token gate settings")
        )

        if (state.getCurrentChannelIsLendingValue()) {
            commands.addAll(lendingChannelOnlyCommands)
        }

        val isAdmin = channelManager.isChannelAdmin(currentChannel, myPeerID)
        val isOwner = channelManager.isChannelCreator(currentChannel, myPeerID)

        if (isAdmin) {
            commands.addAll(
                listOf(
                    CommandSuggestion("/channel", emptyList(), "<action>", "channel tools"),
                    CommandSuggestion("/kick", emptyList(), "[@user|#channel @user]", "remove member from channel (admin)"),
                    CommandSuggestion("/gate create", emptyList(), "#channel <type> ...", "alias: channel gate set (admin)"),
                    CommandSuggestion("/gate status", emptyList(), "[#channel]", "alias: channel gate show"),
                    CommandSuggestion("/gate refresh", emptyList(), "[#channel]", "alias: channel gate refresh (admin)"),
                    CommandSuggestion("/gate remove", emptyList(), "[#channel]", "alias: channel gate remove (admin)"),
                    CommandSuggestion("/channel member remove", emptyList(), "[#channel] @nickname", "remove member from channel (admin)"),
                    CommandSuggestion("/channel member admin", emptyList(), "@nickname", "promote member to admin (channel-local)"),
                    CommandSuggestion("/channel member member", emptyList(), "@nickname", "demote admin to member (channel-local)"),
                    CommandSuggestion("/channel gate set", emptyList(), "#channel <type> ...", "set token gate (admin)"),
                    CommandSuggestion("/channel gate refresh", emptyList(), "[#channel]", "re-check gate status"),
                    CommandSuggestion("/channel gate remove", emptyList(), "[#channel]", "remove token gate (admin)")
                )
            )
        }

        if (isOwner) {
            commands.addAll(
                listOf(
                    CommandSuggestion("/pass", emptyList(), "[password]", "change channel password"),
                    CommandSuggestion("/transfer", emptyList(), "<nickname>", "transfer channel ownership"),
                    CommandSuggestion("/channel owner transfer", emptyList(), "[#channel] @nickname", "transfer channel ownership (owner)")
                )
            )
        }

        return commands
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
            "/lending" -> CommandResult(prefillText = "/lending ", hintText = lendingHint())
            "/lending create" -> CommandResult(prefillText = "/lending create #", hintText = "usage: /lending create #channel <stake_amount> <mint> <minimum_votes> [max_payback_days]")
            "/lending invite" -> CommandResult(prefillText = "/lending invite ", hintText = "usage: /lending invite [#channel|lendingId]")
            "/lending import" -> CommandResult(prefillText = "/lending import ", hintText = "usage: /lending import <invite_code>")
            "/lending join" -> CommandResult(prefillText = "/lending join #", hintText = "use #channel, channel, or lendingId")
            "/lending status" -> CommandResult(prefillText = "/lending status ", hintText = "use #channel or lendingId")
            "/lending request" -> CommandResult(prefillText = "/lending request ", hintText = lendingRequestHint())
            "/lending cancel" -> CommandResult(prefillText = "/lending cancel ", hintText = "usage: /lending cancel <request_id>")
            "/lending vote" -> CommandResult(prefillText = "/lending vote ", hintText = "usage: /lending vote <request_id> approve|deny")
            "/lending review" -> CommandResult(prefillText = "/lending review ", hintText = "usage: /lending review <request_id>")
            "/lending authorize" -> CommandResult(prefillText = "/lending authorize ", hintText = "usage: /lending authorize <request_id>")
            "/lending disburse" -> CommandResult(prefillText = "/lending disburse ", hintText = "usage: /lending disburse <request_id>")
            "/lending repay" -> CommandResult(prefillText = "/lending repay ", hintText = "usage: /lending repay <request_id> <amount> [asset] • e.g. /lending repay abc123 0.25 SOL")
            "/lending leave" -> CommandResult(prefillText = "/lending leave ", hintText = "use #channel or lendingId")
            "/gate" -> CommandResult(prefillText = "/gate create #", hintText = "usage: /gate create #vip <spl|sol|nft-specific|nft-collection> ...")
            "/gate create" -> CommandResult(prefillText = "/gate create #", hintText = "usage: /gate create #vip <spl|sol|nft-specific|nft-collection> ...")
            "/gate status" -> CommandResult(prefillText = "/gate status #", hintText = "or use /gate status in current channel")
            "/gate refresh" -> CommandResult(prefillText = "/gate refresh #", hintText = "or use /gate refresh in current channel")
            "/gate remove" -> CommandResult(prefillText = "/gate remove #", hintText = "or use /gate remove in current channel")
            "/channel" -> CommandResult(prefillText = "/channel ", hintText = "users | gate show | exit")
            "/leave" -> CommandResult(prefillText = "/leave", hintText = "or /leave #channel")
            "/users" -> CommandResult(prefillText = "/users ", hintText = "or use /users #channel")
            "/channel exit" -> CommandResult(prefillText = "/channel exit", hintText = "or /channel exit #channel")
            "/channel users" -> CommandResult(prefillText = "/channel users #", hintText = "or use in current channel")
            "/channel member remove" -> CommandResult(prefillText = "/channel member remove @", hintText = "or /channel member remove #channel @name")
            "/channel member admin" -> CommandResult(prefillText = "/channel member admin @", hintText = "channel-local only; switch channels first")
            "/channel member member" -> CommandResult(prefillText = "/channel member member @", hintText = "channel-local only; switch channels first")
            "/channel owner transfer" -> CommandResult(prefillText = "/channel owner transfer @", hintText = "or /channel owner transfer #channel @name")
            "/channel gate show" -> CommandResult(prefillText = "/channel gate show #", hintText = "or use in current channel")
            "/channel gate set" -> CommandResult(prefillText = "/channel gate set #", hintText = "usage: /channel gate set #vip <spl|sol|nft-specific|nft-collection> ...")
            "/channel gate refresh" -> CommandResult(prefillText = "/channel gate refresh #", hintText = "or use in current channel")
            "/channel gate remove" -> CommandResult(prefillText = "/channel gate remove #", hintText = "or use in current channel")
            "/kick" -> CommandResult(prefillText = "/kick @", hintText = "who should be removed from the channel?")
            "/transfer" -> CommandResult(prefillText = "/transfer @", hintText = "who should become channel owner?")
            "/j", "/join" -> CommandResult(prefillText = "/join #", hintText = "type a channel name")
            "/tip" -> CommandResult(prefillText = "/tip @nickname amount", hintText = "replace nickname and amount")
            "/m", "/msg" -> CommandResult(prefillText = "/m @", hintText = "who do you want to message?")
            "/block" -> CommandResult(prefillText = "/block @", hintText = "who do you want to block?")
            "/unblock" -> CommandResult(prefillText = "/unblock @", hintText = "who do you want to unblock?")
            "/gm" -> CommandResult(prefillText = "/gm @", hintText = "who gets your gm?")
            "/hug" -> CommandResult(prefillText = "/hug @", hintText = "who do you want to hug?")
            else -> CommandResult(prefillText = "${suggestion.command} ")
        }
    }

    private fun lendingHint(): String {
        return if (state.getCurrentChannelIsLendingValue()) {
            "create | join | status | request | vote | review | authorize | disburse | repay | leave"
        } else {
            "create | join"
        }
    }

    private fun lendingRequestHint(): String {
        val example = currentLendingAssetExample()
        return "usage: /lending request <amount> [asset] <days> <purpose...> • e.g. /lending request $example 7 inventory"
    }

    private fun currentLendingAssetExample(): String {
        if (!state.getCurrentChannelIsLendingValue()) {
            return "1 SOL"
        }
        val channelKey = state.getCurrentChannelValue() ?: return "1 SOL"
        val channel = runCatching {
            runBlocking {
                lendingChannelService?.getChannelByChannelKey(ChannelKeys.normalize(channelKey))
            }
        }.getOrNull() ?: return "1 SOL"
        val symbol = channel.stakeTokenSymbol.ifBlank {
            if (isNativeSolStakeAsset(channel.stakeTokenMint, channel.stakeTokenSymbol)) {
                NATIVE_SOL_ASSET
            } else {
                channel.stakeTokenMint.take(8).uppercase()
            }
        }
        val amount = if (channel.stakeTokenDecimals > 0) "1" else "1"
        return "$amount $symbol"
    }

    private data class ParsedLendingStake(
        val amountAtomic: Long,
        val mint: String,
        val symbol: String,
        val decimals: Int
    )

    private data class ParsedLendingLoanAmount(
        val amountAtomic: Long,
        val consumedTokenCount: Int
    )

    private fun parseLendingStakeAmount(rawAmount: String, rawMint: String): ParsedLendingStake? {
        if (isNativeSolStakeAsset(rawMint, rawMint)) {
            val amountSol = rawAmount.toDoubleOrNull() ?: return null
            val lamports = (amountSol * 1_000_000_000.0).toLong()
            if (lamports <= 0L) return null
            return ParsedLendingStake(
                amountAtomic = lamports,
                mint = NATIVE_SOL_ASSET,
                symbol = NATIVE_SOL_ASSET,
                decimals = 9
            )
        }

        val amountAtomic = rawAmount.toLongOrNull() ?: return null
        if (amountAtomic <= 0L) return null
        return ParsedLendingStake(
            amountAtomic = amountAtomic,
            mint = rawMint,
            symbol = rawMint.take(8).uppercase(),
            decimals = 6
        )
    }

    private fun parseLendingLoanAmount(
        args: List<String>,
        channel: com.bitchat.android.data.local.entities.LendingChannelEntity
    ): ParsedLendingLoanAmount? {
        val rawAmount = args.getOrNull(0) ?: return null
        val explicitAsset = args.getOrNull(1)
        val assetSymbol = channel.stakeTokenSymbol.ifBlank {
            if (isNativeSolStakeAsset(channel.stakeTokenMint, channel.stakeTokenSymbol)) NATIVE_SOL_ASSET
            else channel.stakeTokenMint.take(8).uppercase()
        }
        val symbolMatches = explicitAsset?.let {
            it.equals(assetSymbol, ignoreCase = true) ||
                it.equals(channel.stakeTokenMint, ignoreCase = true) ||
                (isNativeSolStakeAsset(channel.stakeTokenMint, channel.stakeTokenSymbol) && it.equals(NATIVE_SOL_ASSET, ignoreCase = true))
        } == true
        val amountAtomic = parseDisplayAmountToAtomic(rawAmount, channel.stakeTokenDecimals) ?: return null
        return ParsedLendingLoanAmount(
            amountAtomic = amountAtomic,
            consumedTokenCount = if (symbolMatches) 2 else 1
        )
    }

    private fun parseDisplayAmountToAtomic(rawAmount: String, decimals: Int): Long? {
        val normalized = rawAmount.trim().replace(",", "")
        if (normalized.isBlank()) return null
        val amount = normalized.toBigDecimalOrNull() ?: return null
        if (amount <= BigDecimal.ZERO) return null
        val scaled = amount.movePointRight(decimals)
        if (scaled.stripTrailingZeros().scale() > 0) return null
        return runCatching { scaled.setScale(0, RoundingMode.UNNECESSARY).longValueExact() }.getOrNull()
    }

    private fun parseLendingRepaymentAmount(
        args: List<String>,
        channel: com.bitchat.android.data.local.entities.LendingChannelEntity
    ): ParsedLendingLoanAmount? {
        return parseLendingLoanAmount(args, channel)
    }

    private fun repaymentAssetSymbol(
        channel: com.bitchat.android.data.local.entities.LendingChannelEntity
    ): String {
        return channel.stakeTokenSymbol.ifBlank {
            if (isNativeSolStakeAsset(channel.stakeTokenMint, channel.stakeTokenSymbol)) {
                NATIVE_SOL_ASSET
            } else {
                channel.stakeTokenMint.take(8).uppercase()
            }
        }
    }

    private fun formatDisplayAmount(amountAtomic: Long, decimals: Int): String {
        return BigDecimal.valueOf(amountAtomic)
            .movePointLeft(decimals)
            .stripTrailingZeros()
            .toPlainString()
    }

    private fun friendlyLendingRequestError(error: Exception): String {
        return when (error.message) {
            "lending_channel_not_found" -> "lending channel not found"
            "invalid_loan_amount" -> "amount must match the channel asset, e.g. 1 SOL or 25.5"
            "invalid_loan_duration" -> "days must be a whole number greater than 0"
            "missing_loan_purpose" -> "add a short purpose after the amount and days"
            "membership_not_found", "membership_not_active" -> "you need an active lending membership before requesting a loan"
            "active_individual_loan_exists" -> "you already have an active individual loan in this channel"
            "request_exceeds_pool_cap" -> "requested amount is above the current pool cap"
            "loan_duration_exceeds_channel_max" -> "loan duration is longer than this channel allows"
            else -> error.message ?: "unknown error"
        }
    }

    private fun friendlyLendingForwardError(error: Exception): String {
        return when (error.message) {
            "forwarding_disabled_phase_one" -> "loan forwarding is disabled in phase one while single-channel production flow is being hardened"
            "loan_request_not_found" -> "loan request not found"
            "loan_request_not_forwardable" -> "only pending or approved loans can be forwarded"
            "admin_only_loan_forward" -> "only a channel admin can forward this loan"
            "cannot_forward_to_same_channel" -> "choose a different lending channel"
            "membership_not_found", "membership_not_active" -> "borrower needs an active membership in the destination lending channel"
            "loan_request_already_funded_elsewhere" -> "this loan family has already been funded by another channel"
            else -> error.message ?: "unknown error"
        }
    }

    private fun friendlyLendingVoteError(error: Exception): String {
        return when (error.message) {
            "loan_request_not_found" -> "loan request was not found on this device yet"
            "loan_request_not_open_for_voting" -> "loan is no longer open for voting"
            "loan_request_voting_closed" -> "the voting window for this loan has already closed"
            "borrower_cannot_vote_own_request" -> "borrower cannot approve their own loan request"
            "membership_not_found", "membership_not_active" -> "you need an active lending membership to approve this loan"
            "vote_must_be_upvote" -> "vote must be approve"
            "vote_must_be_yes_or_no" -> "vote must be approve or deny"
            else -> error.message ?: "unknown error"
        }
    }

    private fun friendlyLendingCancelError(error: Exception): String {
        return when (error.message) {
            "loan_request_not_found" -> "loan request not found"
            "loan_request_not_cancellable" -> "only open, undisbursed loan requests can be cancelled"
            "loan_request_already_funded_elsewhere" -> "this loan family has already been funded and cannot be cancelled"
            "borrower_or_admin_only_cancellation" -> "only the borrower or a channel admin can cancel this loan request"
            else -> error.message ?: "unknown error"
        }
    }

    private fun friendlyLendingSquadError(error: Exception): String {
        return when (error.message) {
            "lending_channel_not_found" -> "lending channel not found"
            "owner_only_squad_configuration" -> "only the lending channel owner can configure treasury custody"
            "squad_multisig_required" -> "enter a Squad multisig address"
            "squad_threshold_must_be_2" -> "the Squad threshold must be set to 2 approvals"
            "squad_member_count_must_be_at_least_3" -> "the Squad must have at least 3 members"
            "account_not_found" -> "the Squad multisig account was not found on chain"
            else -> error.message ?: "unknown error"
        }
    }

    private fun friendlyLendingRepaymentError(error: Exception, requestId: String): String {
        return when (error.message) {
            "invalid_repayment_amount" -> "amount must match the loan asset, e.g. /lending repay $requestId 0.25 SOL"
            "repayment_amount_must_be_positive" -> "repayment amount must be greater than zero"
            "repayment_amount_exceeds_outstanding_balance" -> "amount is higher than the remaining balance"
            "loan_request_not_found" -> "loan request $requestId was not found"
            "loan_request_not_repayable" -> "this loan is not in a repayable state"
            "loan_already_repaid" -> "this loan is already fully repaid"
            "only_borrower_can_repay_individual_loan" -> "only the borrower can repay this individual loan"
            "shared_custody_required" -> "configure verified shared Squad custody before recording repayment"
            "repayment_vault_not_configured" -> "this lending channel needs a treasury vault before repayments can be recorded"
            else -> error.message ?: "unknown error"
        }
    }

    private fun friendlyLendingSignerReviewError(error: Exception): String {
        return when (error.message) {
            "loan_request_not_found" -> "loan request not found"
            "lending_channel_not_found" -> "lending channel not found"
            "shared_custody_required" -> "configure verified shared Squad custody before opening signer review"
            "loan_request_not_ready_for_signer_review" -> "loan must be community approved before signer review"
            "admin_only_signer_review" -> "only a channel admin can open signer review"
            else -> error.message ?: "unknown error"
        }
    }

    private fun friendlyLendingSignerAuthorizationError(error: Exception): String {
        return when (error.message) {
            "loan_request_not_found" -> "loan request not found"
            "lending_channel_not_found" -> "lending channel not found"
            "shared_custody_required" -> "configure verified shared Squad custody before authorizing payout"
            "signer_review_not_open" -> "open signer review before authorizing payout"
            "loan_request_not_ready_for_signer_authorization" -> "loan must be in signer review before authorization"
            "approver_only_signer_authorization" -> "only an approver can authorize payout"
            "squad_proposal_not_created" -> "signer review proposal is missing"
            else -> error.message ?: "unknown error"
        }
    }

    private fun friendlyLendingDisbursementError(error: Exception): String {
        return when (error.message) {
            "loan_request_not_found" -> "loan request not found"
            "shared_custody_required" -> "configure verified shared Squad custody before disbursement"
            "loan_request_not_ready_for_disbursement" -> "loan must complete signer review before disbursement"
            "admin_only_disbursement" -> "only a channel admin can disburse a signer-approved loan"
            "loan_request_already_funded_elsewhere" -> "another linked channel already funded this loan"
            else -> error.message ?: "unknown error"
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
                    val displayNames = viewModel?.peerNicknames?.value ?: meshService.getPeerNicknames()
                    val myName = displayNames[meshService.myPeerID]
                    displayNames.values.filter { candidate ->
                        candidate != myName &&
                            candidate != state.getNicknameValue() &&
                            !candidate.startsWith("${state.getNicknameValue()}#")
                    }
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
    
    private fun getPeerIDForNickname(nickname: String, meshService: BluetoothMeshService, viewModel: ChatViewModel? = null): String? {
        val displayNames = viewModel?.peerNicknames?.value ?: meshService.getPeerNicknames()
        val exact = displayNames.entries.find { it.value == nickname }?.key
        if (exact != null) return exact

        val base = nickname.substringBefore("#")
        return meshService.getPeerNicknames().entries.find { it.value == base }?.key
    }
    
    private fun getPeerNickname(peerID: String, meshService: BluetoothMeshService, viewModel: ChatViewModel? = null): String {
        return (viewModel?.peerNicknames?.value?.get(peerID))
            ?: meshService.getPeerNicknames()[peerID]
            ?: peerID
    }
    
    private fun getMyPeerID(meshService: BluetoothMeshService): String {
        return meshService.myPeerID
    }
    
    private fun sendPrivateMessageVia(meshService: BluetoothMeshService, content: String, peerID: String, recipientNickname: String, messageId: String) {
        meshService.sendPrivateMessage(content, peerID, recipientNickname, messageId)
    }
}
