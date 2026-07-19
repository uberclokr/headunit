package com.xterra.helm.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Two-voice type system:
 *  - Labels/UI: system sans, wide tracking (eyebrow style).
 *  - Data readouts: monospace so gauge digits never jitter.
 * Drop custom fonts in res/font (e.g. IBM Plex Mono + Archivo) and swap here.
 */
val HelmType = Typography(
    displayLarge = TextStyle(          // big gauge numerals
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Light,
        fontSize = 64.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
    ),
    titleMedium = TextStyle(           // pane titles / eyebrows
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 2.4.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.6.sp,
    ),
)
