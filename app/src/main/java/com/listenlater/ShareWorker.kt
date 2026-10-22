package com.listenlater

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** One worker run at a time: REPLACE can overlap a canceled run's blocking network call. */
object QueueLock {
    val mutex = Mutex()
}

/**
 * Sends queued shares one at a time. Network failures retry quietly on the Backoff
 * rhythm; a server-given reason skips the video at most once-retried, names it in a
 * notification, and parks it in the "Couldn't get it" list for Try again; a 401 keeps
 * the queue and raises one sign-in notification. Login itself only ever happens on
 * tap in the app, never here.
 */
class ShareWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = QueueLock.mutex.withLock { run() }

    private suspend fun run(): Result {
        val store = CredStore(applicationContext)
        val queue = QueueStore(applicationContext)
        val q = queue.shareQueue()
        if (q.isEmpty()) return Result.success()

        val token = store.token
        val server = store.server
        if (token.isNullOrEmpty() || server.isNullOrEmpty()) {
            store.needsLogin = true
            Notifier.notifySignInOnce(applicationContext, store)
            return Result.success()
        }

        val api = Api(server, token)
        val url = q.first()
        var title = ""
        return try {
            val feed = api.previewFeed(url)
            title = feed.optString("title", "")
            val result = api.addFeed(feed)
            if (result.duplicate) {
                // The server kept the record after cleanup or a failed download, so it
                // skipped the download. Look the episode up by its video ID and kick it.
                // UNVERIFIED: the download/retry calls accept READY/FAILED only, per the
                // teardown's read of the server; any surprise falls back to the old toast.
                var handled = false
                val id = VideoUrl.extractVideoId(url)
                if (id != null) {
                    try {
                        val status = api.episodes(FEED_ID)
                            .firstOrNull { it.optString("id") == id }
                            ?.optString("downloadStatus")
                        when (status) {
                            "READY" -> {
                                api.download(id)
                                Notifier.toast(applicationContext, "Getting it again")
                                handled = true
                            }
                            "FAILED" -> {
                                api.retryEpisode(id)
                                Notifier.toast(applicationContext, "Trying that one again")
                                handled = true
                            }
                            else -> {}
                        }
                    } catch (e: Exception) {
                        // The lookup or kick failed; fall back to the plain duplicate word.
                    }
                }
                if (!handled) Notifier.toast(applicationContext, "Already in your feed")
            } else {
                Notifier.toast(applicationContext, "Sent to Listen Later")
            }
            queue.removeFromQueue(url)
            queue.removeSkipped(url)
            Notifier.cancelSkipped(applicationContext, url)
            queue.attempts = 0
            if (queue.shareQueue().isNotEmpty()) enqueue(applicationContext, 0L)
            Result.success()
        } catch (e: Api.SessionExpired) {
            store.needsLogin = true
            Notifier.notifySignInOnce(applicationContext, store)
            Result.success()
        } catch (e: Api.ApiError) {
            // The server answered with a reason (private, deleted, members-only...):
            // retrying for hours will not help. One quick retry in case YouTube was
            // briefly limited, then skip it, name it, and park it for Try again.
            val failures = queue.attempts + 1
            if (failures < 2) {
                queue.attempts = failures
                enqueue(applicationContext, 60_000L)
            } else {
                queue.removeFromQueue(url)
                queue.attempts = 0
                val reason = e.message ?: "The server said it can't get this video."
                queue.addSkipped(url, title, reason)
                Notifier.skipped(applicationContext, url, title, reason)
                if (queue.shareQueue().isNotEmpty()) enqueue(applicationContext, 0L)
            }
            Result.success()
        } catch (e: Exception) {
            // No signal, timeout, server down: today's backoff.
            val failures = queue.attempts + 1
            if (Backoff.shouldGiveUp(failures)) {
                // A permanently failing share must not block the queue behind it, and it
                // must not be lost either: park it under "Couldn't get it" with Try again,
                // like a refusal. (A background toast would never be seen.)
                queue.removeFromQueue(url)
                queue.attempts = 0
                val reason = "Couldn't reach your server"
                queue.addSkipped(url, title, reason)
                Notifier.skipped(applicationContext, url, title, reason)
                if (queue.shareQueue().isNotEmpty()) enqueue(applicationContext, 0L)
            } else {
                queue.attempts = failures
                enqueue(applicationContext, Backoff.nextDelayMs(failures))
            }
            Result.success()
        }
    }

    companion object {
        fun enqueue(context: Context, delayMs: Long) {
            val req = OneTimeWorkRequestBuilder<ShareWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("share-send", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
