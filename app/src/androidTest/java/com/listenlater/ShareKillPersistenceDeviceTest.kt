package com.listenlater

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Share-and-kill persistence: after every process-lived object is dropped (the
 * state a process kill leaves), the queue and the session are re-read from disk
 * intact. The adb-driven half (a real force-stop and relaunch) is in
 * scripts/share-smoke.sh; this covers the in-app re-read path on a real
 * encrypted store.
 */
@RunWith(AndroidJUnit4::class)
class ShareKillPersistenceDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        CredStore.resetForTests()
        CredStore(ctx).signOut() // deterministic starting session state on disk
        CredStore.resetForTests()
        QueueStore(ctx).clearAll()
    }

    @After
    fun tearDown() {
        QueueStore(ctx).clearAll()
        CredStore(ctx).signOut()
    }

    @Test
    fun queueAndSessionSurviveDroppingEveryProcessLivedObject() {
        CredStore(ctx).markLoggedIn("http://srv", "u", "T", "p", false)
        val q = QueueStore(ctx)
        q.enqueueShare("https://youtu.be/aaa111bbb22", "One")
        q.enqueueShare("https://youtu.be/ccc333ddd44", "Two")
        q.updateQueued("https://youtu.be/aaa111bbb22") {
            it.copy(attempts = 2, nextRetryAt = 10_000L, redirectNotified = true)
        }

        // The process-kill stand-in: the only surviving state is what is on disk.
        CredStore.resetForTests()

        val queue = QueueStore(ctx).shareQueue()
        assertEquals(2, queue.size)
        assertEquals("https://youtu.be/aaa111bbb22", queue[0].url)
        assertEquals(2, queue[0].attempts)
        assertEquals(10_000L, queue[0].nextRetryAt)
        assertTrue(queue[0].redirectNotified)
        assertEquals("https://youtu.be/ccc333ddd44", queue[1].url)
        assertEquals("the session survives too", "T", CredStore(ctx).token)
    }
}
