package com.listenlater

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0E0E10)
val Card = Color(0xFF1C1C20)
val Card2 = Color(0xFF26262C)
val NavBg = Color(0xFF141418)
val TextC = Color(0xFFF5F5F7)
val Muted = Color(0xFF9696A0)
val Sage = Color(0xFF6F9C7E)
val SageL = Color(0xFF9CC4A6)
val OnSage = Color(0xFF0E0E10)
val Red = Color(0xFFF46F74)

/** Approved redesign surfaces: subtle vertical ombré on cards and filled tiles. */
val CardGradient: Brush = Brush.verticalGradient(listOf(Card2, Card))
val SageGradient: Brush = Brush.verticalGradient(listOf(SageL, Sage))
val DarkTileGradient: Brush = Brush.verticalGradient(listOf(Color(0xFF2E2E36), Card2))
val Yellow = Color(0xFFFFBE3C)

@Composable
fun ListenLaterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sage,
            onPrimary = Color.White,
            background = Bg,
            onBackground = TextC,
            surface = Card,
            onSurface = TextC,
            surfaceVariant = Card2,
            onSurfaceVariant = Muted
        ),
        content = content
    )
}
