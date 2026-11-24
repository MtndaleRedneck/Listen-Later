package com.listenlater

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** One worker run at a time: REPLACE can overlap a canceled run's blocking network call. */
object QueueLock {
    val mutex = Mutex()
}

/**
 * Sends queued shares one at a time, fairly: every link carries its own retry count,
 * and after a failed try it moves to the back so the others get a turn. Network
 * failures retry quietly on the Backoff rhythm with no limit - a server down for
 * hours no longer parks anything, and a redirect the app won't follow retries the
 * same way (with one notification naming where the server tried to send it). A
 * server-given reason parks the video under
 * "Couldn't get it" (which clears itself after about a day) with one quick retry
 * first; a missing or expired sign-in raises one notification. What happens for
 * each outcome lives in [ShareLogic] so every path is unit-tested. Login itself
 * only ever happens on tap in the app, never here.
 */
class ShareWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = QueueLock.mutex.withLock { run() }

    private suspend fun run(): Result {
        // Never touch the queue before the one-time migration has finished.
        (applicationContext as ListenLaterApp).ready.await()

        val store = CredStore(applicationContext)
        val queue = QueueStore(applicationContext)
        val q = queue.shareQueue()
        if (q.isEmpty()) return Result.success()

        // Run the first item whose retry time has come; if every item is still backing
        // off, wake when the earliest one is due instead of retrying something early or
        // holding fresh shares behind another link's backoff.
        val now = System.currentTimeMillis()
        val dueIndex = ShareLogic.firstDueIndex(q.map { it.nextRetryAt }, now)
        if (dueIndex < 0) {
            enqueue(applicationContext, ShareLogic.nextWakeDelayMs(q.map { it.nextRetryAt }, now))
            return Result.success()
        }

        val token = store.token
        val server = store.server
        if (token.isNullOrEmpty() || server.isNullOrEmpty()) {
            // A deliberate sign-out holds the queue quietly; only an expired or missing
            // session asks him to sign in again (once).
            if (!store.signedOut) {
                store.needsLogin = true
                Notifier.notifySignInOnce(applicationContext, store)
            }
            return Result.success()
        }

        val api = Api(server, token)
        val item = q[dueIndex]
        val url = item.url
        var title = item.title

        val outcome = try {
            val feed = api.previewFeed(url)
            if (title.isEmpty()) {
                title = feed.optString("title", "")
                if (title.isNotEmpty()) queue.updateQueued(url) { it.copy(title = title) }
            }
            val result = api.addFeed(feed)
            if (result.duplicate) duplicateOutcome(api, url) else ShareLogic.Outcome.SENT
        } catch (e: Api.SessionExpired) {
            ShareLogic.Outcome.SESSION_EXPIRED
        } catch (e: Api.RedirectRefused) {
            // Never parked: the share keeps its hourly retry on the saved address (a swept
            // parked row would silently lose it). One accurate notification per video,
            // tracked on the queue entry itself so an earlier hiccup can't suppress it.
            if (!item.redirectNotified) {
                Notifier.redirectNotice(applicationContext, url, title, e.targetOrigin)
                queue.updateQueued(url) { it.copy(redirectNotified = true) }
            }
            ShareLogic.Outcome.REDIRECT_REFUSED
        } catch (e: Api.UnexpectedResponse) {
            ShareLogic.Outcome.UNEXPECTED_RESPONSE
        } catch (e: Api.ApiError) {
            // The refusal answer may have landed after a cancel while the call was
            // blocked: the cancel still wins, before any queue mutation (the shared
            // barrier below only guards the paths that reach it).
            currentCoroutineContext().ensureActive()
            return parkForRefusal(queue, url, title, item.attempts, e.message)
        } catch (e: Exception) {
            ShareLogic.Outcome.NETWORK_FAILURE
        }

        // A REPLACE-enqueued newer run already owns the queue from here on: if this run was
        // cancelled while blocked on the network, stop before mutating or re-enqueuing.
        currentCoroutineContext().ensureActive()
        val d = ShareLogic.decide(outcome, item.attempts, title)
        d.toast?.let { Notifier.toast(applicationContext, it) }
        when (d.action) {
            ShareLogic.Action.DONE -> {
                queue.removeFromQueue(url)
                queue.removeSkipped(url)
                Notifier.cancelSkipped(applicationContext, url)
                Notifier.clearRedirectNotice(applicationContext, url)
                requeueUnreachable(queue)
                if (queue.shareQueue().isNotEmpty()) enqueue(applicationContext, 0L)
            }
            ShareLogic.Action.PARK -> {
                queue.removeFromQueue(url)
                val reason = d.parkReason ?: "The server said it can't get this video."
                queue.addSkipped(url, title, reason)
                Notifier.clearRedirectNotice(applicationContext, url)
                Notifier.skipped(applicationContext, url, title, reason)
                if (queue.shareQueue().isNotEmpty()) enqueue(applicationContext, 0L)
            }
            ShareLogic.Action.SIGN_IN -> {
                store.needsLogin = true
                Notifier.notifySignInOnce(applicationContext, store)
            }
            ShareLogic.Action.RETRY -> {
                queue.updateQueued(url) {
                    it.copy(
                        attempts = it.attempts + 1,
                        nextRetryAt = System.currentTimeMillis() + d.retryDelayMs,
                        unexpectedSince = if (d.markUnexpected) (it.unexpectedSince ?: System.currentTimeMillis()) else null
                    )
                }
                if (d.moveToBack) queue.moveToBack(url)
                enqueue(applicationContext, ShareLogic.nextWakeDelayMs(
                    queue.shareQueue().map { it.nextRetryAt }, System.currentTimeMillis()))
            }
        }
        return Result.success()
    }

    /** A refusal gets one quick retry in case YouTube was briefly limited, then it's parked. */
    private suspend fun parkForRefusal(
        queue: QueueStore, url: String, title: String, attempts: Int, serverReason: String?
    ): Result {
        currentCoroutineContext().ensureActive()
        val d = ShareLogic.decide(ShareLogic.Outcome.REFUSAL, attempts, title)
        return if (d.action == ShareLogic.Action.RETRY) {
            queue.updateQueued(url) {
                it.copy(attempts = it.attempts + 1, nextRetryAt = System.currentTimeMillis() + d.retryDelayMs)
            }
            queue.moveToBack(url)
            enqueue(applicationContext, ShareLogic.nextWakeDelayMs(
                queue.shareQueue().map { it.nextRetryAt }, System.currentTimeMillis()))
            Result.success()
        } else {
            queue.removeFromQueue(url)
            val reason = serverReason ?: "The server said it can't get this video."
            queue.addSkipped(url, title, reason)
            Notifier.clearRedirectNotice(applicationContext, url) // no stale redirect note next to the skipped one
            Notifier.skipped(applicationContext, url, title, reason)
            if (queue.shareQueue().isNotEmpty()) enqueue(applicationContext, 0L)
            Result.success()
        }
    }

    /**
     * The server says it already has this video. Find it by its exact video ID -
     * first the direct status query, then bounded paging - and kick whatever it
     * needs. Never a false "Already in your feed" when it can't be found.
     */
    private fun duplicateOutcome(api: Api, url: String): ShareLogic.Outcome {
        val id = VideoUrl.extractVideoId(url) ?: return ShareLogic.Outcome.DUPLICATE_UNRESOLVED
        val status = try {
            findEpisodeStatus(api, id)
        } catch (e: Api.SessionExpired) {
            throw e
        } catch (e: Exception) {
            // A network glitch mid-lookup is weather, not "can't find it": keep retrying.
            // Parking here would park a share the server really has - and the sweep would lose it.
            return ShareLogic.Outcome.UNEXPECTED_RESPONSE
        }
        return when (status) {
            "READY" -> {
                api.download(id) // cleaned up on the server: start it again
                ShareLogic.Outcome.DUPLICATE_READY
            }
            "FAILED" -> {
                api.retryEpisode(id) // its download failed: one more try on the server
                ShareLogic.Outcome.DUPLICATE_FAILED
            }
            "PENDING", "DOWNLOADING" -> ShareLogic.Outcome.DUPLICATE_WAITING
            null -> ShareLogic.Outcome.DUPLICATE_UNRESOLVED
            else -> ShareLogic.Outcome.DUPLICATE_UNKNOWN
        }
    }

    /** Null means: searched everywhere we're willing to look and didn't find it. */
    private fun findEpisodeStatus(api: Api, id: String): String? {
        api.episodeStatus(listOf(id))
            .firstOrNull { it.optString("id") == id }
            ?.let { return it.optString("downloadStatus", "") }
        // Bounded fallback: page the newest-first list with a hard cap so this never grinds.
        val maxPages = 20 // 20 pages of 50 = 1000 episodes
        for (page in 1..maxPages) {
            val list = api.episodes(FEED_ID, page, 50)
            if (list.isEmpty()) return null
            list.firstOrNull { it.optString("id") == id }
                ?.let { return it.optString("downloadStatus", "") }
            if (list.size < 50) return null
        }
        return null
    }

    /** Legacy "Couldn't reach your server" items go back in the queue once things work again. */
    private fun requeueUnreachable(queue: QueueStore) {
        val parked = queue.takeUnreachableSkipped()
        if (parked.isEmpty()) return
        parked.forEach {
            queue.enqueueShare(it.url, it.title)
            Notifier.cancelSkipped(applicationContext, it.url)
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
