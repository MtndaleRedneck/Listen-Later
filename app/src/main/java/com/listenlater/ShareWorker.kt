package com.listenlater

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Sends queued shares one at a time. Failures retry quietly on the Backoff
 * rhythm; a 401 keeps the queue and raises one sign-in notification. Login
 * itself only ever happens on tap in the app, never here.
 */
class ShareWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val store = CredStore(applicationContext)
        val queue = store.shareQueue()
        if (queue.isEmpty()) return Result.success()

        val token = store.token
        val server = store.server
        if (token.isNullOrEmpty() || server.isNullOrEmpty()) {
            store.needsLogin = true
            Notifier.notifySignInOnce(applicationContext, store)
            return Result.success()
        }

        val api = Api(server, token)
        val url = queue.first()
        return try {
            val feed = api.previewFeed(url)
            val result = api.addFeed(feed)
            store.removeFromQueue(url)
            store.attempts = 0
            Notifier.toast(
                applicationContext,
                if (result.duplicate) "Already in your feed" else "Sent to Listen Later"
            )
            if (store.shareQueue().isNotEmpty()) enqueue(applicationContext, 0L)
            Result.success()
        } catch (e: Api.SessionExpired) {
            store.needsLogin = true
            Notifier.notifySignInOnce(applicationContext, store)
            Result.success()
        } catch (e: Exception) {
            val failures = store.attempts + 1
            store.attempts = failures
            enqueue(applicationContext, Backoff.nextDelayMs(failures))
            Result.success()
        }
    }

    companion object {
        fun enqueue(context: Context, delayMs: Long) {
            val req = OneTimeWorkRequestBuilder<ShareWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("share-send", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
