package com.listenlater

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            url == null && VideoUrl.isPlaylistLink(text) -> {
                Toast.makeText(this, "That's a playlist. Share one video from it.", Toast.LENGTH_LONG).show()
                finish()
            }
            url == null -> {
                Toast.makeText(this, "Couldn't find a video link", Toast.LENGTH_SHORT).show()
                finish()
            }
            else -> {
                val act = this
                CoroutineScope(Dispatchers.Main).launch {
                    (application as ListenLaterApp).ready.await()
                    val store = CredStore(act)
                    QueueStore(act).enqueueShare(url, title)
                    when {
                        store.token == null || store.needsLogin ->
                            Toast.makeText(act, "Saved. Open Listen Later and sign in to send it.", Toast.LENGTH_LONG).show()
                        !isOnline(act) ->
                            Toast.makeText(act, "Saved. It'll send when you're back online.", Toast.LENGTH_LONG).show()
                    }
                    ShareWorker.enqueue(act, 0L)
                    finish()
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
