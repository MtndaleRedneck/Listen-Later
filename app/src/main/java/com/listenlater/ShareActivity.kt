package com.listenlater

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible share-sheet target. Parses the shared text, queues the clean video
 * URL, kicks the worker, and closes. Online and signed in, the worker's toast is
 * the confirmation; offline or signed out, one toast says the share is saved.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val url = VideoUrl.cleanSharedUrl(text)
        when {
            url == null && VideoUrl.isPlaylistLink(text) ->
                Toast.makeText(this, "That's a playlist. Share one video from it.", Toast.LENGTH_LONG).show()
            url == null ->
                Toast.makeText(this, "Couldn't find a video link", Toast.LENGTH_SHORT).show()
            else -> {
                val store = CredStore(this)
                QueueStore(this).enqueueShare(url)
                when {
                    store.token == null || store.needsLogin ->
                        Toast.makeText(this, "Saved. Open Listen Later and sign in to send it.", Toast.LENGTH_LONG).show()
                    !isOnline(this) ->
                        Toast.makeText(this, "Saved. It'll send when you're back online.", Toast.LENGTH_LONG).show()
                }
                ShareWorker.enqueue(this, 0L)
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
