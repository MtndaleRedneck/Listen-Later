package com.listenlater

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val FEED_ID = "individual-videos-youtube"

/** Per app-open memory: the automatic update offer shows once, not after every rebuild. */
object Session {
    var updateOffered = false
}

private fun Drawable.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap {
    val bmp = Bitmap.createBitmap(intrinsicWidth.coerceAtLeast(1), intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp.asImageBitmap()
}

// Stemmed up-arrow matching the approved Top pill design (material-icons-core has only the chevron).
private val TopArrowIcon: ImageVector = ImageVector.Builder(
    name = "TopArrow",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString("M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z").toNodes(),
    fill = SolidColor(Color.White),
).build()

@Composable
private fun AppHeader(onSettings: () -> Unit, connected: Boolean) {
    val ctx = LocalContext.current
    val icon = remember { ctx.packageManager.getApplicationIcon(ctx.packageName).toImageBitmap() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 0.dp, end = 0.dp, top = 12.dp, bottom = 8.dp)) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)))
        Spacer(Modifier.width(8.dp))
        // Narrow phone plus a large font scale: the title would take the width the status pill needs
        // ("Disconnected" clipped at 360dp x 1.3), so the title stays at 18sp unscaled there.
        val titleCfg = LocalConfiguration.current
        val titleSize = if (titleCfg.screenWidthDp < 400 && titleCfg.fontScale > 1.15f) (18f / titleCfg.fontScale).sp else 20.sp
        Text("Listen Later", fontSize = titleSize, fontWeight = FontWeight.Bold, color = TextC, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Status pill centered in the free space between the name and the gear tile;
        // bounded so a long status wraps instead of competing with the title.
        BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // "Connected" is one word: a hard width squeeze wraps it mid-word ("Connect/ed").
            // Keep it on a single unwrapped line; when the free header space is tight
            // (narrow widths, large font scales) switch to the compact pill instead of
            // shrinking or truncating the word. Offline keeps the full message, wrapping
            // at word boundaries within the free space.
            // Threshold scales with the system font scale: at 1.3x the normal pill needs
            // roughly 125-130dp, so the compact pill must take over proportionally earlier
            // instead of clipping the word at the old fixed cutoff.
            val availWidth = maxWidth
            val fontScale = LocalConfiguration.current.fontScale
            val compact = availWidth < 112.dp * fontScale
            Surface(color = NavBg, shape = RoundedCornerShape(50), modifier = Modifier.widthIn(max = maxWidth)) {
                Row(
                    Modifier.padding(horizontal = if (compact) 8.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(color = if (connected) Sage else Red, shape = RoundedCornerShape(50), modifier = Modifier.width(if (compact) 7.dp else 9.dp).height(if (compact) 7.dp else 9.dp)) {}
                    Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
                    if (connected) {
                        // Same shrink-to-fit as Disconnected (floor 10sp) so "Connected" is never clipped.
                        var connSize by remember(availWidth, fontScale) { mutableStateOf(if (compact) 12f else 13f) }
                        Text(
                            "Connected", fontSize = connSize.sp, fontWeight = FontWeight.SemiBold, color = TextC,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                            onTextLayout = { if (it.didOverflowWidth && connSize > 10f) connSize -= 0.5f },
                        )
                    } else {
                        // "Disconnected" is long: shrink the text until it fits the free header space (down to 10sp)
                        // instead of clipping it, at any phone width or font scale.
                        var discSize by remember(availWidth, fontScale) { mutableStateOf(if (compact) 12f else 13f) }
                        Text(
                            "Disconnected", fontSize = discSize.sp, fontWeight = FontWeight.SemiBold, color = TextC,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                            onTextLayout = { if (it.didOverflowWidth && discSize > 10f) discSize -= 0.5f },
                        )
                    }
                }
            }
        }
        HeaderIconButton(onSettings, "Settings", Modifier.offset(x = 4.dp)) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = Sage)
        }
    }
}

/** Fixed-width status column so every status ends at the same right edge. */
@Composable
private fun StatusColumn(content: @Composable () -> Unit) {
    // One fixed column width shared by every row (so centered mode lines up), growing with the font scale.
    val fs = androidx.compose.ui.platform.LocalConfiguration.current.fontScale
    Box(Modifier.width((128f * fs.coerceIn(1f, 1.2f)).dp), contentAlignment = Alignment.CenterEnd) { content() }
}

