package com.bitchat.android.ui
// [Goose] Bridge file share events to ViewModel via dispatcher is installed in ChatScreen composition

// [Goose] Installing FileShareDispatcher handler in ChatScreen to forward file sends to ViewModel

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.bitchat.android.ui.theme.AppIcons
import com.bitchat.android.ui.theme.rememberAppIconPainter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.bitchat.android.data.local.entities.LendingChannelEntity
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.media.FullScreenImageViewer
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.BitchatShapes
import kotlinx.coroutines.launch

/**
 * Main ChatScreen - REFACTORED to use component-based architecture
 * This is now a coordinator that orchestrates the following UI components:
 * - ChatHeader: App bar, navigation, peer counter
 * - MessageComponents: Message display and formatting
 * - InputComponents: Message input and command suggestions
 * - SidebarComponents: Navigation drawer with channels and people
 * - AboutSheet: App info and password prompts
 * - ChatUIUtils: Utility functions for formatting and colors
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val messages by viewModel.messages.observeAsState(emptyList())
    val connectedPeers by viewModel.connectedPeers.observeAsState(emptyList())
    val nickname by viewModel.nickname.observeAsState("")
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.observeAsState()
    val currentChannel by viewModel.currentChannel.observeAsState()
    val joinedChannels by viewModel.joinedChannels.observeAsState(emptySet())
    val hasUnreadChannels by viewModel.unreadChannelMessages.observeAsState(emptyMap())
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.observeAsState(emptySet())
    val privateChats by viewModel.privateChats.observeAsState(emptyMap())
    val channelMessages by viewModel.channelMessages.observeAsState(emptyMap())
    val showSidebar by viewModel.showSidebar.observeAsState(false)
    val selectedLocationChannel by viewModel.selectedLocationChannel.observeAsState()
    val commandSheetSuggestions = remember(
        selectedPrivatePeer,
        currentChannel
    ) { viewModel.getAllSlashCommands() }
    val showMentionSuggestions by viewModel.showMentionSuggestions.observeAsState(false)
    val mentionSuggestions by viewModel.mentionSuggestions.observeAsState(emptyList())
    val showAppInfo by viewModel.showAppInfo.observeAsState(false)

    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var commandHint by remember { mutableStateOf<String?>(null) }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var showLocationChannelsSheet by remember { mutableStateOf(false) }
    var showLocationNotesSheet by remember { mutableStateOf(false) }
    var showUserSheet by remember { mutableStateOf(false) }
    var showCommandSheet by remember { mutableStateOf(false) }
    var selectedUserForSheet by remember { mutableStateOf("") }
    var selectedMessageForSheet by remember { mutableStateOf<BitchatMessage?>(null) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    var viewerImagePaths by remember { mutableStateOf(emptyList<String>()) }
    var initialViewerIndex by remember { mutableStateOf(0) }
    var forceScrollToBottom by remember { mutableStateOf(false) }
    var isScrolledUp by remember { mutableStateOf(false) }
    var showWalletScreen by remember { mutableStateOf(false) }
    var showNotificationsSheet by remember { mutableStateOf(false) }
    var forwardRequestId by remember { mutableStateOf<String?>(null) }
    var forwardSourceLendingId by remember { mutableStateOf<String?>(null) }
    var feedJumpTargetPostId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val selectedTab by viewModel.selectedTab.observeAsState("chat")
    val showNewPostComposer by viewModel.showNewPostComposer.observeAsState(false)
    val pendingLendingStakeApproval by viewModel.pendingLendingStakeApproval.observeAsState()
    val isSubmittingLendingStakeApproval by viewModel.isSubmittingLendingStakeApproval.observeAsState(false)
    val pendingLendingLeaveApproval by viewModel.pendingLendingLeaveApproval.observeAsState()
    val isSubmittingLendingLeaveApproval by viewModel.isSubmittingLendingLeaveApproval.observeAsState(false)
    val pendingLendingTreasurySetup by viewModel.pendingLendingTreasurySetup.observeAsState()
    val isSubmittingLendingTreasurySetup by viewModel.isSubmittingLendingTreasurySetup.observeAsState(false)
    val lendingLoanRequestStatuses by viewModel.lendingLoanRequestStatuses.observeAsState(emptyMap())
    val currentLendingSharedCustodyReady by viewModel.currentLendingSharedCustodyReady.observeAsState(false)
    val availableLendingChannels by viewModel.availableLendingChannels.observeAsState(emptyList())

    // Show password dialog when needed
    LaunchedEffect(showPasswordPrompt) {
        showPasswordDialog = showPasswordPrompt
    }

    val isConnected by viewModel.isConnected.observeAsState(false)
    val passwordPromptChannel by viewModel.passwordPromptChannel.observeAsState(null)

    // Determine what messages to show based on current context (unified timelines)
    val displayMessages = when {
        selectedPrivatePeer != null -> privateChats[selectedPrivatePeer] ?: emptyList()
        currentChannel != null -> channelMessages[currentChannel] ?: emptyList()
        else -> {
            val locationChannel = selectedLocationChannel
            if (locationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                val geokey = "geo:${locationChannel.channel.geohash}"
                channelMessages[geokey] ?: emptyList()
            } else {
                messages // Mesh timeline
            }
        }
    }

    // Determine whether to show media buttons (only hide in geohash location chats)
    val showMediaButtons = when {
        selectedPrivatePeer != null -> true
        currentChannel != null -> true
        else -> selectedLocationChannel !is com.bitchat.android.geohash.ChannelID.Location
    }

    // Use WindowInsets to handle keyboard properly
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background) // Extend background to fill entire screen including status bar
    ) {
        val headerHeight = 42.dp
        
        // Main content area that responds to keyboard/window insets
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime) // This handles keyboard insets
                .windowInsetsPadding(WindowInsets.navigationBars) // Add bottom padding when keyboard is not expanded
        ) {
            // Header spacer - creates exact space for the floating header (status bar + compact header)
            Spacer(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(headerHeight)
            )

            // Tab bar: Chat/Feed toggle + mesh/channel name
            ChatTabBar(
                selectedTab = selectedTab,
                onTabChange = { viewModel.selectTab(it) },
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                selectedLocationChannel = selectedLocationChannel,
                onContextClick = { showLocationChannelsSheet = true }
            )

            if (selectedTab == "feed") {
                // Feed view
                com.bitchat.android.ui.feed.FeedTimeline(
                    viewModel = viewModel,
                    jumpToPostId = feedJumpTargetPostId,
                    onJumpHandled = { feedJumpTargetPostId = null },
                    modifier = Modifier.weight(1f)
                )
                com.bitchat.android.ui.feed.FeedInputBar(
                    onNewPost = { viewModel.showNewPostComposer() }
                )
            } else {
                // Chat view - Messages area
                MessagesList(
                    messages = displayMessages,
                    currentUserNickname = nickname,
                    meshService = viewModel.meshService,
                    lendingLoanRequestStatuses = lendingLoanRequestStatuses,
                    currentUserPeerId = viewModel.meshService.myPeerID,
                    lendingSharedCustodyReady = currentLendingSharedCustodyReady,
                    canReviewLoans = viewModel.canOpenLendingSignerReview(),
                    canAuthorizeLoans = viewModel.canAuthorizeLendingPayout(),
                    canDisburseLoans = viewModel.canManageFeedPins(),
                    canSetupTreasury = viewModel.canSetupLendingTreasury(),
                    modifier = Modifier.weight(1f),
                    forceScrollToBottom = forceScrollToBottom,
                    onScrolledUpChanged = { isUp -> isScrolledUp = isUp },
                    onNicknameClick = { fullSenderName ->
                        val currentText = messageText.text
                        val (baseName, hashSuffix) = splitSuffix(fullSenderName)
                        val selectedLocationChannel = viewModel.selectedLocationChannel.value
                        val mentionText = if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location && hashSuffix.isNotEmpty()) {
                            "@$baseName$hashSuffix"
                        } else {
                            "@$baseName"
                        }
                        val newText = when {
                            currentText.isEmpty() -> "$mentionText "
                            currentText.endsWith(" ") -> "$currentText$mentionText "
                            else -> "$currentText $mentionText "
                        }
                        messageText = TextFieldValue(
                            text = newText,
                            selection = TextRange(newText.length)
                        )
                    },
                    onMessageLongPress = { message ->
                        val (baseName, _) = splitSuffix(message.sender)
                        selectedUserForSheet = baseName
                        selectedMessageForSheet = message
                        showUserSheet = true
                    },
                    onCancelTransfer = { msg ->
                        viewModel.cancelMediaSend(msg.id)
                    },
                    onImageClick = { currentPath, allImagePaths, initialIndex ->
                        viewerImagePaths = allImagePaths
                        initialViewerIndex = initialIndex
                        showFullScreenImageViewer = true
                    },
                    onLoanVoteAction = viewModel::sendLendingVoteAction,
                    onLoanCancelAction = viewModel::sendLendingCancelAction,
                    onLoanForwardAction = { requestId, lendingId ->
                        forwardRequestId = requestId
                        forwardSourceLendingId = lendingId
                    },
                    onLoanReviewAction = viewModel::sendLendingReviewAction,
                    onLoanAuthorizeAction = viewModel::sendLendingAuthorizeAction,
                    onLoanSetupTreasuryAction = { viewModel.requestLendingTreasurySetup() },
                    onLoanDisburseAction = viewModel::sendLendingDisburseAction,
                    onEnsureLoanRequestStatus = viewModel::ensureLendingLoanRequestStatusLoaded,
                    onLoanRepayPrefill = { requestId, assetSymbol ->
                        val text = "/lending repay $requestId "
                        val assetExample = assetSymbol.ifBlank { "SOL" }
                        messageText = TextFieldValue(
                            text = text,
                            selection = TextRange(text.length)
                        )
                        commandHint = "enter amount, e.g. /lending repay $requestId 0.25 $assetExample"
                    }
                )
                // Input area - stays at bottom
                // Bridge file share from lower-level input to ViewModel
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    com.bitchat.android.ui.events.FileShareDispatcher.setHandler { peer, channel, path ->
                        viewModel.sendFileNote(peer, channel, path)
                    }
                }

                ChatInputSection(
                    messageText = messageText,
                    onMessageTextChange = { newText: TextFieldValue ->
                        messageText = newText
                        viewModel.updateMentionSuggestions(newText.text)
                        if (!newText.text.startsWith("/")) commandHint = null
                    },
                    onSend = {
                        if (messageText.text.trim().isNotEmpty()) {
                            val result = viewModel.sendMessage(messageText.text.trim())
                            if (result?.prefillText != null) {
                                val text = result.prefillText
                                messageText = TextFieldValue(
                                    text = text,
                                    selection = TextRange(result.cursorPosition ?: text.length)
                                )
                                commandHint = result.hintText
                            } else {
                                messageText = TextFieldValue("")
                                commandHint = null
                            }
                            forceScrollToBottom = !forceScrollToBottom
                        }
                    },
                    onOpenCommandSheet = { showCommandSheet = true },
                    onSendVoiceNote = { peer, onionOrChannel, path ->
                        viewModel.sendVoiceNote(peer, onionOrChannel, path)
                    },
                    onSendImageNote = { peer, onionOrChannel, path ->
                        viewModel.sendImageNote(peer, onionOrChannel, path)
                    },
                    onSendFileNote = { peer, onionOrChannel, path ->
                        viewModel.sendFileNote(peer, onionOrChannel, path)
                    },
                    commandHint = commandHint,
                    showMentionSuggestions = showMentionSuggestions,
                    mentionSuggestions = mentionSuggestions,
                    onMentionSuggestionClick = { mention: String ->
                        val mentionText = viewModel.selectMentionSuggestion(mention, messageText.text)
                        messageText = TextFieldValue(
                            text = mentionText,
                            selection = TextRange(mentionText.length)
                        )
                    },
                    selectedPrivatePeer = selectedPrivatePeer,
                    currentChannel = currentChannel,
                    nickname = nickname,
                    colorScheme = colorScheme,
                    showMediaButtons = showMediaButtons
                )
            }
        }

        // Floating header - positioned absolutely at top, ignores keyboard
        ChatFloatingHeader(
            headerHeight = headerHeight,
            selectedPrivatePeer = selectedPrivatePeer,
            currentChannel = currentChannel,
            nickname = nickname,
            viewModel = viewModel,
            colorScheme = colorScheme,
            onSidebarToggle = { viewModel.showSidebar() },
            onShowAppInfo = { viewModel.showAppInfo() },
            onPanicClear = { viewModel.panicClearAllData() },
            onLocationChannelsClick = { showLocationChannelsSheet = true },
            onLocationNotesClick = { showLocationNotesSheet = true },
            onShowWallet = { showWalletScreen = true },
            onNotificationsClick = { showNotificationsSheet = true }
        )

        // Divider under header - positioned after status bar + header height
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .offset(y = headerHeight)
                .zIndex(1f),
            color = colorScheme.outline.copy(alpha = 0.3f)
        )

        val alpha by animateFloatAsState(
            targetValue = if (showSidebar) 0.5f else 0f,
            animationSpec = tween(
                durationMillis = 300,
                easing = EaseOutCubic
            ), label = "overlayAlpha"
        )

        // Only render the background if it's visible
        if (alpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = alpha))
                    .clickable { viewModel.hideSidebar() }
                    .zIndex(1f)
            )
        }

        // Scroll-to-bottom floating button
        AnimatedVisibility(
            visible = isScrolledUp && !showSidebar,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 64.dp)
                .zIndex(1.5f)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .windowInsetsPadding(WindowInsets.ime)
        ) {
            Surface(
                shape = CircleShape,
                color = colorScheme.background,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(2.dp, BitchatColors.StatusSuccess)
            ) {
                IconButton(onClick = { forceScrollToBottom = !forceScrollToBottom }) {
                    Icon(
                        painter = rememberAppIconPainter(AppIcons.ArrowDown),
                        contentDescription = stringResource(com.bitchat.android.R.string.cd_scroll_to_bottom),
                        tint = BitchatColors.StatusSuccess
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showSidebar,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(300, easing = EaseOutCubic)
            ) + fadeIn(animationSpec = tween(300)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(250, easing = EaseInCubic)
            ) + fadeOut(animationSpec = tween(250)),
            modifier = Modifier.zIndex(2f)
        ) {
            SidebarOverlay(
                viewModel = viewModel,
                onDismiss = { viewModel.hideSidebar() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    // Full-screen image viewer - separate from other sheets to allow image browsing without navigation
    if (showFullScreenImageViewer) {
        FullScreenImageViewer(
            imagePaths = viewerImagePaths,
            initialIndex = initialViewerIndex,
            onClose = { showFullScreenImageViewer = false }
        )
    }

    if (showCommandSheet) {
        CommandPickerSheet(
            suggestions = commandSheetSuggestions,
            onDismiss = { showCommandSheet = false },
            onCommandSelected = { suggestion ->
                val autoRunCommands = setOf("/clear", "/w", "/channels", "/wallet")
                if (suggestion.command in autoRunCommands) {
                    viewModel.sendMessage(suggestion.command)
                    messageText = TextFieldValue("")
                    commandHint = null
                    showCommandSheet = false
                    return@CommandPickerSheet
                }
                val result = viewModel.selectCommandSuggestion(suggestion)
                val text = result.prefillText ?: "${suggestion.command} "
                messageText = TextFieldValue(
                    text = text,
                    selection = TextRange(result.cursorPosition ?: text.length)
                )
                commandHint = result.hintText
                viewModel.updateCommandSuggestions(text)
                showCommandSheet = false
            }
        )
    }

    // New Post Composer
    if (showNewPostComposer) {
        com.bitchat.android.ui.feed.NewPostComposer(
            onPost = { content, imageBytes, audioBytes, audioPath ->
                viewModel.createFeedPost(content, imageBytes, audioBytes, audioPath)
                viewModel.hideNewPostComposer()
            },
            onDismiss = { viewModel.hideNewPostComposer() }
        )
    }

    // Dialogs and Sheets
    ChatDialogs(
        showPasswordDialog = showPasswordDialog,
        passwordPromptChannel = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = { passwordInput = it },
        onPasswordConfirm = {
            if (passwordInput.isNotEmpty()) {
                val success = viewModel.joinChannel(passwordPromptChannel!!, passwordInput)
                if (success) {
                    showPasswordDialog = false
                    passwordInput = ""
                }
            }
        },
        onPasswordDismiss = {
            showPasswordDialog = false
            passwordInput = ""
        },
        showAppInfo = showAppInfo,
        onAppInfoDismiss = { viewModel.hideAppInfo() },
        showLocationChannelsSheet = showLocationChannelsSheet,
        onLocationChannelsSheetDismiss = { showLocationChannelsSheet = false },
        showLocationNotesSheet = showLocationNotesSheet,
        onLocationNotesSheetDismiss = { showLocationNotesSheet = false },
        showUserSheet = showUserSheet,
        onUserSheetDismiss = { 
            showUserSheet = false
            selectedMessageForSheet = null // Reset message when dismissing
        },
        selectedUserForSheet = selectedUserForSheet,
        selectedMessageForSheet = selectedMessageForSheet,
        viewModel = viewModel,
        onShowWallet = { showWalletScreen = true }
    )

    // Wallet screen (full screen overlay)
    if (showWalletScreen) {
        com.bitchat.android.solana.WalletScreen(
            onBack = { showWalletScreen = false },
            getPeersWithSolana = { viewModel.getPeersWithSolanaAddresses() }
        )
    }

    pendingLendingStakeApproval?.let { approval ->
        LendingStakeApprovalSheet(
            approval = approval,
            isSubmitting = isSubmittingLendingStakeApproval,
            onDismiss = { viewModel.dismissLendingStakeApproval() },
            onConfirm = { viewModel.confirmLendingStakeApproval() }
        )
    }
    pendingLendingLeaveApproval?.let { approval ->
        LendingLeaveApprovalSheet(
            approval = approval,
            isSubmitting = isSubmittingLendingLeaveApproval,
            onDismiss = { viewModel.dismissLendingLeaveApproval() },
            onConfirm = { viewModel.confirmLendingLeaveApproval() }
        )
    }
    pendingLendingTreasurySetup?.let { setup ->
        LendingTreasurySetupSheet(
            setup = setup,
            isSubmitting = isSubmittingLendingTreasurySetup,
            onDismiss = { viewModel.dismissLendingTreasurySetup() },
            onConfirm = { multisigAddress, vaultAddress, selectedSignerWalletAddresses ->
                viewModel.confirmLendingTreasurySetup(multisigAddress, vaultAddress, selectedSignerWalletAddresses)
            }
        )
    }

    forwardRequestId?.let { requestId ->
        val forwardTargets = availableLendingChannels.filter { it.lendingId != forwardSourceLendingId }
        AlertDialog(
            onDismissRequest = {
                forwardRequestId = null
                forwardSourceLendingId = null
            },
            title = { Text("Forward Request") },
            text = {
                if (forwardTargets.isEmpty()) {
                    Text("No other lending channels are available.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        forwardTargets.forEach { channel: LendingChannelEntity ->
                            OutlinedButton(
                                onClick = {
                                    viewModel.sendLendingForwardAction(requestId, channel.channelKey)
                                    forwardRequestId = null
                                    forwardSourceLendingId = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(channel.displayName.ifBlank { ChannelKeys.parseChannelName(channel.channelKey) })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        forwardRequestId = null
                        forwardSourceLendingId = null
                    }
                ) {
                    Text(if (forwardTargets.isEmpty()) "Close" else "Cancel")
                }
            }
        )
    }

    if (showNotificationsSheet) {
        InAppNotificationsSheet(
            viewModel = viewModel,
            onNotificationClick = { item ->
                val targetId = extractNotificationTarget(item.id)
                when (item.type) {
                    "dm" -> {
                        if (!targetId.isNullOrBlank()) {
                            viewModel.startPrivateChat(targetId)
                            viewModel.selectTab("chat")
                            showNotificationsSheet = false
                        }
                    }
                    "geohash" -> {
                        if (!targetId.isNullOrBlank()) {
                            val level = ChannelKeys.levelForGeohashLength(targetId.length)
                            val geohashChannel = com.bitchat.android.geohash.GeohashChannel(level, targetId)
                            viewModel.endPrivateChat()
                            viewModel.selectLocationChannel(com.bitchat.android.geohash.ChannelID.Location(geohashChannel))
                            viewModel.clearNotificationsForGeohash(targetId)
                            viewModel.selectTab("chat")
                            showNotificationsSheet = false
                        }
                    }
                    "feed" -> {
                        val postId = item.targetId ?: targetId
                        if (!postId.isNullOrBlank()) {
                            scope.launch {
                                val targetChannelKey = item.targetChannelKey
                                    ?: viewModel.getFeedPostChannelKey(postId)
                                viewModel.openFeedChannelScope(targetChannelKey)
                                feedJumpTargetPostId = postId
                                viewModel.selectTab("feed")
                                viewModel.expandPost(postId)
                                viewModel.clearNotificationsForFeedPost(postId)
                                showNotificationsSheet = false
                            }
                        }
                    }
                    "mention" -> {
                        viewModel.endPrivateChat()
                        viewModel.selectLocationChannel(com.bitchat.android.geohash.ChannelID.Mesh)
                        viewModel.clearMeshMentionNotifications()
                        viewModel.selectTab("chat")
                        showNotificationsSheet = false
                    }
                }
            },
            onDismiss = { showNotificationsSheet = false }
        )
    }
}

private fun extractNotificationTarget(id: String): String? {
    val target = id.substringAfterLast(':', "")
    return target.takeIf { it.isNotBlank() }
}

@Composable
private fun ChatInputSection(
    messageText: TextFieldValue,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onOpenCommandSheet: () -> Unit,
    onSendVoiceNote: (String?, String?, String) -> Unit,
    onSendImageNote: (String?, String?, String) -> Unit,
    onSendFileNote: (String?, String?, String) -> Unit,
    commandHint: String?,
    showMentionSuggestions: Boolean,
    mentionSuggestions: List<String>,
    onMentionSuggestionClick: (String) -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    colorScheme: ColorScheme,
    showMediaButtons: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BitchatColors.BackgroundLayer1
    ) {
        Column {
            HorizontalDivider(color = BitchatColors.InputFieldBorder)
            // Mention suggestions box
            if (showMentionSuggestions && mentionSuggestions.isNotEmpty()) {
                MentionSuggestionsBox(
                    suggestions = mentionSuggestions,
                    onSuggestionClick = onMentionSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.2f))
            }
            // Command hint bar (step guidance)
            if (commandHint != null) {
                Text(
                    text = commandHint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BitchatColors.AccentGreen.copy(alpha = 0.1f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = BitchatColors.AccentGreen
                )
            }
            MessageInput(
                value = messageText,
                onValueChange = onMessageTextChange,
                onSend = onSend,
                onOpenCommandSheet = onOpenCommandSheet,
                onSendVoiceNote = onSendVoiceNote,
                onSendImageNote = onSendImageNote,
                onSendFileNote = onSendFileNote,
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                showMediaButtons = showMediaButtons,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InAppNotificationsSheet(
    viewModel: ChatViewModel,
    onNotificationClick: (NotificationManager.InAppNotificationItem) -> Unit,
    onDismiss: () -> Unit
) {
    val notifications by viewModel.inAppNotifications.observeAsState(emptyList())
    val colorScheme = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BitchatColors.BackgroundLayer1
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Notifications",
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
                TextButton(onClick = { viewModel.clearInAppNotifications() }) {
                    Text("Clear all")
                }
            }
            if (notifications.isEmpty()) {
                Text(
                    text = "No notifications yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BitchatColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 18.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(notifications, key = { it.id }) { item ->
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            color = BitchatColors.InputFieldBg,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNotificationClick(item) }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = BitchatColors.TextPrimary
                                    )
                                    Text(
                                        text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                            .format(java.util.Date(item.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BitchatColors.TextTertiary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = item.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BitchatColors.TextSecondary
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LendingStakeApprovalSheet(
    approval: com.bitchat.android.lending.LendingStakeApprovalRequest,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BitchatColors.BackgroundLayer1
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = approval.actionLabel,
                style = MaterialTheme.typography.titleMedium,
                color = BitchatColors.TextPrimary
            )
            Text(
                text = "This signs and submits a real devnet transaction from your in-app wallet.",
                style = MaterialTheme.typography.bodySmall,
                color = BitchatColors.TextSecondary
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = BitchatShapes.Card,
                color = BitchatColors.InputFieldBg,
                border = BorderStroke(1.dp, BitchatColors.Border.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LendingStakeDetailRow("Channel", approval.channelDisplayName)
                    LendingStakeDetailRow("Asset", approval.assetDescriptor)
                    LendingStakeDetailRow("Amount", "${formatStakeApprovalAmount(approval.amountAtomic, approval.decimals)} ${approval.symbol}")
                    LendingStakeDetailRow("To treasury", shortKey(approval.treasuryAddress))
                    LendingStakeDetailRow("Network", "Solana Devnet")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onConfirm,
                    enabled = !isSubmitting,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BitchatColors.ButtonPrimaryBg,
                        contentColor = Color.White
                    )
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isSubmitting) "Signing..." else "Sign and Send")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LendingLeaveApprovalSheet(
    approval: com.bitchat.android.lending.LendingLeaveApprovalRequest,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BitchatColors.BackgroundLayer1
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Confirm stake refund",
                style = MaterialTheme.typography.titleMedium,
                color = BitchatColors.TextPrimary
            )
            Text(
                text = "Review and confirm the refund transaction from the channel treasury to your wallet.",
                style = MaterialTheme.typography.bodySmall,
                color = BitchatColors.TextSecondary
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = BitchatShapes.Card,
                color = BitchatColors.InputFieldBg,
                border = BorderStroke(1.dp, BitchatColors.Border.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LendingStakeDetailRow("Channel", approval.channelDisplayName)
                    LendingStakeDetailRow("Asset", approval.assetDescriptor)
                    LendingStakeDetailRow("Refund", "${formatStakeApprovalAmount(approval.amountAtomic, approval.decimals)} ${approval.symbol}")
                    LendingStakeDetailRow("From treasury", shortKey(approval.treasuryAddress))
                    LendingStakeDetailRow("To wallet", shortKey(approval.recipientAddress))
                    LendingStakeDetailRow("Network", "Solana Devnet")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onConfirm,
                    enabled = !isSubmitting,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BitchatColors.ButtonPrimaryBg,
                        contentColor = Color.White
                    )
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isSubmitting) "Confirming..." else "Confirm Refund")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LendingStakeDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = BitchatColors.TextTertiary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = BitchatColors.TextPrimary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LendingTreasurySetupSheet(
    setup: com.bitchat.android.lending.LendingTreasurySetupRequest,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, List<String>) -> Unit
) {
    var multisigAddress by remember(setup.channelKey) { mutableStateOf(setup.existingMultisigAddress) }
    var vaultAddress by remember(setup.channelKey) { mutableStateOf(setup.existingVaultAddress) }
    val defaultSelectedPeerIds = remember(setup.channelKey) {
        setup.signerCandidates
            .filter { it.recommended }
            .take(setup.recommendedSignerCount)
            .map { it.peerId }
            .toSet()
    }
    var selectedPeerIds by remember(setup.channelKey) { mutableStateOf(defaultSelectedPeerIds) }
    var showAdvancedDetails by remember(setup.channelKey) {
        mutableStateOf(setup.existingMultisigAddress.isNotBlank() || setup.existingVaultAddress.isNotBlank())
    }
    val selectedCandidates = setup.signerCandidates.filter { it.peerId in selectedPeerIds }
    val selectedApproverNames = selectedCandidates.joinToString(", ") { it.displayName }
    val enoughSelectedApprovers = selectedPeerIds.size >= setup.recommendedSignerCount

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BitchatColors.BackgroundLayer1
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Set up community treasury",
                style = MaterialTheme.typography.titleMedium,
                color = BitchatColors.TextPrimary
            )
            Text(
                text = "Choose trusted approvers for ${setup.channelDisplayName}. Members still vote first, but money only moves after this shared treasury is active.",
                style = MaterialTheme.typography.bodySmall,
                color = BitchatColors.TextSecondary
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = BitchatShapes.Card,
                color = BitchatColors.InputFieldBg,
                border = BorderStroke(1.dp, BitchatColors.Border.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LendingStakeDetailRow("Channel", setup.channelDisplayName)
                    LendingStakeDetailRow("Authorizations needed", "${setup.approvalThreshold} of ${setup.recommendedSignerCount}")
                    LendingStakeDetailRow("Who approves", if (selectedApproverNames.isBlank()) "Choose trusted people below" else selectedApproverNames)
                    LendingStakeDetailRow("Network", "Solana ${setup.cluster.replaceFirstChar { it.uppercase() }}")
                }
            }

            Text(
                text = "Recommended setup: you plus ${setup.approvalThreshold} trusted approvers. Only people with verified wallets appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = BitchatColors.TextSecondary
            )

            if (setup.signerCandidates.isEmpty()) {
                Text(
                    text = "No verified wallets were found for this lending channel yet. Ask the owner and approvers to open Bitchat with their wallet linked before creating treasury access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitchatColors.TextPrimary
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = BitchatShapes.Card,
                    color = BitchatColors.InputFieldBg,
                    border = BorderStroke(1.dp, BitchatColors.Border.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        setup.signerCandidates.forEach { candidate ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = candidate.peerId in selectedPeerIds,
                                    onCheckedChange = if (isSubmitting) {
                                        null
                                    } else { checked ->
                                        selectedPeerIds = if (checked == true) {
                                            selectedPeerIds + candidate.peerId
                                        } else {
                                            selectedPeerIds - candidate.peerId
                                        }
                                    }
                                )
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = candidate.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = BitchatColors.TextPrimary
                                    )
                                    Text(
                                        text = "${candidate.roleLabel} • ${shortKey(candidate.walletAddress)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BitchatColors.TextSecondary
                                    )
                                }
                                if (candidate.recommended) {
                                    Text(
                                        text = "Recommended",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BitchatColors.TextTertiary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = if (enoughSelectedApprovers) {
                    "Bitchat will use this approval group for treasury activation. If you already have a community wallet, connect it below."
                } else {
                    "Select at least ${setup.recommendedSignerCount} people with verified wallets to match the recommended 2-of-3 treasury model."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (enoughSelectedApprovers) BitchatColors.TextSecondary else BitchatColors.TextPrimary
            )

            TextButton(
                onClick = { showAdvancedDetails = !showAdvancedDetails },
                enabled = !isSubmitting
            ) {
                Text(if (showAdvancedDetails) "Hide existing wallet details" else "I already have a community wallet")
            }

            if (showAdvancedDetails) {
                OutlinedTextField(
                    value = multisigAddress,
                    onValueChange = { multisigAddress = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Community wallet ID") },
                    singleLine = true,
                    enabled = !isSubmitting
                )
                OutlinedTextField(
                    value = vaultAddress,
                    onValueChange = { vaultAddress = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Treasury vault ID (optional)") },
                    singleLine = true,
                    enabled = !isSubmitting
                )
                Text(
                    text = "Advanced: only enter the vault ID if your treasury uses a non-default vault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BitchatColors.TextTertiary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        onConfirm(
                            multisigAddress,
                            vaultAddress,
                            selectedCandidates.map { it.walletAddress }
                        )
                    },
                    enabled = !isSubmitting && enoughSelectedApprovers,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BitchatColors.ButtonPrimaryBg,
                        contentColor = Color.White
                    )
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        if (isSubmitting) {
                            if (multisigAddress.isBlank()) "Creating..." else "Connecting..."
                        } else {
                            if (multisigAddress.isBlank()) "Create Treasury" else "Connect Treasury"
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private fun formatStakeApprovalAmount(amountAtomic: Long, decimals: Int): String {
    if (decimals <= 0) return amountAtomic.toString()
    val divisor = Math.pow(10.0, decimals.toDouble())
    val normalized = amountAtomic.toDouble() / divisor
    val formatted = if (decimals >= 6) {
        "%,.4f".format(normalized)
    } else {
        "%,.2f".format(normalized)
    }
    return formatted.trimEnd('0').trimEnd('.')
}

private fun shortKey(value: String): String {
    return if (value.length > 16) {
        "${value.take(8)}...${value.takeLast(6)}"
    } else {
        value
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandPickerSheet(
    suggestions: List<CommandSuggestion>,
    onDismiss: () -> Unit,
    onCommandSelected: (CommandSuggestion) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BitchatColors.BackgroundLayer1
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Commands",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = com.bitchat.android.ui.theme.SatoshiFamily,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                ),
                color = colorScheme.onSurface
            )
            Text(
                text = "Tap a command to insert it",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = com.bitchat.android.ui.theme.SatoshiFamily
                ),
                color = BitchatColors.TextTertiary
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(suggestions) { cmd ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCommandSelected(cmd) },
                        shape = BitchatShapes.Card,
                        color = BitchatColors.InputFieldBg,
                        border = BorderStroke(1.dp, BitchatColors.Border.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val syntaxPart = cmd.syntax?.let { " $it" } ?: ""
                            Text(
                                text = "${cmd.command}$syntaxPart",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = com.bitchat.android.ui.theme.SatoshiFamily,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                ),
                                color = colorScheme.primary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = cmd.description,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = com.bitchat.android.ui.theme.SatoshiFamily
                                ),
                                color = colorScheme.onSurface.copy(alpha = 0.75f),
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
@Composable
private fun ChatTabBar(
    selectedTab: String = "chat",
    onTabChange: (String) -> Unit = {},
    selectedPrivatePeer: String?,
    currentChannel: String?,
    selectedLocationChannel: com.bitchat.android.geohash.ChannelID?,
    onContextClick: () -> Unit = {}
) {
    // Determine the current context label
    val contextLabel = when {
        selectedPrivatePeer != null -> {
            val (baseName, _) = splitSuffix(selectedPrivatePeer)
            "DM: @$baseName"
        }
        currentChannel != null -> "#$currentChannel"
        selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location -> {
            "#${selectedLocationChannel.channel.geohash}"
        }
        else -> "#mesh"
    }

    val contextColor = when {
        selectedPrivatePeer != null -> BitchatColors.SelfMessage
        currentChannel != null -> BitchatColors.MeshChannel
        selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location -> BitchatColors.LocationChannel
        else -> BitchatColors.MeshChannel
    }

    val borderColor = BitchatColors.Border

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        // Left: mesh/channel context label (tap to open location channels)
        Text(
            text = contextLabel,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = com.bitchat.android.ui.theme.SatoshiFamily,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            ),
            color = contextColor,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .clickable { onContextClick() }
        )

        // Right: Chat/Feed segmented control
        val pillShape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
        val selectedBg = BitchatColors.AccentGreen.copy(alpha = 0.15f)
        val selectedTextColor = BitchatColors.AccentGreen
        val unselectedTextColor = BitchatColors.TextTertiary
        Row(
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = selectedTextColor.copy(alpha = 0.4f),
                    shape = pillShape
                )
                .clip(pillShape),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chat tab
            Box(
                modifier = Modifier
                    .background(if (selectedTab == "chat") selectedBg else Color.Transparent)
                    .clickable { onTabChange("chat") }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Chat",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = com.bitchat.android.ui.theme.SatoshiFamily,
                        fontWeight = if (selectedTab == "chat") androidx.compose.ui.text.font.FontWeight.Bold
                            else androidx.compose.ui.text.font.FontWeight.Normal
                    ),
                    color = if (selectedTab == "chat") selectedTextColor else unselectedTextColor
                )
            }
            // Divider between tabs
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(borderColor)
            )
            // Feed tab
            Box(
                modifier = Modifier
                    .background(if (selectedTab == "feed") selectedBg else Color.Transparent)
                    .clickable { onTabChange("feed") }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Feed",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = com.bitchat.android.ui.theme.SatoshiFamily,
                        fontWeight = if (selectedTab == "feed") androidx.compose.ui.text.font.FontWeight.Bold
                            else androidx.compose.ui.text.font.FontWeight.Normal
                    ),
                    color = if (selectedTab == "feed") selectedTextColor else unselectedTextColor
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatFloatingHeader(
    headerHeight: Dp,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    viewModel: ChatViewModel,
    colorScheme: ColorScheme,
    onSidebarToggle: () -> Unit,
    onShowAppInfo: () -> Unit,
    onPanicClear: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit,
    onShowWallet: () -> Unit = {},
    onNotificationsClick: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val locationManager = remember { com.bitchat.android.geohash.LocationChannelManager.getInstance(context) }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .windowInsetsPadding(WindowInsets.statusBars), // Extend into status bar area
        color = colorScheme.background // Solid background color extending into status bar
    ) {
        TopAppBar(
            title = {
                ChatHeaderContent(
                    selectedPrivatePeer = selectedPrivatePeer,
                    currentChannel = currentChannel,
                    nickname = nickname,
                    viewModel = viewModel,
                    onBackClick = {
                        when {
                            selectedPrivatePeer != null -> viewModel.endPrivateChat()
                            currentChannel != null -> viewModel.switchToChannel(null)
                        }
                    },
                    onSidebarClick = onSidebarToggle,
                    onTripleClick = onPanicClear,
                    onShowAppInfo = onShowAppInfo,
                    onLocationChannelsClick = onLocationChannelsClick,
                    onLocationNotesClick = {
                        // Ensure location is loaded before showing sheet
                        locationManager.refreshChannels()
                        onLocationNotesClick()
                    },
                    onShowWallet = onShowWallet,
                    onNotificationsClick = onNotificationsClick
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            ),
            modifier = Modifier.height(headerHeight) // Ensure compact header height
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDialogs(
    showPasswordDialog: Boolean,
    passwordPromptChannel: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirm: () -> Unit,
    onPasswordDismiss: () -> Unit,
    showAppInfo: Boolean,
    onAppInfoDismiss: () -> Unit,
    showLocationChannelsSheet: Boolean,
    onLocationChannelsSheetDismiss: () -> Unit,
    showLocationNotesSheet: Boolean,
    onLocationNotesSheetDismiss: () -> Unit,
    showUserSheet: Boolean,
    onUserSheetDismiss: () -> Unit,
    selectedUserForSheet: String,
    selectedMessageForSheet: BitchatMessage?,
    viewModel: ChatViewModel,
    onShowWallet: (() -> Unit)? = null
) {
    // Password dialog
    PasswordPromptDialog(
        show = showPasswordDialog,
        channelName = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = onPasswordChange,
        onConfirm = onPasswordConfirm,
        onDismiss = onPasswordDismiss
    )

    // About sheet
    var showDebugSheet by remember { mutableStateOf(false) }
    AboutSheet(
        isPresented = showAppInfo,
        onDismiss = onAppInfoDismiss,
        onShowDebug = { showDebugSheet = true },
        onShowWallet = onShowWallet
    )
    if (showDebugSheet) {
        com.bitchat.android.ui.debug.DebugSettingsSheet(
            isPresented = showDebugSheet,
            onDismiss = { showDebugSheet = false },
            meshService = viewModel.meshService
        )
    }
    
    // Location channels sheet
    if (showLocationChannelsSheet) {
        LocationChannelsSheet(
            isPresented = showLocationChannelsSheet,
            onDismiss = onLocationChannelsSheetDismiss,
            viewModel = viewModel
        )
    }
    
    // Location notes sheet (extracted to separate presenter)
    if (showLocationNotesSheet) {
        LocationNotesSheetPresenter(
            viewModel = viewModel,
            onDismiss = onLocationNotesSheetDismiss
        )
    }
    
    // User action sheet
    if (showUserSheet) {
        ChatUserSheet(
            isPresented = showUserSheet,
            onDismiss = onUserSheetDismiss,
            targetNickname = selectedUserForSheet,
            selectedMessage = selectedMessageForSheet,
            viewModel = viewModel
        )
    }
}
