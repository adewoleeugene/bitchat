package com.bitchat.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.mesh.BluetoothMeshService
import androidx.compose.material3.ColorScheme
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import com.bitchat.android.ui.theme.BitchatColors
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility functions for ChatScreen UI components
 * Extracted from ChatScreen.kt for better organization
 */

/**
 * Get RSSI-based color for signal strength visualization
 */
fun getRSSIColor(rssi: Int): Color {
    return when {
        rssi >= -50 -> BitchatColors.StatusSuccess
        rssi >= -60 -> BitchatColors.StatusSuccess.copy(alpha = 0.8f)
        rssi >= -70 -> BitchatColors.StatusWarning
        rssi >= -80 -> BitchatColors.SelfMessage
        else -> BitchatColors.StatusError
    }
}

/**
 * Format message as annotated string with iOS-style formatting
 * Timestamp at END, peer colors, hashtag suffix handling
 */
fun formatMessageAsAnnotatedString(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
): AnnotatedString {
    val builder = AnnotatedString.Builder()

    // Determine if this message was sent by self
    val isSelf = message.senderPeerID == meshService.myPeerID ||
                 message.sender == currentUserNickname ||
                 message.sender.startsWith("$currentUserNickname#")

    if (message.sender != "system") {
        // Get base color for this peer
        val baseColor = if (isSelf) {
            BitchatColors.SelfMessage
        } else {
            getPeerColor(message)
        }

        // Split sender into base name and hashtag suffix
        val (baseName, suffix) = splitSuffix(message.sender)

        // Content color: slightly dimmer than nickname for visual hierarchy
        val contentColor = if (isSelf) {
            baseColor.copy(alpha = 0.85f)
        } else {
            baseColor.copy(alpha = 0.85f)
        }

        // Sender prefix "<@"
        builder.pushStyle(SpanStyle(
            color = baseColor,
            fontSize = BASE_FONT_SIZE.sp,
            fontWeight = FontWeight.Bold
        ))
        builder.append("<@")
        builder.pop()

        // Base name (clickable, bold)
        builder.pushStyle(SpanStyle(
            color = baseColor,
            fontSize = BASE_FONT_SIZE.sp,
            fontWeight = FontWeight.Bold
        ))
        val nicknameStart = builder.length
        val truncatedBase = truncateNickname(baseName)
        builder.append(truncatedBase)
        val nicknameEnd = builder.length

        // Add click annotation for nickname (store canonical sender name with hash if available)
        if (!isSelf) {
            builder.addStringAnnotation(
                tag = "nickname_click",
                annotation = (message.originalSender ?: message.sender),
                start = nicknameStart,
                end = nicknameEnd
            )
        }
        builder.pop()

        // Hashtag suffix in lighter color
        if (suffix.isNotEmpty()) {
            builder.pushStyle(SpanStyle(
                color = baseColor.copy(alpha = 0.5f),
                fontSize = BASE_FONT_SIZE.sp,
                fontWeight = FontWeight.Normal
            ))
            builder.append(suffix)
            builder.pop()
        }

        // Sender suffix "> "
        builder.pushStyle(SpanStyle(
            color = baseColor,
            fontSize = BASE_FONT_SIZE.sp,
            fontWeight = FontWeight.Bold
        ))
        builder.append("> ")
        builder.pop()

        // Message content with hashtag and mention highlighting
        appendIOSFormattedContent(builder, message.content, message.mentions, currentUserNickname, contentColor, isSelf)

        // Timestamp at the END (smaller, tertiary text)
        builder.pushStyle(SpanStyle(
            color = BitchatColors.TextTertiary.copy(alpha = 0.8f),
            fontSize = (BASE_FONT_SIZE - 3).sp
        ))
        builder.append(" [${timeFormatter.format(message.timestamp)}]")
        // If message has valid PoW difficulty, append bits immediately after timestamp
        message.powDifficulty?.let { bits ->
            if (bits > 0) {
                builder.append(" ⛨${bits}b")
            }
        }
        builder.pop()

    } else {
        // System message — italic, dimmer
        builder.pushStyle(SpanStyle(
            color = BitchatColors.TextTertiary,
            fontSize = (BASE_FONT_SIZE - 2).sp,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        ))
        builder.append("* ${message.content} *")
        builder.pop()

        // Timestamp for system messages too
        builder.pushStyle(SpanStyle(
            color = BitchatColors.TextTertiary.copy(alpha = 0.5f),
            fontSize = (BASE_FONT_SIZE - 3).sp
        ))
        builder.append(" [${timeFormatter.format(message.timestamp)}]")
        builder.pop()
    }

    return builder.toAnnotatedString()
}

