package com.listenlater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The notification's Try again: the link goes back in the share queue and sends now.
 * If it fails again with a reason, the worker parks it in "Couldn't get it" again.
 */
class SkippedRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(Notifier.EXTRA_URL) ?: return
        val queue = QueueStore(context)
        queue.removeSkipped(url)
        queue.enqueueShare(url)
        queue.attempts = 0
        Notifier.cancelSkipped(context, url)
        ShareWorker.enqueue(context, 0L)
    }
}
