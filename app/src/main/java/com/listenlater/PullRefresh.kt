package com.listenlater

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Stock pull gesture; the list stays put while a spinner in the gap above it
 * grows and rotates with pull distance, then spins on its own while refreshing.
 * (The full pull-down variant, where the content rides down with the finger,
 * is kept on the experiment/full-pull-down branch, not shipped.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrowingSpinnerIndicator(state: PullToRefreshState, modifier: Modifier = Modifier) {
    if (state.progress <= 0f && !state.isRefreshing) return // nothing shown, no animation running
    val spin by rememberInfiniteTransition(label = "ptrSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "ptrSpinAngle"
    )
    Box(modifier.padding(top = 18.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(28.dp)) {
            val frac = if (state.isRefreshing) 1f else state.progress.coerceIn(0f, 1f)
            val radius = size.minDimension / 2 * (0.35f + 0.65f * frac)
            val cx = size.width / 2
            val cy = size.height / 2
            val stroke = 3.dp.toPx()
            drawCircle(
                color = Sage.copy(alpha = 0.2f * frac),
                radius = radius,
                center = Offset(cx, cy),
                style = Stroke(width = stroke)
            )
            drawArc(
                color = Sage.copy(alpha = frac),
                startAngle = if (state.isRefreshing) spin else -90f + 300f * frac,
                sweepAngle = if (state.isRefreshing) 280f else 90f + 200f * frac,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}
