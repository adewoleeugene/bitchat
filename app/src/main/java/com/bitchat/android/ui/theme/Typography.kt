package com.bitchat.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bitchat.android.R

// Base font size for consistent scaling across the app
internal const val BASE_FONT_SIZE = com.bitchat.android.util.AppConstants.UI.BASE_FONT_SIZE_SP

// Satoshi — primary UI typeface (headings, labels, UI chrome)
val SatoshiFamily = FontFamily(
    Font(R.font.satoshi_variable, weight = FontWeight.Normal),
    Font(R.font.satoshi_variable, weight = FontWeight.Medium),
    Font(R.font.satoshi_variable, weight = FontWeight.Bold)
)

// Backward-compatible alias used across many screens.
// Keeping the symbol avoids large refactors while ensuring Satoshi is applied globally.
val CourierPrimeFamily = SatoshiFamily

// Typography using Satoshi — sized for mobile readability.
// Terminal/chat-specific surfaces can still opt into CourierPrimeFamily explicitly.
// Minimum 12sp for any visible text (WCAG mobile guideline)
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = SatoshiFamily,
        fontWeight = FontWeight.Normal,
        fontSize = (BASE_FONT_SIZE + 1).sp,
        lineHeight = (BASE_FONT_SIZE + 8).sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SatoshiFamily,
        fontWeight = FontWeight.Normal,
        fontSize = BASE_FONT_SIZE.sp,
        lineHeight = (BASE_FONT_SIZE + 6).sp
    ),
    bodySmall = TextStyle(
        fontFamily = SatoshiFamily,
        fontWeight = FontWeight.Normal,
        fontSize = (BASE_FONT_SIZE - 2).sp,    // 13sp (was 12sp)
        lineHeight = (BASE_FONT_SIZE + 2).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = SatoshiFamily,
        fontWeight = FontWeight.Normal,
        fontSize = (BASE_FONT_SIZE + 3).sp,
        lineHeight = (BASE_FONT_SIZE + 10).sp
    ),
    titleLarge = TextStyle(
        fontFamily = SatoshiFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (BASE_FONT_SIZE + 5).sp,
        lineHeight = (BASE_FONT_SIZE + 12).sp
    ),
    titleMedium = TextStyle(
        fontFamily = SatoshiFamily,
        fontWeight = FontWeight.Normal,
        fontSize = (BASE_FONT_SIZE + 1).sp,
        lineHeight = (BASE_FONT_SIZE + 8).sp
    ),
    titleSmall = TextStyle(
        fontFamily = SatoshiFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (BASE_FONT_SIZE - 1).sp,
        lineHeight = (BASE_FONT_SIZE + 6).sp
    ),
    labelLarge = TextStyle(
        fontFamily = SatoshiFamily,
        fontWeight = FontWeight.Medium,
        fontSize = BASE_FONT_SIZE.sp,
        lineHeight = (BASE_FONT_SIZE + 6).sp,
        letterSpacing = 0.5.sp
    ),
    labelMedium = TextStyle(
        fontFamily = SatoshiFamily,
        fontWeight = FontWeight.Normal,
        fontSize = (BASE_FONT_SIZE - 2).sp,    // 13sp (was 13sp, unchanged)
        lineHeight = (BASE_FONT_SIZE + 4).sp,
        letterSpacing = 0.8.sp
    ),
    labelSmall = TextStyle(
        fontFamily = SatoshiFamily,
        fontWeight = FontWeight.Medium,         // Medium weight for better readability at small size
        fontSize = (BASE_FONT_SIZE - 3).sp,    // 12sp (was 11sp)
        lineHeight = (BASE_FONT_SIZE + 2).sp,
        letterSpacing = 0.8.sp
    )
)
