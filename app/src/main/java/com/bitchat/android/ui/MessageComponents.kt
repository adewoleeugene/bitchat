package com.bitchat.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import android.content.Intent
import android.net.Uri
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.mesh.BluetoothMeshService
import java.text.SimpleDateFormat
import java.util.*
import com.bitchat.android.ui.theme.AppIcons
import com.bitchat.android.ui.theme.rememberAppIconPainter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import com.bitchat.android.ui.media.FileMessageItem
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.R
import androidx.compose.ui.res.stringResource
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import com.bitchat.android.ui.theme.BitchatColors
import com.bitchat.android.ui.theme.BitchatShapes
import com.bitchat.android.ui.theme.SatoshiFamily


// VoiceNotePlayer moved to com.bitchat.android.ui.media.VoiceNotePlayer

/**
 * Message display components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun MessagesList(
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    modifier: Modifier = Modifier,
    forceScrollToBottom: Boolean = false,
    onScrolledUpChanged: ((Boolean) -> Unit)? = null,
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null,
    onCancelTransfer: ((BitchatMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    
    // Track if this is the first time messages are being loaded
    var hasScrolledToInitialPosition by remember { mutableStateOf(false) }
    
    // Smart scroll: auto-scroll to bottom for initial load, then only when user is at or near the bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val layoutInfo = listState.layoutInfo
            val firstVisibleIndex = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            
            // With reverseLayout=true and reversed data, index 0 is the latest message at the bottom
            val isFirstLoad = !hasScrolledToInitialPosition
            val isNearLatest = firstVisibleIndex <= 2
            
            if (isFirstLoad || isNearLatest) {
                listState.animateScrollToItem(0)
                if (isFirstLoad) {
                    hasScrolledToInitialPosition = true
                }
            }
        }
    }
    
    // Track whether user has scrolled away from the latest messages
    val isAtLatest by remember {
        derivedStateOf {
            val firstVisibleIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            firstVisibleIndex <= 2
        }
    }
    LaunchedEffect(isAtLatest) {
        onScrolledUpChanged?.invoke(!isAtLatest)
    }
    
    // Force scroll to bottom when requested (e.g., when user sends a message)
    LaunchedEffect(forceScrollToBottom) {
        if (messages.isNotEmpty()) {
            // With reverseLayout=true and reversed data, latest is at index 0
            listState.animateScrollToItem(0)
        }
    }
    
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
        reverseLayout = true
    ) {
        items(
            items = messages.asReversed(),
            key = { it.id }
        ) { message ->
                MessageItem(
                    message = message,
                    messages = messages,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    onNicknameClick = onNicknameClick,
                    onMessageLongPress = onMessageLongPress,
                    onCancelTransfer = onCancelTransfer,
                    onImageClick = onImageClick
                )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    messages: List<BitchatMessage> = emptyList(),
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null,
    onCancelTransfer: ((BitchatMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val isSelf = message.senderPeerID == meshService.myPeerID ||
            message.sender == currentUserNickname ||
            message.sender.startsWith("$currentUserNickname#")
    val isSystem = message.sender == "system"

    // System messages: centered, no bubble
    if (isSystem) {
        SystemMessageItem(
            message = message,
            timeFormatter = timeFormatter,
            onMessageLongPress = onMessageLongPress
        )
        return
    }

    val peerColor = if (isSelf) BitchatColors.SelfMessage else getPeerColor(message)
    val bubbleBg = if (isSelf) BitchatColors.MessageBubbleSelf else BitchatColors.MessageBubblePeer
    val borderColor = peerColor.copy(alpha = 0.35f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start
    ) {
        // Nickname chip above the bubble
        NicknameChip(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Bordered content box
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .border(1.dp, borderColor, BitchatShapes.MessageBubble)
                .background(bubbleBg, BitchatShapes.MessageBubble)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Column {
                BubbleContent(
                    message = message,
                    messages = messages,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    colorScheme = colorScheme,
                    timeFormatter = timeFormatter,
                    onNicknameClick = onNicknameClick,
                    onMessageLongPress = onMessageLongPress,
                    onCancelTransfer = onCancelTransfer,
                    onImageClick = onImageClick
                )
            }
        }

        // Delivery status below bubble for private self messages
        if (message.isPrivate && isSelf) {
            message.deliveryStatus?.let { status ->
                Spacer(modifier = Modifier.height(2.dp))
                DeliveryStatusIcon(status = status)
            }
        }
    }
}

@Composable
private fun SystemMessageItem(
    message: BitchatMessage,
    timeFormatter: SimpleDateFormat,
    onMessageLongPress: ((BitchatMessage) -> Unit)?
) {
    val haptic = LocalHapticFeedback.current
    val annotatedText = buildAnnotatedString {
        pushStyle(SpanStyle(
            color = BitchatColors.TextTertiary,
            fontSize = (BASE_FONT_SIZE - 2).sp,
            fontStyle = FontStyle.Italic
        ))
        append("* ${message.content} *")
        pop()
        pushStyle(SpanStyle(
            color = BitchatColors.TextTertiary.copy(alpha = 0.5f),
            fontSize = (BASE_FONT_SIZE - 3).sp
        ))
        append(" [${timeFormatter.format(message.timestamp)}]")
        pop()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = annotatedText,
            fontFamily = SatoshiFamily,
            modifier = Modifier.pointerInput(message.id) {
                detectTapGestures(
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongPress?.invoke(message)
                    }
                )
            }
        )
    }
}

@Composable
private fun NicknameChip(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?
) {
    val nicknameText = formatNicknameAnnotatedString(
        message = message,
        currentUserNickname = currentUserNickname,
        meshService = meshService
    )
    if (nicknameText.isEmpty()) return

    val haptic = LocalHapticFeedback.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = nicknameText,
        fontFamily = SatoshiFamily,
        modifier = Modifier.pointerInput(message.id) {
            detectTapGestures(
                onTap = { pos ->
                    val l = layout ?: return@detectTapGestures
                    val offset = l.getOffsetForPosition(pos)
                    val ann = nicknameText.getStringAnnotations("nickname_click", offset, offset)
                    if (ann.isNotEmpty() && onNicknameClick != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNicknameClick.invoke(ann.first().item)
                    }
                },
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onMessageLongPress?.invoke(message)
                }
            )
        },
        onTextLayout = { layout = it }
    )
}

@Composable
private fun BubbleContent(
    message: BitchatMessage,
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    onCancelTransfer: ((BitchatMessage) -> Unit)?,
    onImageClick: ((String, List<String>, Int) -> Unit)?
) {
    when (message.type) {
        BitchatMessageType.Image -> {
            com.bitchat.android.ui.media.ImageMessageItem(
                message = message,
                messages = messages,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter,
                onNicknameClick = onNicknameClick,
                onMessageLongPress = onMessageLongPress,
                onCancelTransfer = onCancelTransfer,
                onImageClick = onImageClick,
                showHeader = false
            )
        }
        BitchatMessageType.Audio -> {
            com.bitchat.android.ui.media.AudioMessageItem(
                message = message,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter,
                onNicknameClick = onNicknameClick,
                onMessageLongPress = onMessageLongPress,
                onCancelTransfer = onCancelTransfer,
                showHeader = false
            )
        }
        BitchatMessageType.File -> {
            BubbleFileContent(
                message = message,
                currentUserNickname = currentUserNickname,
                onCancelTransfer = onCancelTransfer
            )
        }
        else -> {
            BubbleTextContent(
                message = message,
                messages = messages,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter,
                onNicknameClick = onNicknameClick,
                onMessageLongPress = onMessageLongPress,
                onImageClick = onImageClick
            )
        }
    }
}

@Composable
private fun BubbleTextContent(
    message: BitchatMessage,
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    onImageClick: ((String, List<String>, Int) -> Unit)?
) {
    val shouldAnimate = shouldAnimateMessage(message.id)

    if (shouldAnimate) {
        MessageWithMatrixAnimation(
            message = message,
            messages = messages,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onImageClick = onImageClick
        )
    } else {
        val contentText = formatContentAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )

        val isSelf = message.senderPeerID == meshService.myPeerID ||
                     message.sender == currentUserNickname ||
                     message.sender.startsWith("$currentUserNickname#")

        val haptic = LocalHapticFeedback.current
        val context = LocalContext.current
        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = contentText,
            modifier = Modifier.pointerInput(message) {
                detectTapGestures(
                    onTap = { position ->
                        val layout = textLayoutResult ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(position)
                        // Geohash teleport
                        val geohashAnnotations = contentText.getStringAnnotations("geohash_click", offset, offset)
                        if (geohashAnnotations.isNotEmpty()) {
                            val geohash = geohashAnnotations.first().item
                            try {
                                val locationManager = com.bitchat.android.geohash.LocationChannelManager.getInstance(context)
                                val level = when (geohash.length) {
                                    in 0..2 -> com.bitchat.android.geohash.GeohashChannelLevel.REGION
                                    in 3..4 -> com.bitchat.android.geohash.GeohashChannelLevel.PROVINCE
                                    5 -> com.bitchat.android.geohash.GeohashChannelLevel.CITY
                                    6 -> com.bitchat.android.geohash.GeohashChannelLevel.NEIGHBORHOOD
                                    else -> com.bitchat.android.geohash.GeohashChannelLevel.BLOCK
                                }
                                val channel = com.bitchat.android.geohash.GeohashChannel(level, geohash.lowercase())
                                locationManager.setTeleported(true)
                                locationManager.select(com.bitchat.android.geohash.ChannelID.Location(channel))
                            } catch (_: Exception) { }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            return@detectTapGestures
                        }
                        // URL open
                        val urlAnnotations = contentText.getStringAnnotations("url_click", offset, offset)
                        if (urlAnnotations.isNotEmpty()) {
                            val raw = urlAnnotations.first().item
                            val resolved = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) raw else "https://$raw"
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolved))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            return@detectTapGestures
                        }
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongPress?.invoke(message)
                    }
                )
            },
            fontFamily = SatoshiFamily,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = androidx.compose.ui.text.TextStyle(color = colorScheme.onSurface),
            onTextLayout = { result -> textLayoutResult = result }
        )
    }
}

@Composable
private fun BubbleFileContent(
    message: BitchatMessage,
    currentUserNickname: String,
    onCancelTransfer: ((BitchatMessage) -> Unit)?
) {
    val path = message.content.trim()
    val (overrideProgress, _) = when (val st = message.deliveryStatus) {
        is DeliveryStatus.PartiallyDelivered -> {
            if (st.total > 0 && st.reached < st.total) {
                (st.reached.toFloat() / st.total.toFloat()) to BitchatColors.StatusInfo
            } else null to null
        }
        else -> null to null
    }

    val packet = try {
        val file = java.io.File(path)
        if (file.exists()) {
            com.bitchat.android.model.BitchatFilePacket(
                fileName = file.name,
                fileSize = file.length(),
                mimeType = com.bitchat.android.features.file.FileUtils.getMimeTypeFromExtension(file.name),
                content = file.readBytes()
            )
        } else null
    } catch (_: Exception) { null }

    Box {
        if (packet != null) {
            if (overrideProgress != null) {
                com.bitchat.android.ui.media.FileSendingAnimation(
                    fileName = packet.fileName,
                    progress = overrideProgress,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                FileMessageItem(
                    packet = packet,
                    onFileClick = { }
                )
            }
            val showCancel = message.sender == currentUserNickname && (message.deliveryStatus is DeliveryStatus.PartiallyDelivered)
            if (showCancel) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .background(BitchatColors.TextSecondary.copy(alpha = 0.6f), CircleShape)
                        .clickable { onCancelTransfer?.invoke(message) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painter = rememberAppIconPainter(AppIcons.Close), contentDescription = stringResource(R.string.cd_cancel), tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        } else {
            Text(text = stringResource(R.string.file_unavailable), fontFamily = SatoshiFamily, color = BitchatColors.TextSecondary)
        }
    }
}


@Composable
fun DeliveryStatusIcon(status: DeliveryStatus) {
    val colorScheme = MaterialTheme.colorScheme
    
    when (status) {
        is DeliveryStatus.Sending -> {
            Text(
                text = stringResource(R.string.status_sending),
                fontSize = 12.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Sent -> {
            // Use a subtle hollow marker for Sent; single check is reserved for Delivered (iOS parity)
            Text(
                text = stringResource(R.string.status_pending),
                fontSize = 12.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Delivered -> {
            // Single check for Delivered (matches iOS expectations)
            Text(
                text = stringResource(R.string.status_sent),
                fontSize = 12.sp,
                color = colorScheme.primary.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.Read -> {
            Text(
                text = stringResource(R.string.status_delivered),
                fontSize = 12.sp,
                color = BitchatColors.LinkColor,
                fontWeight = FontWeight.Bold
            )
        }
        is DeliveryStatus.Failed -> {
            Text(
                text = stringResource(R.string.status_failed),
                fontSize = 12.sp,
                color = BitchatColors.StatusError.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.PartiallyDelivered -> {
            // Show a single subdued check without numeric label
            Text(
                text = stringResource(R.string.status_sent),
                fontSize = 12.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
    }
}
