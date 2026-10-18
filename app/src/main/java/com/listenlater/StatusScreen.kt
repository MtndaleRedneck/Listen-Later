package com.listenlater

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

const val FEED_ID = "individual-videos-youtube"

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
    var updateUi by remember { mutableStateOf<UpdateUi?>(null) }
    var updateJob by remember { mutableStateOf<Job?>(null) }

    fun host(): String = (store.server ?: "").removePrefix("https://").removePrefix("http://")

    fun copyFeedLink() {
        val url = feedUrl ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("feed", url))
        Notifier.toast(context, "Feed link copied")
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

    val ptrState = rememberPullToRefreshState()
    if (ptrState.isRefreshing) {
        LaunchedEffect(true) {
            load { ptrState.endRefresh() }
        }
    }

    Box(Modifier.fillMaxSize().nestedScroll(ptrState.nestedScrollConnection)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Spacer(Modifier.height(2.dp))
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
                Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(20.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 34.dp),
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
                Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Text("RECENTLY ADDED", fontSize = 13.sp, color = Muted, letterSpacing = 1.6.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
                        episodes.forEach { ep ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(ep.title, fontSize = 15.sp, color = TextC, modifier = Modifier.weight(1f).padding(end = 10.dp))
                                when {
                                    ep.status == "COMPLETED" ->
                                        Text("Ready", fontSize = 13.sp, color = Sage, fontWeight = FontWeight.SemiBold)
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
                                    else ->
                                        Text("Downloading", fontSize = 13.sp, color = Muted)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Feed link", fontSize = 15.sp, color = TextC)
                            Text("Copy", fontSize = 15.sp, color = Sage, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { copyFeedLink() })
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Sign out", fontSize = 15.sp, color = TextC)
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
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Sign out", fontSize = 15.sp, color = TextC)
                        Text("Sign out", fontSize = 15.sp, color = Sage, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onSignedOut() })
                    }
                }
            }
        }

        PullToRefreshContainer(ptrState, Modifier.align(Alignment.TopCenter))
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
