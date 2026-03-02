package com.bitchat.android.ui
// [Goose] Bridge file share events to ViewModel via dispatcher is installed in ChatScreen composition

// [Goose] Installing FileShareDispatcher handler in ChatScreen to forward file sends to ViewModel

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.media.FullScreenImageViewer
import com.bitchat.android.ui.theme.BitchatColors

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
    val showCommandSuggestions by viewModel.showCommandSuggestions.observeAsState(false)
    val commandSuggestions by viewModel.commandSuggestions.observeAsState(emptyList())
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
    var selectedUserForSheet by remember { mutableStateOf("") }
    var selectedMessageForSheet by remember { mutableStateOf<BitchatMessage?>(null) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    var viewerImagePaths by remember { mutableStateOf(emptyList<String>()) }
    var initialViewerIndex by remember { mutableStateOf(0) }
    var forceScrollToBottom by remember { mutableStateOf(false) }
    var isScrolledUp by remember { mutableStateOf(false) }
    var showWalletScreen by remember { mutableStateOf(false) }
    val selectedTab by viewModel.selectedTab.observeAsState("chat")
    val showNewPostComposer by viewModel.showNewPostComposer.observeAsState(false)

    // Show password dialog when needed
    LaunchedEffect(showPasswordPrompt) {
        showPasswordDialog = showPasswordPrompt
    }

    val isConnected by viewModel.isConnected.observeAsState(false)
    val passwordPromptChannel by viewModel.passwordPromptChannel.observeAsState(null)

    // Get location channel info for timeline switching
    val selectedLocationChannel by viewModel.selectedLocationChannel.observeAsState()

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
                        viewModel.updateCommandSuggestions(newText.text)
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
                    showCommandSuggestions = showCommandSuggestions,
                    commandSuggestions = commandSuggestions,
                    showMentionSuggestions = showMentionSuggestions,
                    mentionSuggestions = mentionSuggestions,
                    onCommandSuggestionClick = { suggestion: CommandSuggestion ->
                        val result = viewModel.selectCommandSuggestion(suggestion)
                        val text = result.prefillText ?: "${suggestion.command} "
                        messageText = TextFieldValue(
                            text = text,
                            selection = TextRange(result.cursorPosition ?: text.length)
                        )
                        commandHint = result.hintText
                    },
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
            onShowWallet = { showWalletScreen = true }
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

    // New Post Composer
    if (showNewPostComposer) {
        com.bitchat.android.ui.feed.NewPostComposer(
            onPost = { content, imageBytes ->
                viewModel.createFeedPost(content, imageBytes)
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
}

@Composable
private fun ChatInputSection(
    messageText: TextFieldValue,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendVoiceNote: (String?, String?, String) -> Unit,
    onSendImageNote: (String?, String?, String) -> Unit,
    onSendFileNote: (String?, String?, String) -> Unit,
    commandHint: String?,
    showCommandSuggestions: Boolean,
    commandSuggestions: List<CommandSuggestion>,
    showMentionSuggestions: Boolean,
    mentionSuggestions: List<String>,
    onCommandSuggestionClick: (CommandSuggestion) -> Unit,
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
            // Command suggestions box
            if (showCommandSuggestions && commandSuggestions.isNotEmpty()) {
                CommandSuggestionsBox(
                    suggestions = commandSuggestions,
                    onSuggestionClick = onCommandSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.2f))
            }
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
            .padding(horizontal = 12.dp, vertical = 5.dp),
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
    onShowWallet: () -> Unit = {}
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
                    onShowWallet = onShowWallet
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
