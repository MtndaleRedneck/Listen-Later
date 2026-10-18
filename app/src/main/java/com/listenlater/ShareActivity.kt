package com.listenlater

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible share-sheet target. Parses the shared text, queues the clean video
 * URL, kicks the worker, and closes. The worker's toast is the confirmation.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val url = VideoUrl.cleanSharedUrl(text)
        if (url == null) {
            Toast.makeText(this, "Not a single video", Toast.LENGTH_SHORT).show()
        } else {
            val store = CredStore(this)
            store.enqueueShare(url)
            ShareWorker.enqueue(this, 0L)
        }
        finish()
    }
}
