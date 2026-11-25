package com.listenlater

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onFeedback: () -> Unit,
    onUpdateFound: (UpdateInfo) -> Unit,
    username: String,
    serverUrl: String?,
    feedAvailable: Boolean,
    onCopyFeedLink: () -> Boolean,
    onSignOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderIconButton(onBack, "Back") { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = TextC) }
            Spacer(Modifier.width(12.dp))
            Text("Settings", color = TextC, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(18.dp))
        BackHandler { onBack() }

        SettingsSection("Account") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Signed in as", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
                Text(
                    username, color = TextC, fontSize = 14.sp, textAlign = TextAlign.End,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            // Only the address pill is tappable (real 48dp target): opens the server's webpage.
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Server", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    val label = serverUrl?.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "") ?: "Not set"
                    val pillColor = if (serverUrl != null) Sage else Muted
                    val pillText = if (serverUrl != null) SageText else MutedText
                    PressTile(
                        onTap = {
                            val raw = serverUrl?.trim().orEmpty()
                            val url = when {
                                raw.isEmpty() -> null
                                Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> raw
                                raw.contains("://") -> null   // non-web scheme: never open
                                else -> "https://$raw"
                            }
                            val opened = url != null && runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.isSuccess
                            if (!opened) {
                                Toast.makeText(context, "Couldn't open the server page", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = serverUrl != null, onClickLabel = "Open server webpage",
                        background = ClearBrush, corner = 24.dp, fill = false, minTouch = 48.dp, lift = false,
                    ) {
                        Text(
                            label, color = pillText, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(pillColor.copy(alpha = 0.12f))
                                .border(1.dp, pillColor.copy(alpha = 0.38f), RoundedCornerShape(50))
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PressTile(
                onTap = onSignOut, onClickLabel = "Sign out",
                background = DarkTileGradient, corner = 14.dp, lift = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Sign out", color = RedText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))

        // Copy feed link: its own button, like the old Home tile. When there is no feed
        // yet the button is disabled and the subtitle says why - never a silent no-op.
        var copied by remember { mutableStateOf(false) }
        val resetJob = remember { arrayOfNulls<Job>(1) }
        PressTile(
            onTap = {
                if (onCopyFeedLink()) {
                    copied = true
                    resetJob[0]?.cancel()
                    resetJob[0] = scope.launch { delay(1500); copied = false }
                }
            },
            enabled = feedAvailable, onClickLabel = "Copy feed link",
            background = SageGradient, corner = 18.dp,
            modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 10.dp)) {
                Text(
                    if (copied) "\u2713 Copied" else "Copy feed link",
                    color = OnSage, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (feedAvailable) "Paste into your podcast app" else "No feed link available yet",
                    color = OnSage.copy(alpha = 0.85f), fontSize = 12.sp, textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Accent") {
            val view = LocalView.current
            // Choices are saved as colours, so the order here is free to change.
            Swatches.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { col ->
                        val on = UiPrefs.accent == col
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .semantics { contentDescription = "Accent color"; selected = on }
                                .clickable(role = Role.RadioButton) { tapHaptic(view); UiPrefs.setAccent(col) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(42.dp).clip(CircleShape).background(col)
                                    .let { if (on) it.border(3.dp, TextC, CircleShape) else it },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Feel") {
            val view = LocalView.current
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp)
                    .toggleable(value = UiPrefs.haptics, role = Role.Switch, onValueChange = { next ->
                        // Ticks once when turned on, stays silent when turned off.
                        tapHaptic(view, next)
                        UiPrefs.setHaptics(next)
                    })
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Vibrate on press", color = TextC, fontSize = 15.sp, modifier = Modifier.weight(1f))
                SettingToggle(UiPrefs.haptics)
            }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Feedback & bugs") {
            PressTile(
                onTap = onFeedback, onClickLabel = "Send feedback",
                background = ClearBrush, corner = 8.dp, lift = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Send feedback, report a bug, or suggest a feature",
                        color = TextC, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        lineHeight = 20.sp, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        ChevronRightIcon,
                        contentDescription = null, tint = Muted, modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // Updates: untitled section, BingeMote pattern.
        SettingsSection("") {
            var checking by remember { mutableStateOf(false) }
            // Debug builds only: CI can show the result line without a network check.
            var result by remember {
                mutableStateOf(if (BuildConfig.DEBUG) (context as? android.app.Activity)?.intent?.getStringExtra("updateResult") ?: "" else "")
            }
            // Keyed on a per-check counter, not on the text: a second identical result must
            // restart the 5 second countdown, and clear-then-set within one frame can merge.
            var resultSeq by remember { mutableStateOf(0) }
            LaunchedEffect(resultSeq) {
                if (result == "You're up to date.") { delay(5000); result = "" }
            }
            PressTile(
                enabled = !checking, onClickLabel = "Check for updates",
                background = SageGradient, corner = 14.dp,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onTap = {
                        checking = true
                        result = ""
                        scope.launch {
                            val outcome = try {
                                withContext(Dispatchers.IO) {
                                    Updater.checkDetailed(BuildConfig.VERSION_NAME)
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                Updater.CheckOutcome.Unavailable
                            }
                            checking = false
                            resultSeq++
                            when (outcome) {
                                is Updater.CheckOutcome.Newer -> {
                                    result = "Version " + outcome.info.versionName + " is available."
                                    onUpdateFound(outcome.info)
                                }
                                Updater.CheckOutcome.UpToDate -> result = "You're up to date."
                                Updater.CheckOutcome.Unavailable -> result = "Couldn't check right now. Try again later."
                            }
                        }
                },
            ) {
                Text(
                    if (checking) "Checking..." else "Check for updates",
                    color = OnSage, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Version ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
                if (result.isNotEmpty()) {
                    Text(
                        result, color = Muted, fontSize = 12.sp, textAlign = TextAlign.End,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        // Bottom gap = the screen's own 16.dp padding, same as the sides (as in BingeMote).
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(CardGradient)
            .padding(16.dp),
    ) {
        if (title.isNotEmpty()) {
            Text(title, color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

/** Material "keyboard_arrow_right" chevron, same shape BingeMote shows (not in material-icons-core). */
private val ChevronRightIcon: androidx.compose.ui.graphics.vector.ImageVector = androidx.compose.ui.graphics.vector.ImageVector.Builder(
    name = "ChevronRight",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString("M8.59 16.59L13.17 12 8.59 7.41 10 6l6 6-6 6z").toNodes(),
    fill = androidx.compose.ui.graphics.SolidColor(Color.White),
).build()
