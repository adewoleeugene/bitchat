package com.bitchat.android.ui


import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.core.ui.utils.singleOrTripleClickable
import com.bitchat.android.geohash.LocationChannelManager.PermissionState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.CourierPrimeFamily
import com.bitchat.android.ui.icons.LucideIcon
import com.bitchat.android.ui.icons.LucideIconSet

/**
 * Header components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */


/**
 * Reactive helper to compute favorite state from fingerprint mapping
 * This eliminates the need for static isFavorite parameters and makes
 * the UI reactive to fingerprint manager changes
 */
@Composable
fun isFavoriteReactive(
    peerID: String,
    peerFingerprints: Map<String, String>,
    favoritePeers: Set<String>
): Boolean {
    return remember(peerID, peerFingerprints, favoritePeers) {
        val fingerprint = peerFingerprints[peerID]
        fingerprint != null && favoritePeers.contains(fingerprint)
    }
}

@Composable
fun TorStatusDot(
    modifier: Modifier = Modifier
) {
    val torStatus by com.bitchat.android.net.TorManager.statusFlow.collectAsState()
    
    if (torStatus.mode != com.bitchat.android.net.TorMode.OFF) {
        val dotColor = when {
            torStatus.running && torStatus.bootstrapPercent < 100 -> BitchatColors.SelfMessage // Orange - bootstrapping
            torStatus.running && torStatus.bootstrapPercent >= 100 -> BitchatColors.StatusSuccess // Green - connected
            else -> BitchatColors.StatusError // Red - error/disconnected
        }
        Canvas(
            modifier = modifier
        ) {
            val radius = size.minDimension / 2
            drawCircle(
                color = dotColor,
                radius = radius,
                center = Offset(size.width / 2, size.height / 2)
            )
        }
    }
}

@Composable
fun NoiseSessionIcon(
    sessionState: String?,
    modifier: Modifier = Modifier
) {
    val icon = when (sessionState) {
        "uninitialized" -> LucideIconSet.LockOpen
        "handshaking" -> LucideIconSet.RefreshCw
        "established" -> LucideIconSet.Lock
        else -> LucideIconSet.AlertTriangle // "failed" or any other state
    }
    val color: Color
    val contentDescription: String

    when (sessionState) {
        "uninitialized" -> {
            color = BitchatColors.TextDisabled // Grey - ready to establish
            contentDescription = stringResource(R.string.cd_ready_for_handshake)
        }
        "handshaking" -> {
            color = BitchatColors.TextDisabled // Grey - in progress
            contentDescription = stringResource(R.string.cd_handshake_in_progress)
        }
        "established" -> {
            color = BitchatColors.SelfMessage // Orange - secure
            contentDescription = stringResource(R.string.cd_encrypted)
        }
        else -> {
            color = BitchatColors.StatusError // Red - error
            contentDescription = stringResource(R.string.cd_handshake_failed)
        }
    }

    LucideIcon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = color
    )
}

@Composable
fun NicknameEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    
    // Auto-scroll to end when text changes (simulates cursor following)
    LaunchedEffect(value) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(
            text = stringResource(R.string.at_symbol),
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.primary.copy(alpha = 0.8f)
        )
        
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = colorScheme.primary,
                fontFamily = CourierPrimeFamily
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { 
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier
                .widthIn(max = 120.dp)
                .horizontalScroll(scrollState)
        )
    }
}