/**
 * Build only the nickname + timestamp header line for a message, matching styles of normal messages.
 */
fun formatMessageHeaderAnnotatedString(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
): AnnotatedString {
    val builder = AnnotatedString.Builder()

    val isSelf = message.senderPeerID == meshService.myPeerID ||
            message.sender == currentUserNickname ||
            message.sender.startsWith("$currentUserNickname#")

    if (message.sender != "system") {
        val baseColor = if (isSelf) BitchatColors.SelfMessage else getPeerColor(message)
        val (baseName, suffix) = splitSuffix(message.sender)

        // "<@"
        builder.pushStyle(SpanStyle(
            color = baseColor,
            fontSize = BASE_FONT_SIZE.sp,
            fontWeight = FontWeight.Bold
        ))
        builder.append("<@")
        builder.pop()

        // Base name (clickable when not self)
        builder.pushStyle(SpanStyle(
            color = baseColor,
            fontSize = BASE_FONT_SIZE.sp,
            fontWeight = FontWeight.Bold
        ))
        val nicknameStart = builder.length
        builder.append(truncateNickname(baseName))
        val nicknameEnd = builder.length
        if (!isSelf) {
            builder.addStringAnnotation(
                tag = "nickname_click",
                annotation = (message.originalSender ?: message.sender),
                start = nicknameStart,
                end = nicknameEnd
            )
        }
        builder.pop()

        // Hashtag suffix
        if (suffix.isNotEmpty()) {
            builder.pushStyle(SpanStyle(
                color = baseColor.copy(alpha = 0.5f),
                fontSize = BASE_FONT_SIZE.sp,
                fontWeight = FontWeight.Normal
            ))
            builder.append(suffix)
            builder.pop()
        }

        // Sender suffix ">"
        builder.pushStyle(SpanStyle(
            color = baseColor,
            fontSize = BASE_FONT_SIZE.sp,
            fontWeight = FontWeight.Bold
        ))
        builder.append(">")
        builder.pop()

        // Timestamp and optional PoW bits
        builder.pushStyle(SpanStyle(
            color = BitchatColors.TextTertiary.copy(alpha = 0.8f),
            fontSize = (BASE_FONT_SIZE - 3).sp
        ))
        builder.append("  [${timeFormatter.format(message.timestamp)}]")
        message.powDifficulty?.let { bits ->
            if (bits > 0) builder.append(" ⛨${bits}b")
        }
        builder.pop()
    } else {
        // System message header
        builder.pushStyle(SpanStyle(
            color = BitchatColors.TextTertiary,
            fontSize = (BASE_FONT_SIZE - 2).sp,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        ))
        builder.append("* ${message.content} *")
        builder.pop()
        builder.pushStyle(SpanStyle(
            color = BitchatColors.TextTertiary.copy(alpha = 0.5f),
            fontSize = (BASE_FONT_SIZE - 3).sp
        ))
        builder.append(" [${timeFormatter.format(message.timestamp)}]")
        builder.pop()
    }

    return builder.toAnnotatedString()
}

/**
 * Peer color assignment using djb2 hash algorithm
 * Avoids orange (~30°) reserved for self messages
 */
