package com.listenlater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/**
 * Worker-level runs against a real local server: retry-then-park, the signed-out hold,
 * and the cancellation barrier after a blocked network call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareWorkerTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        QueueStore(ctx).clearAll()
    }

    private fun worker() = TestListenableWorkerBuilder<ShareWorker>(ctx).build()

    private fun signIn(server: MockWebServer) =
        CredStore(ctx).markLoggedIn(server.url("/").toString().trimEnd('/'), "u", "T", "p", false)

    @Test
    fun refusalRetriesThenParksWithoutLosingTheVideo() = runBlocking {
        val server = MockWebServer()
        server.start()
        signIn(server)
        QueueStore(ctx).enqueueShare("https://youtu.be/one", "One")
        // Every call answers a plain refusal (an ApiError, not a session or network failure).
        repeat(6) {
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("{\"code\":50010,\"msg\":\"Can't get this video\"}"))
        }

        val r1 = worker().doWork()
        assertEquals(ListenableWorker.Result.success(), r1)
        var q = QueueStore(ctx).shareQueue()
        assertEquals("first refusal backs off, not parks", 1, q.size)
        assertEquals(1, q[0].attempts)
        assertTrue(q[0].nextRetryAt > 0L)

        // The wake fires when the retry comes due; simulate that moment.
        QueueStore(ctx).updateQueued("https://youtu.be/one") { it.copy(nextRetryAt = 0L) }
        val r2 = worker().doWork()
        assertEquals(ListenableWorker.Result.success(), r2)
        q = QueueStore(ctx).shareQueue()
        assertTrue("second refusal parks it where he can retry by hand", q.isEmpty())
        assertEquals(listOf("https://youtu.be/one"), QueueStore(ctx).skipped().map { it.url })
        server.shutdown()
    }

    @Test
    fun deliberateSignOutHoldsTheQueueQuietly() = runBlocking {
        val server = MockWebServer()
        server.start()
        val store = CredStore(ctx)
        store.markLoggedIn(server.url("/").toString().trimEnd('/'), "u", "T", "p", false)
        QueueStore(ctx).enqueueShare("https://youtu.be/one", "One")
        store.signOut()
        val r = worker().doWork()
        assertEquals(ListenableWorker.Result.success(), r)
        assertEquals(1, QueueStore(ctx).shareQueue().size) // the share waits
        assertFalse("no sign-in nag after a deliberate sign-out", store.needsLogin)
        assertEquals(0, server.requestCount) // and nothing was sent
        server.shutdown()
    }

    @Test
    fun cancelledWhileBlockedMutatesNothing() = runBlocking {
        val server = MockWebServer()
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown() // the network call really began before the cancel
                gate.await() // hold the network call until the test lets it through
                return MockResponse().setResponseCode(200)
                    .setBody("{\"code\":50010,\"msg\":\"Can't get this video\"}")
            }
        }
        server.start()
        signIn(server)
        QueueStore(ctx).enqueueShare("https://youtu.be/one", "One")

        val w = worker()
        // Default, like CoroutineWorker in production: the blocking network call must not
        // freeze the test's own event loop.
        val job = async(kotlinx.coroutines.Dispatchers.Default) { w.doWork() }
        assertTrue("the request reached the server before the cancel", started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel()
        gate.countDown()
        var cancelled = false
        try { job.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true }
        assertTrue("a cancelled run surfaces CancellationException - any other outcome fails here", cancelled)
        assertEquals("no second request slipped in while the cancel landed", 1, server.requestCount)
        val q = QueueStore(ctx).shareQueue()
        assertEquals("queue untouched by the cancelled run", 1, q.size)
        assertEquals(0, q[0].attempts)
        assertEquals(0L, q[0].nextRetryAt)
        assertTrue(QueueStore(ctx).skipped().isEmpty())
        server.shutdown()
    }
}