/** Tonal pill (12% fill, 38% border) in a real 48dp touch target. Sinks on press and ticks like every button. */
@Composable
fun StatusPill(label: String, color: Color, textColor: Color = color, enabled: Boolean = true, onClick: () -> Unit) {
    PressTile(
        onTap = onClick, background = ClearBrush, corner = 24.dp, enabled = enabled, fill = false, minTouch = 48.dp, lift = false,
        onClickLabel = label,
    ) {
        Text(
            label, fontSize = 13.sp, color = textColor, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))
                .border(1.dp, color.copy(alpha = 0.38f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(1.dp).background(Color.White.copy(alpha = 0.07f)))
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
    val queueStore = remember { QueueStore(context) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var episodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var feedUrl by remember { mutableStateOf(store.feedUrl) }
    var updateUi by remember { mutableStateOf<UpdateUi?>(null) }
    var updateJob by remember { mutableStateOf<Job?>(null) }
    var subScreen by rememberSaveable { mutableStateOf("") } // "", "settings" or "feedback"
    var retryingId by remember { mutableStateOf<String?>(null) }
    var gettingId by remember { mutableStateOf<String?>(null) }
    var pendingApk by remember { mutableStateOf<File?>(null) }
    var signOutAsk by remember { mutableStateOf(false) }
    var queued by remember { mutableStateOf(listOf<QueueStore.Queued>()) }
    var skipped by remember { mutableStateOf(listOf<QueueStore.Skipped>()) }

    /** Failed items clear themselves after about a day; cancel their notifications too. */
    fun sweepSkipped() {
        val cleared = queueStore.sweepExpiredSkipped()
        cleared.forEach { Notifier.cancelSkipped(context, it) }
        if (cleared.isNotEmpty()) skipped = queueStore.skipped()
    }

    fun host(): String = (store.server ?: "").removePrefix("https://").removePrefix("http://")

    fun copyFeedLink(): Boolean {
        val url = feedUrl ?: return false
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("feed", url))
        return true
    }

    fun load(onDone: (() -> Unit)? = null) {
        if (Demo.active) {
            // CI screenshots: canned rows instead of a server.
            if (Demo.forceLoadFail) {
                loadFailed = true
            } else {
                episodes = (if (Demo.clean) Demo.cleanEpisodes else Demo.episodes).map { EpisodeItem(it.id, it.title, it.status) }
                loadFailed = false
            }
            loading = false
            onDone?.invoke()
            return
        }
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
                loadFailed = false
                loading = false
                // Sends work again: hand any parked "Couldn't reach your server" shares back to the queue.
                val parked = queueStore.takeUnreachableSkipped()
                if (parked.isNotEmpty()) {
                    parked.forEach {
                        queueStore.enqueueShare(it.url, it.title)
                        Notifier.cancelSkipped(context, it.url)
                    }
                    queued = queueStore.shareQueue()
                    ShareWorker.enqueue(context, 0L)
                }
                onDone?.invoke()
            } catch (e: Api.SessionExpired) {
                onSessionExpired()
            } catch (e: Exception) {
                // Server down or no signal: say so, and keep the last good list on screen.
                loadFailed = true
                loading = false
                onDone?.invoke()
            }
        }
    }

    // Queued and skipped rows follow the store, but only after startup migration has
    // finished - and when the queue shrinks, a send finished, so reload the list.
    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val before = queued.size
            queued = queueStore.shareQueue()
            skipped = queueStore.skipped()
            if (queued.size < before) load()
        }
        queueStore.addListener(listener)
        onDispose { queueStore.removeListener(listener) }
    }

    /** Once per open: ask GitHub for a newer release. Silent on any failure. */
    fun checkForUpdate() {
        if (Session.updateOffered) return
        Session.updateOffered = true
        val current = BuildConfig.VERSION_NAME
        scope.launch {
            val info = withContext(Dispatchers.IO) { Updater.check(current) } ?: return@launch
            if (info.versionName == queueStore.skippedUpdate) return@launch
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
                    // Reuse the file for this exact version if one already survived the
                    // "allow installs" round-trip; never an older version's leftover.
                    val existing = File(Updater.updateDir(context), "ListenLater-${info.versionName}.apk")
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
                    // Remember the verified file; the resume observer installs it on return.
                    pendingApk = f
                    Notifier.toast(context, "Turn on Allow, then come back.")
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
        (context.applicationContext as ListenLaterApp).ready.await()
        sweepSkipped()
        queued = queueStore.shareQueue()
        skipped = queueStore.skipped()
        load()
        checkForUpdate()
    }

    // While the screen is visible and anything is pending/downloading/queued,
    // quietly re-read the list every 10 seconds. One loop, so it can't stall on
    // an unchanged list, and it stops when the app is in the background.
    var resumed by remember { mutableStateOf(true) }
    LaunchedEffect(resumed) {
        while (resumed) {
            delay(10_000)
            if (!resumed) break
            if (episodes.any { it.status == "PENDING" || it.status == "DOWNLOADING" } || queued.isNotEmpty()) {
                sweepSkipped()
                load()
            }
        }
    }

    // Back from Android's "allow installs" screen: install the file we already verified.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    sweepSkipped()
                    load()
                    if (pendingApk != null && context.packageManager.canRequestPackageInstalls()) {
                        launchInstaller(pendingApk!!)
                        pendingApk = null
                    }
                }
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val listScroll = rememberScrollState()
    // Settings keeps its place while Feedback is open on top of it; leaving Settings for Home resets it to the top.
    val settingsScroll = rememberScrollState()
    LaunchedEffect(subScreen) { if (subScreen == "") settingsScroll.scrollTo(0) }
    val hasRows = episodes.isNotEmpty() || queued.isNotEmpty() || skipped.isNotEmpty()

    if (subScreen == "settings") {
        SettingsScreen(
            scrollState = settingsScroll,
            onBack = { subScreen = "" },
            onFeedback = { subScreen = "feedback" },
            onUpdateFound = { info -> updateUi = UpdateUi.Offer(info) },
            username = store.username ?: "",
            serverUrl = store.server,
            feedAvailable = feedUrl != null,
            onCopyFeedLink = { copyFeedLink() },
            onSignOut = { signOutAsk = true },
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
            modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            AppHeader(onSettings = { subScreen = "settings" }, connected = !loadFailed)
            if (!loading && !hasRows) {
                Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (loadFailed) "Can't reach your server" else "Nothing here yet",
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextC
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (loadFailed) "Pull down to try again."
                            else "Share your first video to create the feed. It downloads on your server and lands in your podcast app by itself.",
                            fontSize = 13.5.sp, color = Muted
                        )
                    }
                }
            }

            if (hasRows) {
                Box(
                    Modifier.weight(1f)
                        .shadow(4.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(CardGradient)
                ) {
                    Box {
                    Column(Modifier.verticalScroll(listScroll).padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 64.dp)) {
                        var firstRow = true
                        queued.forEach { q ->
                            if (!firstRow) RowDivider()
                            firstRow = false
                            val id = VideoUrl.extractVideoId(q.url)
                            val now = System.currentTimeMillis()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                                    Text(
                                        q.title.ifEmpty { "Video waiting to send" },
                                        fontSize = 15.sp, color = TextC
                                    )
                                    if (q.title.isEmpty() && id != null) Text(id, fontSize = 12.sp, color = Muted)
                                }
                                StatusColumn {
                                Text(
                                    when {
                                        store.needsLogin -> "Waiting for sign-in"
                                        q.unexpectedSince != null && now - (q.unexpectedSince ?: now) >= 60 * 60 * 1000 ->
                                            "Server answered unexpectedly"
                                        q.nextRetryAt > now ->
                                            "Retrying at " + SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(q.nextRetryAt))
                                        else -> "Sending"
                                    },
                                    fontSize = 13.sp, color = Muted,
                                    // A long status wraps within its own bounded column instead
                                    // of squeezing the title into uselessness; no ellipsis -
                                    // the full reason stays readable at any font scale.
                                    textAlign = TextAlign.End,
                                )
                                }
                            }
                        }
                        skipped.forEach { sk ->
                            if (!firstRow) RowDivider()
                            firstRow = false
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                                    Text(
                                        sk.title.ifEmpty { "Video ${VideoUrl.extractVideoId(sk.url) ?: "link"}" },
                                        fontSize = 15.sp, color = TextC
                                    )
                                    if (sk.reason.isNotEmpty()) {
                                        Text(sk.reason, fontSize = 12.sp, color = Muted)
                                    }
                                }
                                StatusColumn {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Couldn't get it", fontSize = 13.sp, color = Muted)
                                    PressTile(
                                        onTap = {
                                            queueStore.removeSkipped(sk.url)
                                            queueStore.enqueueShare(sk.url, sk.title)
                                            Notifier.cancelSkipped(context, sk.url)
                                            ShareWorker.enqueue(context, 0L)
                                        },
                                        background = ClearBrush, corner = 12.dp, fill = false, minTouch = 48.dp, lift = false,
                                        onClickLabel = "Try again",
                                    ) {
                                        Text("Try again", fontSize = 13.sp, color = SageText, fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                                    }
                                }
                                }
                            }
                        }
                        episodes.forEach { ep ->
                            if (!firstRow) RowDivider()
                            firstRow = false
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(ep.title, fontSize = 15.sp, color = TextC,
                                    modifier = Modifier.weight(1f).padding(end = 10.dp))
                                StatusColumn {
                                when {
                                    ep.status == "COMPLETED" ->
                                        Text("In feed", fontSize = 13.sp, color = SageText, fontWeight = FontWeight.SemiBold)
                                    ep.status == "FAILED" ->
                                        StatusPill(
                                            if (retryingId == ep.id) "Retrying..." else "Retry", Red, RedText,
                                            enabled = retryingId == null,
                                            onClick = {
                                                val server = store.server ?: return@StatusPill
                                                val token = store.token ?: return@StatusPill
                                                val api = Api(server, token)
                                                retryingId = ep.id
                                                scope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) { api.retryEpisode(ep.id) }
                                                        retryingId = null
                                                        load()
                                                    } catch (e: Api.SessionExpired) {
                                                        retryingId = null
                                                        onSessionExpired()
                                                    } catch (e: Exception) {
                                                        retryingId = null
                                                        Notifier.toast(context, "Couldn't retry. Try again.")
                                                    }
                                                }
                                            })
                                    ep.status == "PENDING" || ep.status == "DOWNLOADING" ->
                                        Text("Downloading", fontSize = 13.sp, color = Muted)
                                    else -> // READY: cleaned up on the server, not in the feed
                                        StatusPill(if (gettingId == ep.id) "Getting..." else "Get again", Yellow,
                                            enabled = gettingId == null,
                                            onClick = {
                                                val server = store.server ?: return@StatusPill
                                                val token = store.token ?: return@StatusPill
                                                val api = Api(server, token)
                                                gettingId = ep.id
                                                scope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) { api.download(ep.id) }
                                                        gettingId = null
                                                        load()
                                                    } catch (e: Api.SessionExpired) {
                                                        gettingId = null
                                                        onSessionExpired()
                                                    } catch (e: Exception) {
                                                        gettingId = null
                                                        Notifier.toast(context, "Couldn't start it. Try again.")
                                                    }
                                                }
                                            })
                                }
                                }
                            }
                        }
                    }
                    val topVisible by remember { derivedStateOf { listScroll.value > 300 } }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = topVisible,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 },
                        exit = fadeOut(tween(150)),
                    ) {
                        PressTile(
                            onTap = { scope.launch { listScroll.animateScrollTo(0, tween(350, easing = FastOutSlowInEasing)) } },
                            background = Brush.verticalGradient(listOf(Card2, Card2)), corner = 24.dp, fill = false, lift = false,
                            border = BorderStroke(1.dp, Color(0xFF46464E)), onClickLabel = "Back to top",
                        ) {
                            Row(
                                Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(TopArrowIcon, contentDescription = null, tint = TextC, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Top", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextC)
                            }
                        }
                    }
                }
                }
            }

        }

    }
    }

    if (signOutAsk) {
        AlertDialog(
            onDismissRequest = { signOutAsk = false },
            containerColor = Card2,
            title = { Text("Sign out?", color = TextC) },
            text = { Text("Videos you share will wait until you sign in again.", color = Muted) },
            confirmButton = {
                HTextButton({ signOutAsk = false; onSignedOut() }) { Text("Sign out", color = SageText) }
            },
            dismissButton = {
                HTextButton({ signOutAsk = false }) { Text("Cancel", color = Muted) }
            },
        )
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
                        HTextButton({ queueStore.skippedUpdate = u.info.versionName; updateUi = null }) {
                            Text("Skip this version", color = SageText)
                        }
                    }
                },
                confirmButton = { HTextButton({ startUpdate(u.info) }) { Text("Update", color = SageText) } },
                dismissButton = { HTextButton({ updateUi = null }) { Text("Later", color = Muted) } },
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
                confirmButton = { HTextButton({ updateJob?.cancel() }) { Text("Cancel", color = Muted) } },
            )
            is UpdateUi.Failed -> AlertDialog(
                onDismissRequest = { updateUi = null },
                containerColor = Card2,
                title = { Text("Update not installed", color = TextC) },
                text = { Text(u.message, color = Muted) },
                confirmButton = { HTextButton({ updateUi = null }) { Text("OK", color = SageText) } },
            )
        }
    }
}
