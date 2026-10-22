package com.listenlater

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit, onFeedback: () -> Unit, onUpdateFound: (UpdateInfo) -> Unit) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextC) }
            Spacer(Modifier.width(12.dp))
            Text("Settings", color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        BackHandler { onBack() }

        SettingsSection("Feedback") {
            Row(
                Modifier.fillMaxWidth().clickable { onFeedback() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "General feedback, bugs, and feature suggestions",
                    color = TextC, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp, modifier = Modifier.weight(1f),
                )
                Text("›", color = Muted, fontSize = 20.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("You see exactly what is sent before you send it.", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))

        SettingsSection("Updates") {
            var checking by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf("") }
            Button(
                onClick = {
                    if (checking) return@Button
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
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FillGreen, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Text(
                    if (checking) "Checking..." else "Check for updates",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
            if (result.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(result, color = Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
            Text("Version ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp),
    ) {
        if (title.isNotEmpty()) {
            Text(title, color = TextC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}
