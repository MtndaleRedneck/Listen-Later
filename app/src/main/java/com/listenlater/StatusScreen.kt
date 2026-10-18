package com.listenlater

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val FEED_ID = "individual-videos-youtube"

private data class EpisodeItem(val id: String, val title: String, val status: String)

@Composable
fun StatusScreen(store: CredStore, onSignedOut: () -> Unit, onSessionExpired: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var episodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var feedUrl by remember { mutableStateOf(store.feedUrl) }

    fun host(): String = (store.server ?: "").removePrefix("https://").removePrefix("http://")

    fun copyFeedLink() {
        val url = feedUrl ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("feed", url))
        Notifier.toast(context, "Feed link copied")
    }

    fun load() {
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
            } catch (e: Api.SessionExpired) {
                onSessionExpired()
            } catch (e: Exception) {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

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
}
