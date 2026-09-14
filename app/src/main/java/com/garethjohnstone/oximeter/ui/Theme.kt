package com.garethjohnstone.oximeter.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Property names are capitalised so call sites read like the old constants.
 */
data class Palette(
    val id: String,
    val label: String,
    val note: String,
    val dark: Boolean,
    val Background: Color,
    val Panel: Color,
    val Divider: Color,
    val Spo2: Color,
    val Pulse: Color,
    val Respiration: Color,
    val Alarm: Color,
    val Caution: Color,
    val Label: Color,
    val Faint: Color
)

object Themes {

    /** Bedside-monitor convention: cyan saturation, green pulse, amber the rest. */
    val Clinical = Palette(
        id = "clinical",
        label = "Clinical",
        note = "Bedside monitor colours on near-black",
        dark = true,
        Background = Color(0xFF05080A),
        Panel = Color(0xFF0C1215),
        Divider = Color(0xFF1B262B),
        Spo2 = Color(0xFF38E1E6),
        Pulse = Color(0xFF5CE08A),
        Respiration = Color(0xFFE8CE6A),
        Alarm = Color(0xFFFF4D4D),
        Caution = Color(0xFFFFB020),
        Label = Color(0xFF7C8C93),
        Faint = Color(0xFF3C4A50)
    )

    /** Warm and dim, for a dark room at 3am without wrecking night vision. */
    val Night = Palette(
        id = "night",
        label = "Night",
        note = "Warm and dim, easier on the eyes in the dark",
        dark = true,
        Background = Color(0xFF07050A),
        Panel = Color(0xFF130D14),
        Divider = Color(0xFF2A1D26),
        Spo2 = Color(0xFFE08A5C),
        Pulse = Color(0xFFD9705E),
        Respiration = Color(0xFFB98A6A),
        Alarm = Color(0xFFFF6B5B),
        Caution = Color(0xFFD79A55),
        Label = Color(0xFF8A6E72),
        Faint = Color(0xFF4A3A42)
    )

    /** Maximum legibility from across a room. */
    val Contrast = Palette(
        id = "contrast",
        label = "High contrast",
        note = "Brightest option, readable from furthest away",
        dark = true,
        Background = Color(0xFF000000),
        Panel = Color(0xFF141414),
        Divider = Color(0xFF3A3A3A),
        Spo2 = Color(0xFF00FFFF),
        Pulse = Color(0xFF00FF6A),
        Respiration = Color(0xFFFFE500),
        Alarm = Color(0xFFFF2D2D),
        Caution = Color(0xFFFFA000),
        Label = Color(0xFFBFBFBF),
        Faint = Color(0xFF6E6E6E)
    )

    /** For daytime, on a bright screen. */
    val Daylight = Palette(
        id = "daylight",
        label = "Daylight",
        note = "Light background, for a sunlit room",
        dark = false,
        Background = Color(0xFFF6F7F8),
        Panel = Color(0xFFE7EAEC),
        Divider = Color(0xFFCBD2D6),
        Spo2 = Color(0xFF00727A),
        Pulse = Color(0xFF1F7A45),
        Respiration = Color(0xFF8A6A12),
        Alarm = Color(0xFFC81F1F),
        Caution = Color(0xFF9A5B00),
        Label = Color(0xFF5A676D),
        Faint = Color(0xFFA9B4B9)
    )

    val all = listOf(Clinical, Night, Contrast, Daylight)

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: Clinical
}

val LocalPalette = staticCompositionLocalOf { Themes.Clinical }

@Composable
fun ink(): Palette = LocalPalette.current

/** Numerals are the interface, so they get their own scale. */
object Numerals {
    val Primary = TextStyle(
        fontSize = 132.sp, lineHeight = 132.sp,
        fontWeight = FontWeight.Bold, textAlign = TextAlign.Start
    )
    val Secondary = TextStyle(
        fontSize = 104.sp, lineHeight = 104.sp,
        fontWeight = FontWeight.Bold, textAlign = TextAlign.Start
    )
    val Tertiary = TextStyle(
        fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium
    )
}

@Composable
fun OximeterTheme(themeId: String = Themes.Clinical.id, content: @Composable () -> Unit) {
    val palette = Themes.byId(themeId)
    val scheme = if (palette.dark) {
        darkColorScheme(
            background = palette.Background,
            surface = palette.Panel,
            onBackground = Color.White,
            onSurface = Color.White,
            error = palette.Alarm
        )
    } else {
        lightColorScheme(
            background = palette.Background,
            surface = palette.Panel,
            onBackground = Color(0xFF10181C),
            onSurface = Color(0xFF10181C),
            error = palette.Alarm
        )
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
