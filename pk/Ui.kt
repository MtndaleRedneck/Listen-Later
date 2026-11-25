package com.listenlater

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Light tick on press, when the user left haptics on. */
fun tapHaptic(view: View, on: Boolean = UiPrefs.haptics) {
    if (on) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

val ClearBrush: Brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))

/**
 * A button that sinks when pressed (to 0.94, dimmed about 15%, 90 ms down and 120 ms up) and ticks
 * once on touch-down when haptics are on. Same feel as BingeMote's buttons.
 *
 * [fill] true: the tile fills [modifier] (full-width buttons). False: it wraps its content, and
 * [minTouch] keeps a 48 dp touch target around it. [lift] adds the resting shadow of a filled button.
 */
@Composable
fun PressTile(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    background: Brush = CardGradient,
    corner: Dp = 14.dp,
    desc: String? = null,
    onClickLabel: String? = null,
    enabled: Boolean = true,
    fill: Boolean = true,
    minTouch: Dp = 0.dp,
    lift: Boolean = true,
    border: BorderStroke? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(if (pressed) 90 else 120), label = "sink")
    val dim by animateFloatAsState(if (pressed) 0.15f else 0f, tween(if (pressed) 90 else 120), label = "dim")
    val view = LocalView.current
    // Read the newest callback at fire time so a recomposition never restarts a press in progress.
    val tap by rememberUpdatedState(onTap)
    val shape = RoundedCornerShape(corner)
    // Keyboard / D-pad: reachable by Tab, Enter or D-pad centre activates, a ring shows where focus is.
    val focusSource = remember { MutableInteractionSource() }
    val focused by focusSource.collectIsFocusedAsState()
    var keyArmed by remember(enabled) { mutableStateOf(false) }

    Box(
        Modifier
            .heightIn(min = minTouch)
            .then(
                if (enabled) Modifier
                    .onFocusChanged { if (!it.isFocused) keyArmed = false }
                    .focusable(interactionSource = focusSource)
                    .onKeyEvent { e ->
                        val activate = e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.DirectionCenter
                        when {
                            !activate -> false
                            e.type == KeyEventType.KeyDown -> { keyArmed = true; true }
                            // Only a release that matches a press made while this tile had focus.
                            e.type == KeyEventType.KeyUp -> { if (keyArmed) { keyArmed = false; tapHaptic(view); tap() }; true }
                            else -> true
                        }
                    }
                else Modifier
            )
            .semantics(mergeDescendants = true) {
                if (desc != null) contentDescription = desc
                role = Role.Button
                if (!enabled) disabled()
                onClick(label = onClickLabel) { if (enabled) { tap(); true } else false }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    tapHaptic(view)
                    pressed = true
                    try {
                        val up = waitForUpOrCancellation()
                        if (up != null) tap()
                    } finally {
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f }
                .then(if (lift) Modifier.shadow(if (pressed || !enabled) 2.dp else 6.dp, shape) else Modifier)
                .clip(shape)
                .background(background)
                .then(if (border != null) Modifier.border(border, shape) else Modifier)
                .then(if (focused) Modifier.border(2.dp, TextC, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            content()
            if (dim > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = dim }.background(Color.Black))
        }
    }
}

/** The 40 dp dark tile behind back and Settings arrows. Sinks on press; only the icon dims. */
@Composable
fun HeaderIconButton(onClick: () -> Unit, desc: String, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val sink by animateFloatAsState(if (isPressed) 0.94f else 1f, tween(if (isPressed) 90 else 120), label = "sink")
    val dim by animateFloatAsState(if (isPressed) 0.15f else 0f, tween(if (isPressed) 90 else 120), label = "dim")
    val focused by interactionSource.collectIsFocusedAsState()
    // The 48 dp box is the touch target; the dark tile drawn inside it stays 40 dp, as in BingeMote.
    Box(
        modifier
            .size(48.dp)
            .semantics { contentDescription = desc }
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button) {
                tapHaptic(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .graphicsLayer { scaleX = sink; scaleY = sink }
                .clip(RoundedCornerShape((32f / 3f).dp))
                .background(NavBg)
                .then(if (focused) Modifier.border(2.dp, TextC, RoundedCornerShape((32f / 3f).dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.graphicsLayer { alpha = 1f - dim }, content = content)
        }
    }
}

/** The settings switch: gray track that fades into the accent ombré, 24 dp thumb. Same as BingeMote's. */
@Composable
fun SettingToggle(checked: Boolean) {
    val fade by animateFloatAsState(if (checked) 1f else 0f, tween(190, easing = FastOutSlowInEasing), label = "track")
    val thumbPad by animateDpAsState(if (checked) 24.dp else 4.dp, tween(190, easing = FastOutSlowInEasing), label = "thumb")
    Box(Modifier.width(52.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(TrackGray)) {
        Box(Modifier.fillMaxSize().alpha(fade).background(SageGradient))
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = thumbPad).size(24.dp).clip(CircleShape)
                .background(if (checked) Bg else TextC),
        )
    }
}

/** Dialog text button that ticks when haptics are on. */
@Composable
fun HTextButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val view = LocalView.current
    TextButton(onClick = { tapHaptic(view); onClick() }) { content() }
}
