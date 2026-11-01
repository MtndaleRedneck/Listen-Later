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
 * The queue is only ever touched after startup migration has finished.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val title = intent?.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
        val url = VideoUrl.cleanSharedUrl(text)
        when {
            url == null && VideoUrl.isPlaylistLink(text) ->
                Toast.makeText(this, "That's a playlist. Share one video from it.", Toast.LENGTH_LONG).show()
            url == null ->
                Toast.makeText(this, "Couldn't find a video link", Toast.LENGTH_SHORT).show()
            else -> {
                val app = application as ListenLaterApp
                // A NoDisplay window must finish before its resume completes, so the
                // queue write and worker kick happen on the app scope after this
                // activity is gone. The startup gate is still honored first, and
                // WorkManager keeps the send even if the process dies right after.
                app.appScope.launch {
                    app.ready.await()
                    val store = CredStore(app)
                    QueueStore(app).enqueueShare(url, title)
                    when {
                        store.token == null || store.needsLogin ->
                            Notifier.toast(app, "Saved. Open Listen Later and sign in to send it.", Toast.LENGTH_LONG)
                        !isOnline(app) ->
                            Notifier.toast(app, "Saved. It'll send when you're back online.", Toast.LENGTH_LONG)
                    }
                    ShareWorker.enqueue(app, 0L)
                }
            }
        }
        finish()
    }

    companion object {
        fun isOnline(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
