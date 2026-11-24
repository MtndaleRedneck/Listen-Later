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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
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
    onCopyFeedLink: () -> Boolean,
    onSignOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = NavBg, shape = RoundedCornerShape(11.dp), modifier = Modifier.size(40.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextC) }
            }
            Spacer(Modifier.width(12.dp))
            Text("Settings", color = TextC, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        BackHandler { onBack() }

        SettingsSection("Account") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Signed in as", color = Muted, fontSize = 14.sp)
                Text(username, color = TextC, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Server", color = Muted, fontSize = 14.sp)
                // Tappable: opens the server's webpage.
                Text(
                    (serverUrl ?: "").removePrefix("https://").removePrefix("http://"),
                    color = Sage, fontSize = 14.sp, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        val url = serverUrl ?: return@clickable
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(DarkTileGradient).clickable { onSignOut() },
                contentAlignment = Alignment.Center,
            ) {
                Text("Sign out", color = Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))

        // Copy feed link: its own button, like the old Home tile.
        var copied by remember { mutableStateOf(false) }
        Box(
            Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(18.dp))
                .background(SageGradient)
                .clickable {
                    if (onCopyFeedLink()) {
                        copied = true
                        scope.launch { delay(1500); copied = false }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (copied) "\u2713 Copied" else "Copy feed link",
                    color = OnSage, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
                Text("Paste into your podcast app", color = OnSage.copy(alpha = 0.85f), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Feedback & bugs") {
            Row(
                Modifier.fillMaxWidth().clickable { onFeedback() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Send feedback, report a bug, or suggest a feature",
                    color = TextC, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp, modifier = Modifier.weight(1f),
                )
                Text("\u203A", color = Muted, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.height(14.dp))

        // Updates: untitled section, BingeMote pattern.
        SettingsSection("") {
            var checking by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf("") }
            Box(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(SageGradient)
                    .clickable(enabled = !checking) {
                        checking = true
                        result = ""
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                Updater.checkDetailed(BuildConfig.VERSION_NAME)
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
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (checking) "Checking..." else "Check for updates",
                    color = OnSage, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Version ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
                if (result.isNotEmpty()) Text(result, color = Muted, fontSize = 12.sp)
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
