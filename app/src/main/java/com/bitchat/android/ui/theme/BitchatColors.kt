package com.bitchat.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * BitChat Design System — Centralized Color Tokens
 * Single dark theme. No light/dark branching.
 */
object BitchatColors {
    // Backgrounds
    val Background = Color(0xFF1A1A1A)
    val BackgroundDeep = Color(0xFF0A0A0A)
    val BackgroundElevated = Color(0xFF222222)

    // Borders
    val Border = Color(0xFF333333)
    val BorderHover = Color(0xFF666666)
    val BorderActive = Color(0xFF888888)

    // Text hierarchy — brightened for mobile readability on dark backgrounds
    val TextPrimary = Color(0xFFE8E8E8)
    val TextSecondary = Color(0xFFA0A0A0)
    val TextTertiary = Color(0xFF8A8A8A)
    val TextDisabled = Color(0xFF606060)

    // Brand accent (green — used sparingly)
    val AccentGreen = Color(0xFF4ADE80)

    // Status
    val StatusSuccess = Color(0xFF4ADE80)
    val StatusWarning = Color(0xFFFACC15)
    val StatusError = Color(0xFFEF4444)
    val StatusInfo = Color(0xFF60A5FA)

    // Semantic / functional
    val SelfMessage = Color(0xFFFF9500)
    val MeshChannel = Color(0xFF60A5FA)
    val LocationChannel = Color(0xFF4ADE80)
    val NostrIndicator = Color(0xFF9B59B6)
    val FavoriteStar = Color(0xFFFACC15)
    val UnreadBadge = Color(0xFFFACC15)
    val MentionHighlight = Color(0xFFFF9500)
    val LinkColor = Color(0xFF60A5FA)
    val Destructive = Color(0xFFEF4444)

    // Interactive states
    val ButtonPrimaryBg = Color(0xFF4ADE80)
    val ButtonPrimaryFg = Color(0xFF0A0A0A)
    val ButtonDisabledBg = Color(0xFF333333)
    val ButtonDisabledFg = Color(0xFF555555)

    // Overlays
    val Overlay = Color(0x80000000)
    val SurfaceVariant = Color(0xFF2A2A2A)

    // Special
    val SolanaAccent = Color(0xFF9945FF)

    // --- Modernization tokens ---

    // Layered backgrounds for depth
    val BackgroundLayer0 = Color(0xFF0D0D0D)   // Deepest: sidebar
    val BackgroundLayer1 = Color(0xFF141414)   // Input area, sheet containers
    // BackgroundLayer2 = Background (#1A1A1A)
    // BackgroundLayer3 = BackgroundElevated (#222222)
    // BackgroundLayer4 = SurfaceVariant (#2A2A2A)

    // Message containment
    val MessageBubbleSelf = Color(0x1AFF9500)   // ~10% orange tint
    val MessageBubblePeer = Color(0x14E0E0E0)   // ~8% white tint
    val MessageBubbleSystem = Color(0x0D888888)  // ~5% grey tint

    // Button fills
    val ButtonGhostBg = Color(0x1AE0E0E0)       // ~10% white for ghost buttons
    val ButtonGhostBgHover = Color(0x26E0E0E0)   // ~15% white on hover/press

    // Glow effects (shadow/border tints on accent elements)
    val GlowGreen = Color(0x334ADE80)            // 20% green
    val GlowOrange = Color(0x33FF9500)           // 20% orange
    val GlowBlue = Color(0x3360A5FA)             // 20% blue
    val GlowSolana = Color(0x339945FF)           // 20% purple

    // Section accents
    val SectionAccentLine = Color(0xFF4ADE80)    // Green accent bar

    // Input area
    val InputFieldBg = Color(0xFF1E1E1E)         // Slightly lighter than background
    val InputFieldBorder = Color(0xFF3A3A3A)     // Distinct from general border
    val InputFieldBorderFocused = Color(0xFF4ADE80)  // Green on focus
}
