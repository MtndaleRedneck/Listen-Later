package com.listenlater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The notification's Try again: the link goes back in the share queue (with its
 * title, fresh retry state) and sends now. If it fails again with a reason, the
 * worker parks it in "Couldn't get it" again.
 */
class SkippedRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(Notifier.EXTRA_URL) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                (context.applicationContext as ListenLaterApp).ready.await()
                val queue = QueueStore(context)
                val title = queue.skipped().firstOrNull { it.url == url }?.title ?: ""
                queue.removeSkipped(url)
                queue.enqueueShare(url, title)
                Notifier.cancelSkipped(context, url)
                ShareWorker.enqueue(context, 0L)
            } finally {
                pending.finish()
            }
        }
    }
}
