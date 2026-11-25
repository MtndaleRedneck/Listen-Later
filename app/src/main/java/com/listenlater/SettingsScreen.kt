package com.listenlater

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
    statusCentered: Boolean = false,
    onStatusCentered: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 40dp visual tile inside a real 48dp touch target.
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(11.dp))
                    .semantics { contentDescription = "Back" }
                    .clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Surface(color = NavBg, shape = RoundedCornerShape(11.dp), modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = TextC) }
                }
            }
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
                    Box(
                        Modifier.heightIn(min = 48.dp)
                            .clickable(enabled = serverUrl != null, onClickLabel = "Open server webpage", role = Role.Button) {
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
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label, color = pillColor, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(pillColor.copy(alpha = 0.12f))
                                .border(1.dp, pillColor.copy(alpha = 0.38f), RoundedCornerShape(50))
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                    .background(DarkTileGradient)
                    .clickable(onClickLabel = "Sign out", role = Role.Button) { onSignOut() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Sign out", color = Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))

        // Copy feed link: its own button, like the old Home tile. When there is no feed
        // yet the button is disabled and the subtitle says why - never a silent no-op.
        var copied by remember { mutableStateOf(false) }
        val resetJob = remember { arrayOfNulls<Job>(1) }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(18.dp))
                .background(SageGradient)
                .clickable(
                    enabled = feedAvailable,
                    onClickLabel = "Copy feed link",
                    role = Role.Button,
                ) {
                    if (onCopyFeedLink()) {
                        copied = true
                        resetJob[0]?.cancel()
                        resetJob[0] = scope.launch { delay(1500); copied = false }
                    }
                }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(if (feedAvailable) 1f else 0.55f),
            ) {
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

        SettingsSection("Feed list") {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Toggle centered status labels", role = Role.Switch) { onStatusCentered(!statusCentered) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Center status labels", color = TextC, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (statusCentered) "Centered in the status column" else "Right-aligned (default)", color = Muted, fontSize = 12.sp)
                }
                androidx.compose.material3.Switch(
                    checked = statusCentered, onCheckedChange = null,
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Sage, checkedThumbColor = Color.White),
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Feedback & bugs") {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Send feedback", role = Role.Button) { onFeedback() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Send feedback, report a bug, or suggest a feature",
                    color = TextC, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null, tint = Muted, modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // Updates: untitled section, BingeMote pattern.
        SettingsSection("") {
            var checking by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf("") }
            LaunchedEffect(result) {
                if (result == "You're up to date.") { delay(5000); result = "" }
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                    .background(SageGradient)
                    .clickable(enabled = !checking, onClickLabel = "Check for updates", role = Role.Button) {
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
                            when (outcome) {
                                is Updater.CheckOutcome.Newer -> {
                                    result = "Version " + outcome.info.versionName + " is available."
                                    onUpdateFound(outcome.info)
                                }
                                Updater.CheckOutcome.UpToDate -> result = "You're up to date."
                                Updater.CheckOutcome.Unavailable -> result = "Couldn't check right now. Try again later."
                            }
                        }
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
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
        Spacer(Modifier.height(28.dp))
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
