package com.magicmouse.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// MagicMouse dark theme — minimal, clean, Apple-inspired
private val DarkColorScheme = darkColorScheme(
    primary          = Color(0xFF64D2FF),   // Electric blue
    onPrimary        = Color(0xFF001F2E),
    secondary        = Color(0xFF30D158),   // Apple green — for "connected"
    onSecondary      = Color(0xFF00210C),
    error            = Color(0xFFFF453A),   // Apple red
    background       = Color(0xFF0A0A0F),
    onBackground     = Color(0xFFE5E5EA),
    surface          = Color(0xFF1C1C1E),
    onSurface        = Color(0xFFE5E5EA),
    surfaceVariant   = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline          = Color(0xFF3A3A3C),
)

@Composable
fun MagicMouseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
