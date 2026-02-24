package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import com.bitchat.android.model.BitchatMessage
import androidx.compose.foundation.BorderStroke
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.BitchatShapes
import com.bitchat.android.ui.theme.CourierPrimeFamily

/**
 * User Action Sheet for selecting actions on a specific user (slap, hug, block)
 * Design language matches LocationChannelsSheet.kt for consistency
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatUserSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    targetNickname: String,
    selectedMessage: BitchatMessage? = null,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    
    // Bottom sheet state
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    
    // iOS system colors (matches LocationChannelsSheet exactly)
    val colorScheme = MaterialTheme.colorScheme
    val standardGreen = BitchatColors.AccentGreen // iOS green
    val standardBlue = BitchatColors.MeshChannel // iOS blue
    val standardRed = BitchatColors.Destructive // iOS red
    val standardGrey = BitchatColors.TextSecondary // iOS grey
    
    if (isPresented) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Text(
                    text = stringResource(R.string.at_nickname, targetNickname),
                    fontSize = 18.sp,
                    fontFamily = CourierPrimeFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Text(
                    text = if (selectedMessage != null) stringResource(R.string.choose_action_message_or_user) else stringResource(R.string.choose_action_user),
                    fontSize = 12.sp,
                    fontFamily = CourierPrimeFamily,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                // Solana address (if peer has one)
                val peerSolanaAddress = remember(targetNickname) {
                    viewModel.getPeerSolanaAddress(targetNickname)
                }
                val peerOwnershipProofs = remember(targetNickname) {
                    viewModel.getPeerOwnershipProofs(targetNickname)
                }
                if (peerSolanaAddress != null) {
                    val truncatedAddress = if (peerSolanaAddress.length > 12) {
                        "${peerSolanaAddress.take(6)}...${peerSolanaAddress.takeLast(4)}"
                    } else peerSolanaAddress
                    Surface(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(peerSolanaAddress))
                        },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SOL: $truncatedAddress",
                                fontSize = 12.sp,
                                fontFamily = CourierPrimeFamily,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                            Text(
                                text = "copy",
                                fontSize = 12.sp,
                                fontFamily = CourierPrimeFamily,
                                color = standardBlue
                            )
                        }
                    }
                }
                if (peerOwnershipProofs.isNotEmpty()) {
                    val hasSplProof = peerOwnershipProofs.any {
                        it.claimType == com.bitchat.android.model.SolanaOwnershipProof.ClaimType.SPL_TOKEN
                    }
                    val nftProofCount = peerOwnershipProofs.count {
                        it.claimType == com.bitchat.android.model.SolanaOwnershipProof.ClaimType.NFT_MINT ||
                            it.claimType == com.bitchat.android.model.SolanaOwnershipProof.ClaimType.NFT_COLLECTION
                    }
                    val badges = buildList {
                        if (hasSplProof) add("Token Holder")
                        if (nftProofCount > 0) add("NFT Collector")
                    }.joinToString(" · ")
                    val subtitle = if (badges.isNotEmpty()) {
                        "$badges (${peerOwnershipProofs.size} verified proofs)"
                    } else {
                        "${peerOwnershipProofs.size} verified ownership proofs"
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = subtitle,
                            fontSize = 12.sp,
                            fontFamily = CourierPrimeFamily,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                // Action list (iOS-style plain list)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Copy message action (only show if we have a message)
                    selectedMessage?.let { message ->
                        item {
                            UserActionRow(
                                title = stringResource(R.string.action_copy_message_title),
                                subtitle = stringResource(R.string.action_copy_message_subtitle),
                                titleColor = standardGrey,
                                onClick = {
                                    // Copy the message content to clipboard
                                    clipboardManager.setText(AnnotatedString(message.content))
                                    onDismiss()
                                }
                            )
                        }

                        // Notarize action - post message hash to Solana blockchain
                        if (message.type == com.bitchat.android.model.BitchatMessageType.Message) {
                            item {
                                val standardOrange = BitchatColors.SelfMessage // iOS orange
                                UserActionRow(
                                    title = "Notarize",
                                    subtitle = "Post message hash to Solana blockchain",
                                    titleColor = standardOrange,
                                    onClick = {
                                        viewModel.notarizeMessage(message)
                                        onDismiss()
                                    }
                                )
                            }

                            item {
                                UserActionRow(
                                    title = "View Notarization Proof",
                                    subtitle = "Show queue/confirmation details for this message",
                                    titleColor = standardBlue,
                                    onClick = {
                                        viewModel.inspectNotarization(message)
                                        onDismiss()
                                    }
                                )
                            }

                            item {
                                UserActionRow(
                                    title = "Process Notarization Queue",
                                    subtitle = "Try posting queued hashes now",
                                    titleColor = standardGreen,
                                    onClick = {
                                        viewModel.processNotarizationQueueNow()
                                        onDismiss()
                                    }
                                )
                            }

                            item {
                                UserActionRow(
                                    title = "Retry Failed Notarizations",
                                    subtitle = "Re-queue failed notarization attempts",
                                    titleColor = standardGrey,
                                    onClick = {
                                        viewModel.retryFailedNotarizations()
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                    
                    // Only show user actions for other users' messages or when no message is selected
                    if (selectedMessage?.sender != viewModel.nickname.value) {
                        // GM action
                        item {
                            UserActionRow(
                                title = "Say gm to $targetNickname",
                                subtitle = "Send a quick web3 greeting",
                                titleColor = standardBlue,
                                onClick = {
                                    // Send gm command
                                    viewModel.sendMessage("/gm $targetNickname")
                                    onDismiss()
                                }
                            )
                        }
                        
                        // Hug action  
                        item {
                            UserActionRow(
                                title = stringResource(R.string.action_hug_title, targetNickname),
                                subtitle = stringResource(R.string.action_hug_subtitle),
                                titleColor = standardGreen,
                                onClick = {
                                    // Send hug command
                                    viewModel.sendMessage("/hug $targetNickname")
                                    onDismiss()
                                }
                            )
                        }
                        
                        // Block action
                        item {
                            UserActionRow(
                                title = stringResource(R.string.action_block_title, targetNickname),
                                subtitle = stringResource(R.string.action_block_subtitle),
                                titleColor = standardRed,
                                onClick = {
                                    // Check if we're in a geohash channel
                                    val selectedLocationChannel = viewModel.selectedLocationChannel.value
                                    if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                                        // Get user's nostr public key and add to geohash block list
                                        viewModel.blockUserInGeohash(targetNickname)
                                    } else {
                                        // Regular mesh blocking
                                        viewModel.sendMessage("/block $targetNickname")
                                    }
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
                
                // Cancel button (iOS-style)
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BitchatColors.ButtonGhostBg,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = BitchatShapes.Button,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.cancel_lower),
                        fontSize = BASE_FONT_SIZE.sp,
                        fontFamily = CourierPrimeFamily
                    )
                }
            }
        }
    }
}

@Composable
private fun UserActionRow(
    title: String,
    subtitle: String,
    titleColor: Color,
    onClick: () -> Unit
) {
    // iOS-style list row with tinted background
    Surface(
        onClick = onClick,
        color = titleColor.copy(alpha = 0.08f),
        shape = BitchatShapes.Large,
        border = BorderStroke(1.dp, titleColor.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                fontSize = BASE_FONT_SIZE.sp,
                fontFamily = CourierPrimeFamily,
                fontWeight = FontWeight.Medium,
                color = titleColor
            )
            
            Text(
                text = subtitle,
                fontSize = 12.sp,
                fontFamily = CourierPrimeFamily,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