@Composable
fun PeerCounter(
    connectedPeers: List<String>,
    joinedChannels: Set<String>,
    hasUnreadChannels: Map<String, Int>,
    isConnected: Boolean,
    selectedLocationChannel: com.bitchat.android.geohash.ChannelID?,
    geohashPeople: List<GeoPerson>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    // Compute channel-aware people count and color (matches iOS logic exactly)
    val (peopleCount, countColor) = when (selectedLocationChannel) {
        is com.bitchat.android.geohash.ChannelID.Location -> {
            // Geohash channel: show geohash participants
            val count = geohashPeople.size
            val green = BitchatColors.StatusSuccess // Standard green
            Pair(count, if (count > 0) green else BitchatColors.TextSecondary)
        }
        is com.bitchat.android.geohash.ChannelID.Mesh,
        null -> {
            // Mesh channel: show Bluetooth-connected peers (excluding self)
            val count = connectedPeers.size
            val meshBlue = BitchatColors.MeshChannel // iOS-style blue for mesh
            Pair(count, if (isConnected && count > 0) meshBlue else BitchatColors.TextSecondary)
        }
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onClick() }.padding(end = 8.dp) // Added right margin to match "bitchat" logo spacing
    ) {
        LucideIcon(
            imageVector = LucideIconSet.Users,
            contentDescription = when (selectedLocationChannel) {
                is com.bitchat.android.geohash.ChannelID.Location -> stringResource(R.string.cd_geohash_participants)
                else -> stringResource(R.string.cd_connected_peers)
            },
            modifier = Modifier.size(22.dp),
            tint = countColor
        )
        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = "$peopleCount",
            style = MaterialTheme.typography.bodyMedium,
            color = countColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        
        if (joinedChannels.isNotEmpty()) {
            Text(
                text = stringResource(R.string.channel_count_prefix) + "${joinedChannels.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isConnected) BitchatColors.StatusSuccess else BitchatColors.StatusError,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ChatHeaderContent(
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    viewModel: ChatViewModel,
    onBackClick: () -> Unit,
    onSidebarClick: () -> Unit,
    onTripleClick: () -> Unit,
    onShowAppInfo: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit,
    onShowWallet: () -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme

    when {
        selectedPrivatePeer != null -> {
            // Private chat header - Fully reactive state tracking
            val favoritePeers by viewModel.favoritePeers.observeAsState(emptySet())
            val peerFingerprints by viewModel.peerFingerprints.observeAsState(emptyMap())
            val peerSessionStates by viewModel.peerSessionStates.observeAsState(emptyMap())
            val peerNicknames by viewModel.peerNicknames.observeAsState(emptyMap())
            
            // Reactive favorite computation - no more static lookups!
            val isFavorite = isFavoriteReactive(
                peerID = selectedPrivatePeer,
                peerFingerprints = peerFingerprints,
                favoritePeers = favoritePeers
            )
            val sessionState = peerSessionStates[selectedPrivatePeer]
            
            Log.d("ChatHeader", "Header recomposing: peer=$selectedPrivatePeer, isFav=$isFavorite, sessionState=$sessionState")
            
            // Pass geohash context and people for NIP-17 chat title formatting
            val selectedLocationChannel by viewModel.selectedLocationChannel.observeAsState()
            val geohashPeople by viewModel.geohashPeople.observeAsState(emptyList())

            PrivateChatHeader(
                peerID = selectedPrivatePeer,
                peerNicknames = peerNicknames,
                isFavorite = isFavorite,
                sessionState = sessionState,
                selectedLocationChannel = selectedLocationChannel,
                geohashPeople = geohashPeople,
                onBackClick = onBackClick,
                onToggleFavorite = { viewModel.toggleFavorite(selectedPrivatePeer) },
                viewModel = viewModel
            )
        }
        currentChannel != null -> {
            // Channel header
            ChannelHeader(
                channel = currentChannel,
                onBackClick = onBackClick,
                onLeaveChannel = { viewModel.leaveChannel(currentChannel) },
                onSidebarClick = onSidebarClick
            )
        }
        else -> {
            // Main header
            MainHeader(
                nickname = nickname,
                onNicknameChange = viewModel::setNickname,
                onTitleClick = onShowAppInfo,
                onTripleTitleClick = onTripleClick,
                onSidebarClick = onSidebarClick,
                onLocationChannelsClick = onLocationChannelsClick,
                onLocationNotesClick = onLocationNotesClick,
                onShowWallet = onShowWallet,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun PrivateChatHeader(
    peerID: String,
    peerNicknames: Map<String, String>,
    isFavorite: Boolean,
    sessionState: String?,
    selectedLocationChannel: com.bitchat.android.geohash.ChannelID?,
    geohashPeople: List<GeoPerson>,
    onBackClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    viewModel: ChatViewModel
) {
    val colorScheme = MaterialTheme.colorScheme
    val isNostrDM = peerID.startsWith("nostr_") || peerID.startsWith("nostr:")
    // Determine mutual favorite state for this peer (supports mesh ephemeral 16-hex via favorites lookup)
    val isMutualFavorite = remember(peerID, peerNicknames) {
        try {
            if (isNostrDM) return@remember false
            if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                val noiseKeyBytes = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKeyBytes)?.isMutual == true
            } else if (peerID.length == 16 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(peerID)?.isMutual == true
            } else false
        } catch (_: Exception) { false }
    }

    // Compute title text: for NIP-17 chats show "#geohash/@username" (iOS parity)
    val titleText: String = if (isNostrDM) {
        // For geohash DMs, get the actual source geohash and proper display name
        val (conversationGeohash, baseName) = try {
            val repoField = com.bitchat.android.ui.GeohashViewModel::class.java.getDeclaredField("repo")
            repoField.isAccessible = true
            val repo = repoField.get(viewModel.geohashViewModel) as com.bitchat.android.nostr.GeohashRepository
            val gh = repo.getConversationGeohash(peerID) ?: "geohash"
            val fullPubkey = com.bitchat.android.nostr.GeohashAliasRegistry.get(peerID) ?: ""
            val displayName = if (fullPubkey.isNotEmpty()) {
                repo.displayNameForGeohashConversation(fullPubkey, gh)
            } else {
                peerNicknames[peerID] ?: "unknown"
            }
            Pair(gh, displayName)
        } catch (e: Exception) { 
            Pair("geohash", peerNicknames[peerID] ?: "unknown")
        }
        
        "#$conversationGeohash/@$baseName"
    } else {
        // Prefer live mesh nickname; fallback to favorites nickname (supports 16-hex), finally short key
        peerNicknames[peerID] ?: run {
            val titleFromFavorites = try {
                if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                    val noiseKeyBytes = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKeyBytes)?.peerNickname
                } else if (peerID.length == 16 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(peerID)?.peerNickname
                } else null
            } catch (_: Exception) { null }
            titleFromFavorites ?: peerID.take(12)
        }
    }
    
    Box(modifier = Modifier.fillMaxWidth()) {
        // Back button - proper IconButton with adequate tap target
        IconButton(
            onClick = onBackClick,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                LucideIcon(
                    imageVector = LucideIconSet.ArrowLeft,
                    contentDescription = stringResource(R.string.back),
                    modifier = Modifier.size(22.dp),
                    tint = colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.chat_back),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.primary
                )
            }
        }

        // Title - perfectly centered regardless of other elements
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.Center)
        ) {

            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                color = BitchatColors.SelfMessage // Orange
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Show a globe when chatting via Nostr alias, or when mesh session not established but mutual favorite exists
            val showGlobe = isNostrDM || (sessionState != "established" && isMutualFavorite)
            if (showGlobe) {
                LucideIcon(
                    imageVector = LucideIconSet.Globe,
                    contentDescription = stringResource(R.string.cd_nostr_reachable),
                    modifier = Modifier.size(20.dp),
                    tint = BitchatColors.NostrIndicator // Purple like iOS
                )
            } else {
                NoiseSessionIcon(
                    sessionState = sessionState,
                    modifier = Modifier.size(20.dp)
                )
            }

        }
        
        // Favorite button - positioned on the right
        IconButton(
            onClick = {
                Log.d("ChatHeader", "Header toggle favorite: peerID=$peerID, currentFavorite=$isFavorite")
                onToggleFavorite()
            },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            LucideIcon(
                imageVector = LucideIconSet.Star,
                contentDescription = if (isFavorite) stringResource(R.string.cd_remove_favorite) else stringResource(R.string.cd_add_favorite),
                modifier = Modifier.size(20.dp), // Slightly larger than sidebar icon
                tint = if (isFavorite) BitchatColors.FavoriteStar else BitchatColors.TextDisabled // Yellow or grey
            )
        }
    }
}

