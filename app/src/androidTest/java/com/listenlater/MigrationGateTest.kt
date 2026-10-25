package com.listenlater

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/**
 * The startup migration moves the queue out of the old encrypted store behind a
 * gate ([ListenLaterApp.ready]). This test recreates that moment: a slow migration
 * runs while a reader waits on the gate, and the reader must see the imported
 * queue exactly once - never early, never duplicated.
 */
@RunWith(AndroidJUnit4::class)
class MigrationGateTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun encryptedPrefs() = EncryptedSharedPreferences.create(
        ctx,
        "creds",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    @Before
    fun setUp() {
        QueueStore(ctx).clearAll()
        QueueStore(ctx).migrated = false
        // The old install's queue, in the old bare-list form, still in the encrypted store.
        encryptedPrefs().edit()
            .putString("queue", "[\"https://youtu.be/aaa111bbb22\",\"https://www.youtube.com/watch?v=ccc333ddd44\"]")
            .commit()
        CredStore.migrationSlowdownMs = 1_200L
    }

    @After
    fun tearDown() {
        CredStore.migrationSlowdownMs = 0L
        QueueStore(ctx).clearAll()
        QueueStore(ctx).migrated = true
        encryptedPrefs().edit().remove("queue").commit()
    }

    @Test
    fun gatedReaderSeesImportedQueueExactlyOnce() {
        val gate = CompletableDeferred<Unit>()
        var queueDuringMigration: List<QueueStore.Queued>? = null

        val migrator = thread {
            try {
                CredStore.migrateToPlain(ctx)
                CredStore.migrateToPlain(ctx) // a racing second pass must be a no-op
            } finally {
                gate.complete(Unit)
            }
        }

        // Mid-migration: the move is still sleeping, so nothing may be visible yet.
        Thread.sleep(300)
        queueDuringMigration = QueueStore(ctx).shareQueue()

        // The reader's view: blocked on the gate, then the queue, exactly once.
        val queueAfterGate = runBlocking {
            gate.await()
            QueueStore(ctx).shareQueue()
        }
        migrator.join(10_000)

        assertTrue("queue must stay hidden behind the gate", queueDuringMigration!!.isEmpty())
        assertEquals(2, queueAfterGate.size)
        assertEquals("https://youtu.be/aaa111bbb22", queueAfterGate[0].url)
        assertEquals("https://www.youtube.com/watch?v=ccc333ddd44", queueAfterGate[1].url)
        assertTrue("migrated flag must be set once", QueueStore(ctx).migrated)
        assertEquals(
            "legacy value must be removed from the encrypted store",
            null, encryptedPrefs().getString("queue", null)
        )
        // The double pass must not have duplicated anything.
        assertEquals(2, QueueStore(ctx).shareQueue().size)
    }
}
