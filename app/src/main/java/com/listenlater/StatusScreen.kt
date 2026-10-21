package com.listenlater

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

const val FEED_ID = "individual-videos-youtube"

private fun Drawable.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap {
    val bmp = Bitmap.createBitmap(intrinsicWidth.coerceAtLeast(1), intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp.asImageBitmap()
}

@Composable
private fun AppHeader(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val icon = remember { ctx.packageManager.getApplicationIcon(ctx.packageName).toImageBitmap() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 18.dp)) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(12.dp))
        Text("Listen Later", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextC, modifier = Modifier.weight(1f))
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Muted)
        }
    }
}

private data class EpisodeItem(val id: String, val title: String, val status: String)

private sealed interface UpdateUi {
    data class Offer(val info: UpdateInfo) : UpdateUi
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(store: CredStore, onSignedOut: () -> Unit, onSessionExpired: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var episodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var feedUrl by remember { mutableStateOf(store.feedUrl) }
    var copied by remember { mutableStateOf(false) }
    var updateUi by remember { mutableStateOf<UpdateUi?>(null) }
    var updateJob by remember { mutableStateOf<Job?>(null) }
    var subScreen by remember { mutableStateOf("") } // "", "settings" or "feedback"

    fun host(): String = (store.server ?: "").removePrefix("https://").removePrefix("http://")

    fun copyFeedLink(): Boolean {
        val url = feedUrl ?: return false
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("feed", url))
        return true
    }

