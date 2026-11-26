package com.listenlater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueRemovalTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val url = "https://youtu.be/one"

    @Before
    fun setUp() { QueueStore(ctx).clearAll() }

    @Test
    fun removesAWaitingShareAndOnlyThatOne() = runBlocking {
        val q = QueueStore(ctx)
        q.enqueueShare(url, "One"); q.enqueueShare("https://youtu.be/two", "Two")
        assertTrue(QueueLock.removeLocal(q, url))
        assertEquals(listOf("https://youtu.be/two"), q.shareQueue().map { it.url })
    }

    @Test
    fun removesAParkedShare() = runBlocking {
        val q = QueueStore(ctx)
        q.addSkipped(url, "One", "refused")
        assertTrue(QueueLock.removeLocal(q, url))
        assertTrue(q.skipped().isEmpty())
    }

    @Test
    fun reportsFalseWhenTheSendAlreadyFinished() = runBlocking {
        val q = QueueStore(ctx)
        assertFalse(QueueLock.removeLocal(q, url))
    }

    @Test
    fun waitsForARunningSendAndThenSeesItsResult() = runBlocking {
        val q = QueueStore(ctx)
        q.enqueueShare(url, "One")
        val delete = async {
            QueueLock.mutex.withLock {
                // A send is in flight and finishes (removes the item) while holding the lock.
                delay(150)
                q.removeFromQueue(url)
            }
            QueueLock.removeLocal(q, url)
        }
        val early = async { delay(20); QueueLock.removeLocal(q, url) }
        assertFalse("send finished first, so nothing local is left to remove", early.await())
        assertFalse(delete.await())
        assertTrue(q.shareQueue().isEmpty())
    }
}