@Composable
private fun ChannelHeader(
    channel: String,
    onBackClick: () -> Unit,
    onLeaveChannel: () -> Unit,
    onSidebarClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val displayName = ChannelKeys.displayName(channel)
    
    Box(modifier = Modifier.fillMaxWidth()) {
        // Back button - proper IconButton with adequate tap target
        IconButton(
            onClick = onBackClick,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                LucideIcon(
                    imageVector = LucideIconSet.ArrowLeft,
                    contentDescription = stringResource(R.string.back),
                    modifier = Modifier.size(22.dp),
                    tint = colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.chat_back),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.primary
                )
            }
        }

        // Title - perfectly centered regardless of other elements
        Text(
            text = stringResource(R.string.chat_channel_prefix, displayName),
            style = MaterialTheme.typography.titleMedium,
            color = BitchatColors.SelfMessage, // Orange to match input field
            modifier = Modifier
                .align(Alignment.Center)
                .clickable { onSidebarClick() }
        )
        
        // Leave button - positioned on the right
        TextButton(
            onClick = onLeaveChannel,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Text(
                text = stringResource(R.string.chat_leave),
                style = MaterialTheme.typography.bodySmall,
                color = BitchatColors.StatusError
            )
        }
    }
}

@Composable
private fun MainHeader(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    onTitleClick: () -> Unit,
    onTripleTitleClick: () -> Unit,
    onSidebarClick: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit,
    onShowWallet: () -> Unit = {},
    viewModel: ChatViewModel
) {
    val colorScheme = MaterialTheme.colorScheme
    val connectedPeers by viewModel.connectedPeers.observeAsState(emptyList())
    val joinedChannels by viewModel.joinedChannels.observeAsState(emptySet())
    val hasUnreadChannels by viewModel.unreadChannelMessages.observeAsState(emptyMap())
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.observeAsState(emptySet())
    val isConnected by viewModel.isConnected.observeAsState(false)
    val selectedLocationChannel by viewModel.selectedLocationChannel.observeAsState()
    val geohashPeople by viewModel.geohashPeople.observeAsState(emptyList())

    // Bookmarks store for current geohash toggle (iOS parity)
    val context = androidx.compose.ui.platform.LocalContext.current
    val bookmarksStore = remember { com.bitchat.android.geohash.GeohashBookmarksStore.getInstance(context) }
    val bookmarks by bookmarksStore.bookmarks.observeAsState(emptyList())

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.app_brand),
                style = MaterialTheme.typography.headlineSmall,
                color = colorScheme.primary,
                modifier = Modifier.singleOrTripleClickable(
                    onSingleClick = onTitleClick,
                    onTripleClick = onTripleTitleClick
                )
            )
            
            Spacer(modifier = Modifier.width(2.dp))
            
            NicknameEditor(
                value = nickname,
                onValueChange = onNicknameChange
            )
        }
        
        // Right section with location channels button and peer counter
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {

            // Unread private messages badge (click to open most recent DM)
            if (hasUnreadPrivateMessages.isNotEmpty()) {
                IconButton(
                    onClick = { viewModel.openLatestUnreadPrivateChat() },
                    modifier = Modifier.size(40.dp)
                ) {
                    LucideIcon(
                        imageVector = LucideIconSet.Mail,
                        contentDescription = stringResource(R.string.cd_unread_private_messages),
                        modifier = Modifier.size(22.dp),
                        tint = BitchatColors.SelfMessage
                    )
                }
            }

            // Bookmark toggle for current geohash (not shown for mesh)
            val currentGeohash: String? = when (val sc = selectedLocationChannel) {
                is com.bitchat.android.geohash.ChannelID.Location -> sc.channel.geohash
                else -> null
            }
            if (currentGeohash != null) {
                val isBookmarked = bookmarks.contains(currentGeohash)
                IconButton(
                    onClick = { bookmarksStore.toggle(currentGeohash) },
                    modifier = Modifier.size(40.dp)
                ) {
                    LucideIcon(
                        imageVector = if (isBookmarked) LucideIconSet.BookmarkCheck else LucideIconSet.Bookmark,
                        contentDescription = stringResource(R.string.cd_toggle_bookmark),
                        tint = if (isBookmarked) BitchatColors.StatusSuccess else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Location Notes button (extracted to separate component)
            LocationNotesButton(
                viewModel = viewModel,
                onClick = onLocationNotesClick
            )

            // Wallet button
            IconButton(
                onClick = onShowWallet,
                modifier = Modifier.size(40.dp)
            ) {
                LucideIcon(
                    imageVector = LucideIconSet.Wallet,
                    contentDescription = "Wallet",
                    modifier = Modifier.size(22.dp),
                    tint = BitchatColors.SolanaAccent
                )
            }

            // Tor status dot when Tor is enabled
            TorStatusDot(
                modifier = Modifier
                    .size(10.dp)
                    .padding(start = 0.dp, end = 2.dp)
            )
            
            // PoW status indicator
            PoWStatusIndicator(
                modifier = Modifier,
                style = PoWIndicatorStyle.COMPACT
            )
            Spacer(modifier = Modifier.width(2.dp))
            PeerCounter(
                connectedPeers = connectedPeers.filter { it != viewModel.meshService.myPeerID },
                joinedChannels = joinedChannels,
                hasUnreadChannels = hasUnreadChannels,
                isConnected = isConnected,
                selectedLocationChannel = selectedLocationChannel,
                geohashPeople = geohashPeople,
                onClick = onSidebarClick
            )
        }
    }
}

@Composable
private fun LocationChannelsButton(
    viewModel: ChatViewModel,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    // Get current channel selection from location manager
    val selectedChannel by viewModel.selectedLocationChannel.observeAsState()
    val teleported by viewModel.isTeleported.observeAsState(false)
    
    val (badgeText, badgeColor) = when (selectedChannel) {
        is com.bitchat.android.geohash.ChannelID.Mesh -> {
            "#mesh" to BitchatColors.MeshChannel // iOS blue for mesh
        }
        is com.bitchat.android.geohash.ChannelID.Location -> {
            val geohash = (selectedChannel as com.bitchat.android.geohash.ChannelID.Location).channel.geohash
            "#$geohash" to BitchatColors.StatusSuccess // Green for location
        }
        null -> "#mesh" to BitchatColors.MeshChannel // Default to mesh
    }
    
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = badgeColor
        ),
        contentPadding = PaddingValues(start = 4.dp, end = 0.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = CourierPrimeFamily
                ),
                color = badgeColor,
                maxLines = 1
            )
            
            // Teleportation indicator (like iOS)
            if (teleported) {
                Spacer(modifier = Modifier.width(2.dp))
                LucideIcon(
                    imageVector = LucideIconSet.MapPin,
                    contentDescription = stringResource(R.string.cd_teleported),
                    modifier = Modifier.size(18.dp),
                    tint = badgeColor
                )
            }
        }
    }
}
