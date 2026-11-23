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
 * The queue row is committed to disk synchronously before the window closes, so a
 * process kill between the share sheet and the worker can never drop the share; the
 * startup migration merges existing rows, so the plain store is safe to touch first.
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
                // Durable before gone: one small synchronous commit, here, before finish().
                // A NoDisplay window must not linger for async I/O (it has to finish before
                // its resume completes), and finishing before a durable write would leave a
                // process-kill window. The commit is one small SharedPreferences write (a few
                // queued entries of JSON), and its result is honored: a failed commit says so
                // instead of pretending the share was saved.
                val saved = try {
                    QueueStore(app).enqueueShareDurable(url, title)
                } catch (t: Throwable) {
                    false
                }
                if (!saved) {
                    Toast.makeText(this, "Couldn't save that share. Try again?", Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
                // On disk now: the worker kick and closing are safe, in either order.
                ShareWorker.enqueue(app, 0L)
                finish()
                // The explanatory toast can wait for startup migration; the write could not.
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
