package com.listenlater

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
            // 40dp visual tile inside a real 48dp touch target.
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(11.dp))
                    .semantics { contentDescription = "Back" }
                    .clickable(onClickLabel = "Back", role = Role.Button, onClick = onBack),
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
            // Tappable row (real 48dp target): opens the server's webpage.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = serverUrl != null, onClickLabel = "Open server webpage", role = Role.Button) {
                        val raw = serverUrl ?: return@clickable
                        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Server", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
                Text(
                    serverUrl?.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "") ?: "Not set",
                    color = if (serverUrl != null) Sage else Muted,
                    fontSize = 14.sp,
                    textDecoration = if (serverUrl != null) TextDecoration.Underline else TextDecoration.None,
                    textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
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
        var resetJob by remember { mutableStateOf<Job?>(null) }
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
                        resetJob?.cancel()
                        resetJob = scope.launch { delay(1500); copied = false }
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
                    if (feedAvailable) "Paste into your podcast app" else "No feed yet - share a video to create it",
                    color = OnSage.copy(alpha = 0.85f), fontSize = 12.sp, textAlign = TextAlign.Center,
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
                Text("\u203A", color = Muted, fontSize = 20.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("You see exactly what is sent before you send it.", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))

        // Updates: untitled section, BingeMote pattern.
        SettingsSection("") {
            var checking by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf("") }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                    .background(SageGradient)
                    .clickable(enabled = !checking, onClickLabel = "Check for updates", role = Role.Button) {
                        checking = true
                        result = ""
                        scope.launch {
                            val outcome = runCatching {
                                withContext(Dispatchers.IO) {
                                    Updater.checkDetailed(BuildConfig.VERSION_NAME)
                                }
                            }.getOrElse { Updater.CheckOutcome.Unavailable }
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
