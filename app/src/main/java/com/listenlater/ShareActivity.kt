package com.listenlater

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.launch

/**
 * Invisible share-sheet target. Parses the shared text, queues the clean video
 * URL (with the share's subject line as its title when the source app provides
 * one), kicks the worker, and closes. Online and signed in, the worker's toast is
 * the confirmation; offline or signed out, one toast says the share is saved.
 * The queue row is written durably (synchronous commit, off the main thread) before the
 * window closes, so a process kill between the share sheet and the worker can never drop
 * the share; the startup migration merges existing rows, so the plain store is safe to
 * touch before it finishes.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val title = intent?.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
        val url = VideoUrl.cleanSharedUrl(text)
        when {
            url == null && VideoUrl.isPlaylistLink(text) -> {
                Toast.makeText(this, "That's a playlist. Share one video from it.", Toast.LENGTH_LONG).show()
                finish()
            }
            url == null -> {
                Toast.makeText(this, "Couldn't find a video link", Toast.LENGTH_SHORT).show()
                finish()
            }
            else -> {
                val app = application as ListenLaterApp
                // Durable first, gone second: the queue row is committed to disk (the app
                // scope runs off the main thread) before the worker kick and finish(). A
                // process kill can now only lose a share before the write - where the user
                // still sees the sheet - never after it.
                app.appScope.launch {
                    try {
                        QueueStore(app).enqueueShareDurable(url, title)
                        ShareWorker.enqueue(app, 0L)
                    } catch (t: Throwable) {
                        // Never crash the invisible share target; say it failed so the
                        // share is not silently lost again.
                        Notifier.toast(app, "Couldn't save that share. Try again?", Toast.LENGTH_LONG)
                    }
                    finish()
                }
                // The explanatory toast can wait for startup migration; the write cannot.
                app.appScope.launch {
                    try {
                        app.ready.await()
                        val store = CredStore(app)
                        when {
                            store.token == null || store.needsLogin ->
                                Notifier.toast(app, "Saved. Open Listen Later and sign in to send it.", Toast.LENGTH_LONG)
                            !isOnline(app) ->
                                Notifier.toast(app, "Saved. It'll send when you're back online.", Toast.LENGTH_LONG)
                        }
                    } catch (t: Throwable) { /* the share is already safe; the toast is best effort */ }
                }
            }
        }
    }

    companion object {
        fun isOnline(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