fun getPeerColor(message: BitchatMessage): Color {
    // Create seed from peer identifier (prioritizing stable keys)
    val seed = when {
        message.senderPeerID?.startsWith("nostr:") == true || message.senderPeerID?.startsWith("nostr_") == true -> {
            "nostr:${message.senderPeerID.lowercase()}"
        }
        message.senderPeerID?.length == 16 -> {
            "noise:${message.senderPeerID.lowercase()}"
        }
        message.senderPeerID?.length == 64 -> {
            "noise:${message.senderPeerID.lowercase()}"
        }
        else -> {
            message.sender.lowercase()
        }
    }

    return colorForPeerSeed(seed)
}

/**
 * Generate consistent peer color using djb2 hash (matches iOS algorithm exactly)
 * Always dark-mode optimized: moderate saturation, high brightness for #1A1A1A bg
 */
fun colorForPeerSeed(seed: String): Color {
    // djb2 hash algorithm (matches iOS implementation)
    var hash = 5381UL
    for (byte in seed.toByteArray()) {
        hash = ((hash shl 5) + hash) + byte.toUByte().toULong()
    }

    var hue = (hash % 360UL).toDouble() / 360.0

    // Avoid orange (~30°) reserved for self (matches iOS logic)
    val orange = 30.0 / 360.0
    if (kotlin.math.abs(hue - orange) < 0.05) {
        hue = (hue + 0.12) % 1.0
    }

    // Dark-only: moderate saturation, high brightness for contrast on #1A1A1A
    val saturation = 0.50
    val brightness = 0.85

    return Color.hsv(
        hue = (hue * 360).toFloat(),
        saturation = saturation.toFloat(),
        value = brightness.toFloat()
    )
}

/**
 * Split a name into base and a '#abcd' suffix if present (matches iOS splitSuffix exactly)
 */
fun splitSuffix(name: String): Pair<String, String> {
    if (name.length < 5) return Pair(name, "")

    val suffix = name.takeLast(5)
    if (suffix.startsWith("#") && suffix.drop(1).all {
        it.isDigit() || it.lowercaseChar() in 'a'..'f'
    }) {
        val base = name.dropLast(5)
        return Pair(base, suffix)
    }

    return Pair(name, "")
}

/**
 * Format only the nickname portion for display above a message bubble.
 * Returns "@nickname#abcd" with peer color, bold, clickable annotation.
 */
fun formatNicknameAnnotatedString(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val isSelf = message.senderPeerID == meshService.myPeerID ||
                 message.sender == currentUserNickname ||
                 message.sender.startsWith("$currentUserNickname#")

    if (message.sender == "system") return builder.toAnnotatedString()

    val baseColor = if (isSelf) BitchatColors.SelfMessage else getPeerColor(message)
    val (baseName, suffix) = splitSuffix(message.sender)

    // "@" prefix
    builder.pushStyle(SpanStyle(
        color = baseColor,
        fontSize = (BASE_FONT_SIZE - 2).sp,
        fontWeight = FontWeight.Bold
    ))
    builder.append("@")
    builder.pop()

    // Base name (clickable for peers)
    builder.pushStyle(SpanStyle(
        color = baseColor,
        fontSize = (BASE_FONT_SIZE - 2).sp,
        fontWeight = FontWeight.Bold
    ))
    val nicknameStart = builder.length
    builder.append(truncateNickname(baseName))
    val nicknameEnd = builder.length
    if (!isSelf) {
        builder.addStringAnnotation(
            tag = "nickname_click",
            annotation = (message.originalSender ?: message.sender),
            start = nicknameStart,
            end = nicknameEnd
        )
    }
    builder.pop()

    // Hashtag suffix
    if (suffix.isNotEmpty()) {
        builder.pushStyle(SpanStyle(
            color = baseColor.copy(alpha = 0.5f),
            fontSize = (BASE_FONT_SIZE - 2).sp,
            fontWeight = FontWeight.Normal
        ))
        builder.append(suffix)
        builder.pop()
    }

    return builder.toAnnotatedString()
}

/**
 * Format only the message content + timestamp for display inside a bubble.
 * No nickname prefix — just the content text with hashtag/mention/URL/geohash highlighting.
 */
