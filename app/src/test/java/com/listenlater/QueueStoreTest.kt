package com.listenlater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Queue store + due-selection integration: what the worker reads is what was written,
 * across instances, including retry times and the one-time redirect-notice flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueStoreTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() { QueueStore(ctx).clearAll() }

    @Test
    fun fieldsSurviveAReloadAndDriveDueSelection() {
        val a = QueueStore(ctx)
        a.enqueueShare("https://youtu.be/one", "One")
        a.enqueueShare("https://youtu.be/two", "Two")
        a.enqueueShare("https://youtu.be/three", "Three")
        a.updateQueued("https://youtu.be/one") { it.copy(attempts = 2, nextRetryAt = 10_000L) }
        a.updateQueued("https://youtu.be/two") { it.copy(redirectNotified = true) }

        // A fresh instance reads the persisted queue: nothing lives only in memory.
        val q = QueueStore(ctx).shareQueue()
        assertEquals(3, q.size)
        assertEquals(listOf("https://youtu.be/one", "https://youtu.be/two", "https://youtu.be/three"), q.map { it.url })
        assertEquals(2, q[0].attempts)
        assertEquals(10_000L, q[0].nextRetryAt)
        assertTrue(q[1].redirectNotified)

        // Due selection over the reloaded queue: backed-off first link does not block the rest.
        val idx = ShareLogic.firstDueIndex(q.map { it.nextRetryAt }, 5_000L)
        assertEquals(1, idx)
        assertEquals("https://youtu.be/two", q[idx].url)
        assertEquals(5_000L, ShareLogic.nextWakeDelayMs(q.map { it.nextRetryAt }, 5_000L))
    }

    @Test
    fun moveToBackKeepsTheOtherEntriesInOrder() {
        val a = QueueStore(ctx)
        a.enqueueShare("https://youtu.be/one")
        a.enqueueShare("https://youtu.be/two")
        a.enqueueShare("https://youtu.be/three")
        a.moveToBack("https://youtu.be/one")
        assertEquals(
            listOf("https://youtu.be/two", "https://youtu.be/three", "https://youtu.be/one"),
            QueueStore(ctx).shareQueue().map { it.url })
    }
}