    fun load(onDone: (() -> Unit)? = null) {
        val server = store.server ?: return
        val token = store.token ?: return
        val api = Api(server, token)
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { api.episodes(FEED_ID) }
                episodes = raw.map { o: JSONObject ->
                    EpisodeItem(
                        o.optString("id", ""),
                        o.optString("title", "Untitled"),
                        o.optString("downloadStatus", "")
                    )
                }
                if (episodes.isNotEmpty()) {
                    try {
                        val url = withContext(Dispatchers.IO) { api.subscribeUrl(FEED_ID) }
                        store.feedUrl = url
                        feedUrl = url
                    } catch (e: Exception) {
                        // Keep any previously cached link.
                    }
                }
                loading = false
                onDone?.invoke()
            } catch (e: Api.SessionExpired) {
                onSessionExpired()
            } catch (e: Exception) {
                loading = false
                onDone?.invoke()
            }
        }
    }

    /** Once per open: ask GitHub for a newer release. Silent on any failure. */
    fun checkForUpdate() {
        val current = BuildConfig.VERSION_NAME
        scope.launch {
            val info = withContext(Dispatchers.IO) { Updater.check(current) } ?: return@launch
            if (info.versionName == store.skippedUpdate) return@launch
            updateUi = UpdateUi.Offer(info)
        }
    }

    fun launchInstaller(f: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            updateUi = UpdateUi.Failed("Could not open the installer.")
        }
    }

    fun startUpdate(info: UpdateInfo) {
        updateUi = UpdateUi.Downloading(info, 0f)
        updateJob = scope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    // Reuse a file that already survived the "allow installs" round-trip.
                    val existing = File(Updater.updateDir(context), "ListenLater-update.apk")
                    if (existing.exists()) {
                        try {
                            Updater.verify(context, existing)
                            return@withContext existing
                        } catch (e: Exception) {
                            existing.delete()
                        }
                    }
                    val file = Updater.download(
                        context, info,
                        { pr -> updateUi = UpdateUi.Downloading(info, pr.coerceIn(0f, 1f)) },
                        { !isActive }
                    )
                    try {
                        Updater.verify(context, file)
                    } catch (e: Updater.Failure) {
                        file.delete()
                        throw e
                    }
                    file
                }
                updateUi = null
                if (context.packageManager.canRequestPackageInstalls()) {
                    launchInstaller(f)
                } else {
                    Notifier.toast(context, "Allow Listen Later to install updates, then tap Update again.")
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            } catch (e: Updater.Failure) {
                updateUi = if (e.msg == "cancelled") null else UpdateUi.Failed(e.msg)
            } catch (e: CancellationException) {
                Updater.clean(context)
                updateUi = null
            } catch (e: Exception) {
                Updater.clean(context)
                updateUi = UpdateUi.Failed("The update could not be installed.")
            }
        }
    }

    LaunchedEffect(Unit) {
        load()
        checkForUpdate()
    }

    val listScroll = rememberScrollState()

    if (subScreen == "settings") {
        SettingsScreen(
            onBack = { subScreen = "" },
            onFeedback = { subScreen = "feedback" },
            onUpdateFound = { info -> updateUi = UpdateUi.Offer(info) },
        )
    } else if (subScreen == "feedback") {
        FeedbackScreen(onBack = { subScreen = "settings" })
    } else {

    val ptrState = rememberPullToRefreshState()
    var ptrRefreshing by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = ptrRefreshing,
        onRefresh = {
            ptrRefreshing = true
            load { ptrRefreshing = false }
        },
        state = ptrState,
        modifier = Modifier.fillMaxSize(),
        indicator = { GrowingSpinnerIndicator(ptrState, ptrRefreshing, Modifier.align(Alignment.TopCenter)) }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            Spacer(Modifier.height(2.dp))
            AppHeader(onSettings = { subScreen = "settings" })
            Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = Sage,
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.width(9.dp).height(9.dp)
                            ) {}
                            Spacer(Modifier.width(8.dp))
                            Text("Connected", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextC)
                        }
                        Text(host(), fontSize = 13.sp, color = Muted)
                    }
                }
            }

            if (!loading && episodes.isEmpty()) {
                Spacer(Modifier.height(14.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Nothing here yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextC)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Share your first video to create the feed. It downloads on your server and lands in your podcast app by itself.",
                            fontSize = 13.5.sp, color = Muted
                        )
                    }
                }
            }

            if (episodes.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)) {
                    Box {
                    Column(Modifier.verticalScroll(listScroll).padding(horizontal = 18.dp, vertical = 8.dp)) {
                        episodes.forEach { ep ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(ep.title, fontSize = 15.sp, color = TextC, modifier = Modifier.weight(1f).padding(end = 10.dp))
                                when {
                                    ep.status == "COMPLETED" ->
                                        Text("In feed", fontSize = 13.sp, color = Sage, fontWeight = FontWeight.SemiBold)
                                    ep.status == "FAILED" ->
                                        Text("Retry", fontSize = 13.sp, color = Yellow, fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.clickable {
                                                val server = store.server ?: return@clickable
                                                val token = store.token ?: return@clickable
                                                val api = Api(server, token)
                                                scope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) { api.retryEpisode(ep.id) }
                                                        load()
                                                    } catch (e: Api.SessionExpired) {
                                                        onSessionExpired()
                                                    } catch (e: Exception) {
                                                        // Leave the row as-is; next open re-reads state.
                                                    }
                                                }
                                            })
                                    ep.status == "PENDING" || ep.status == "DOWNLOADING" ->
                                        Text("Downloading", fontSize = 13.sp, color = Muted)
                                    else -> // READY: no file on the server, not in the feed
                                        Text("Not in feed", fontSize = 13.sp, color = Muted)
                                }
                            }
                        }
                    }
                    val topVisible by remember { derivedStateOf { listScroll.value > 300 } }
                    AnimatedVisibility(
                        visible = topVisible,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 },
                        exit = fadeOut(tween(150)),
                    ) {
                        Surface(
                            onClick = { scope.launch { listScroll.animateScrollTo(0, tween(350, easing = FastOutSlowInEasing)) } },
                            shape = RoundedCornerShape(50),
                            color = Card2,
                            border = BorderStroke(1.dp, Color(0xFF46464E)),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = TextC, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Top", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextC)
                            }
                        }
                    }
                }
                }

                Spacer(Modifier.height(14.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 13.dp)) {
                        Button(
                            onClick = {
                                if (copyFeedLink()) {
                                    copied = true
                                    scope.launch { delay(1500); copied = false }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Sage,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (copied) "\u2713 Copied" else "Copy feed link",
                                    fontSize = 15.sp, fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Paste into your podcast app",
                                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 13.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("Sign out", fontSize = 15.sp, color = Sage, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { onSignedOut() })
                        }
                    }
                }
            } else if (!loading) {
                Spacer(Modifier.height(14.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("Sign out", fontSize = 15.sp, color = Sage, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onSignedOut() })
                    }
                }
            }
        }

    }
    }

    updateUi?.let { u ->
        when (u) {
            is UpdateUi.Offer -> AlertDialog(
                onDismissRequest = { updateUi = null },
                containerColor = Card2,
                title = { Text("Listen Later ${u.info.versionName} is available", color = TextC) },
                text = {
                    Column {
                        Text("You have ${BuildConfig.VERSION_NAME}.", color = Muted)
                        if (u.info.notes.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            // Long notes scroll inside this box, so the buttons never get pushed off.
                            val maxNotes = (LocalConfiguration.current.screenHeightDp * 0.38f).dp
                            Column(Modifier.heightIn(max = maxNotes).verticalScroll(rememberScrollState())) {
                                u.info.notes.forEach {
                                    if (it.startsWith("## ")) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(it.drop(3), fontWeight = FontWeight.Bold, color = TextC)
                                    } else {
                                        Text("\u2022 $it", color = TextC)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Android will ask you to confirm the install.", color = Muted)
                        Spacer(Modifier.height(4.dp))
                        TextButton({ store.skippedUpdate = u.info.versionName; updateUi = null }) {
                            Text("Skip this version", color = Sage)
                        }
                    }
                },
                confirmButton = { TextButton({ startUpdate(u.info) }) { Text("Update", color = Sage) } },
                dismissButton = { TextButton({ updateUi = null }) { Text("Later", color = Muted) } },
            )
            is UpdateUi.Downloading -> AlertDialog(
                onDismissRequest = {},
                containerColor = Card2,
                title = { Text("Downloading ${u.info.versionName}", color = TextC) },
                text = {
                    LinearProgressIndicator(
                        progress = { u.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Sage,
                        trackColor = Card
                    )
                },
                confirmButton = { TextButton({ updateJob?.cancel() }) { Text("Cancel", color = Muted) } },
            )
            is UpdateUi.Failed -> AlertDialog(
                onDismissRequest = { updateUi = null },
                containerColor = Card2,
                title = { Text("Update not installed", color = TextC) },
                text = { Text(u.message, color = Muted) },
                confirmButton = { TextButton({ updateUi = null }) { Text("OK", color = Sage) } },
            )
        }
    }
}
