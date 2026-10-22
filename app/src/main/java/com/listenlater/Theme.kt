package com.listenlater

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0E0E10)
val Card = Color(0xFF1C1C20)
val Card2 = Color(0xFF26262C)
val NavBg = Color(0xFF141418)
val TextC = Color(0xFFF5F5F7)
val Muted = Color(0xFF9696A0)
val Sage = Color(0xFF6F9C7E)
/** Deeper green for filled buttons only: white on Sage reads 3.1:1, on this 4.7:1. Sage stays for dots and green text. */
val SageFill = Color(0xFF4F7D5E)

/** Debug builds only: the CI screenshot run can render the pre-1.5 button green for before/after shots. */
var SageFillOld = false
val FillGreen: Color get() = if (SageFillOld) Sage else SageFill
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
