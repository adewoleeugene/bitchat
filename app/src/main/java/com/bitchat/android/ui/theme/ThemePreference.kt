package com.bitchat.android.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Theme preference — dark-only per design system.
 * Kept as a stub for backward compatibility with SharedPreferences.
 */
enum class ThemePreference {
    Dark;

    val isDark: Boolean get() = true
}

object ThemePreferenceManager {
    private val _themeFlow = MutableStateFlow(ThemePreference.Dark)
    val themeFlow: StateFlow<ThemePreference> = _themeFlow

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        _themeFlow.value = ThemePreference.Dark
    }
}