fun formatContentAnnotatedString(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val isSelf = message.senderPeerID == meshService.myPeerID ||
                 message.sender == currentUserNickname ||
                 message.sender.startsWith("$currentUserNickname#")

    val baseColor = if (isSelf) BitchatColors.SelfMessage else getPeerColor(message)
    val contentColor = baseColor.copy(alpha = 0.85f)

    // Content with highlighting
    appendIOSFormattedContent(builder, message.content, message.mentions, currentUserNickname, contentColor, isSelf)

    // Timestamp
    builder.pushStyle(SpanStyle(
        color = BitchatColors.TextTertiary.copy(alpha = 0.8f),
        fontSize = (BASE_FONT_SIZE - 3).sp
    ))
    builder.append(" [${timeFormatter.format(message.timestamp)}]")
    message.powDifficulty?.let { bits ->
        if (bits > 0) builder.append(" ⛨${bits}b")
    }
    builder.pop()

    return builder.toAnnotatedString()
}

/**
 * Content formatting with proper hashtag and mention handling
 */
internal fun appendIOSFormattedContent(
    builder: AnnotatedString.Builder,
    content: String,
    mentions: List<String>?,
    currentUserNickname: String,
    baseColor: Color,
    isSelf: Boolean
) {
    // Patterns: allow optional '#abcd' suffix in mentions
    val hashtagPattern = "#([a-zA-Z0-9_]+)".toRegex()
    val mentionPattern = "@([\\p{L}0-9_]+(?:#[a-fA-F0-9]{4})?)".toRegex()

    val hashtagMatches = hashtagPattern.findAll(content).toList()
    val mentionMatches = mentionPattern.findAll(content).toList()

    // Combine and sort matches, but exclude hashtags that overlap with mentions
    val mentionRanges = mentionMatches.map { it.range }
    fun overlapsMention(range: IntRange): Boolean {
        return mentionRanges.any { mentionRange ->
            range.first < mentionRange.last && range.last > mentionRange.first
        }
    }

    val allMatches = mutableListOf<Pair<IntRange, String>>()

    // Add hashtag matches that don't overlap with mentions
    for (match in hashtagMatches) {
        if (!overlapsMention(match.range)) {
            allMatches.add(match.range to "hashtag")
        }
    }

    // Add all mention matches
    for (match in mentionMatches) {
        allMatches.add(match.range to "mention")
    }

    // Add standalone geohash matches
    val geoMatches = MessageSpecialParser.findStandaloneGeohashes(content)
    for (gm in geoMatches) {
        val range = gm.start until gm.endExclusive
        if (!overlapsMention(range)) {
            allMatches.add(range to "geohash")
        }
    }

    // Add URL matches
    val urlMatches = MessageSpecialParser.findUrls(content)
    for (um in urlMatches) {
        val range = um.start until um.endExclusive
        if (!overlapsMention(range)) {
            allMatches.add(range to "url")
        }
    }

    // Remove generic hashtag matches that overlap with detected geohash ranges
    fun rangesOverlap(a: IntRange, b: IntRange): Boolean {
        return a.first < b.last && a.last > b.first
    }
    val urlRanges = allMatches.filter { it.second == "url" }.map { it.first }
    val geoRanges = allMatches.filter { it.second == "geohash" }.map { it.first }
    if (geoRanges.isNotEmpty() || urlRanges.isNotEmpty()) {
        val iterator = allMatches.listIterator()
        while (iterator.hasNext()) {
            val (range, type) = iterator.next()
            val overlapsGeo = geoRanges.any { rangesOverlap(range, it) }
            val overlapsUrl = urlRanges.any { rangesOverlap(range, it) }
            if ((type == "hashtag" && overlapsGeo) || (type == "geohash" && overlapsUrl)) iterator.remove()
        }
    }

    allMatches.sortBy { it.first.first }

    var lastEnd = 0
    val isMentioned = mentions?.contains(currentUserNickname) == true

    for ((range, type) in allMatches) {
        // Add text before match
        if (lastEnd < range.first) {
            val beforeText = content.substring(lastEnd, range.first)
            if (beforeText.isNotEmpty()) {
                builder.pushStyle(SpanStyle(
                    color = baseColor,
                    fontSize = BASE_FONT_SIZE.sp,
                    fontWeight = FontWeight.Normal
                ))
                if (isMentioned) {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Normal))
                    builder.append(beforeText)
                    builder.pop()
                } else {
                    builder.append(beforeText)
                }
                builder.pop()
            }
        }

        // Add styled match
        val matchText = content.substring(range.first, range.last + 1)
        when (type) {
            "mention" -> {
                val mentionWithoutAt = matchText.removePrefix("@")
                val (mBase, mSuffix) = splitSuffix(mentionWithoutAt)

                // Check if this mention targets current user
                val isMentionToMe = mBase == currentUserNickname
                val mentionColor = if (isMentionToMe) BitchatColors.MentionHighlight else baseColor

                // "@" symbol
                builder.pushStyle(SpanStyle(
                    color = mentionColor,
                    fontSize = BASE_FONT_SIZE.sp,
                    fontWeight = FontWeight.Normal
                ))
                builder.append("@")
                builder.pop()

                // Base name
                builder.pushStyle(SpanStyle(
                    color = mentionColor,
                    fontSize = BASE_FONT_SIZE.sp,
                    fontWeight = FontWeight.Normal
                ))
                builder.append(truncateNickname(mBase))
                builder.pop()

                // Hashtag suffix in lighter color
                if (mSuffix.isNotEmpty()) {
                    builder.pushStyle(SpanStyle(
                        color = mentionColor.copy(alpha = 0.6f),
                        fontSize = BASE_FONT_SIZE.sp,
                        fontWeight = FontWeight.Normal
                    ))
                    builder.append(mSuffix)
                    builder.pop()
                }
            }
            "hashtag" -> {
                builder.pushStyle(SpanStyle(
                    color = baseColor,
                    fontSize = BASE_FONT_SIZE.sp,
                    fontWeight = FontWeight.Normal
                ))
                if (isMentioned) {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Normal))
                    builder.append(matchText)
                    builder.pop()
                } else {
                    builder.append(matchText)
                }
                builder.pop()
            }
            else -> {
                if (type == "geohash") {
                    builder.pushStyle(SpanStyle(
                        color = BitchatColors.LinkColor,
                        fontSize = BASE_FONT_SIZE.sp,
                        fontWeight = FontWeight.Normal,
                        textDecoration = TextDecoration.Underline
                    ))
                    val start = builder.length
                    builder.append(matchText)
                    val end = builder.length
                    val geohash = matchText.removePrefix("#").lowercase()
                    builder.addStringAnnotation(
                        tag = "geohash_click",
                        annotation = geohash,
                        start = start,
                        end = end
                    )
                    builder.pop()
                } else if (type == "url") {
                    builder.pushStyle(SpanStyle(
                        color = BitchatColors.LinkColor,
                        fontSize = BASE_FONT_SIZE.sp,
                        fontWeight = FontWeight.Normal,
                        textDecoration = TextDecoration.Underline
                    ))
                    val start = builder.length
                    builder.append(matchText)
                    val end = builder.length
                    builder.addStringAnnotation(
                        tag = "url_click",
                        annotation = matchText,
                        start = start,
                        end = end
                    )
                    builder.pop()
                } else {
                    builder.pushStyle(SpanStyle(
                        color = baseColor,
                        fontSize = BASE_FONT_SIZE.sp,
                        fontWeight = FontWeight.Normal
                    ))
                    builder.append(matchText)
                    builder.pop()
                }
            }
        }

        lastEnd = range.last + 1
    }

    // Add remaining text
    if (lastEnd < content.length) {
        val remainingText = content.substring(lastEnd)
        builder.pushStyle(SpanStyle(
            color = baseColor,
            fontSize = BASE_FONT_SIZE.sp,
            fontWeight = FontWeight.Normal
        ))
        if (isMentioned) {
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.Normal))
            builder.append(remainingText)
            builder.pop()
        } else {
            builder.append(remainingText)
        }
        builder.pop()
    }
}
