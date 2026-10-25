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
        // Let the app's own startup migration finish first: on a fresh install it
        // finds nothing and flips the flag, and resetting the flag before it settles
        // would race it. The scenario below is the upgrade case: flag off, legacy
        // data present, one migration running while a reader waits on the gate.
        runBlocking { (ctx.applicationContext as ListenLaterApp).ready.await() }
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
        ctx.getSharedPreferences("queue_store", 0).edit().remove("migration_backup_queue").commit()
    }

    @Test
    fun gatedReaderSeesImportedQueueExactlyOnce() {
        val gate = CompletableDeferred<Unit>()
        var queueDuringMigration: List<QueueStore.Queued>? = null
        var migratorError: Throwable? = null

        val migrator = thread {
            try {
                CredStore.migrateToPlain(ctx)
                CredStore.migrateToPlain(ctx) // a racing second pass must be a no-op
            } catch (t: Throwable) {
                migratorError = t
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

        org.junit.Assert.assertNull("migration itself failed", migratorError)
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

    /**
     * The interrupted-cleanup case: the import finished and the flag was set, but
     * the legacy value was never removed (the old ordering could leave exactly
     * this state). Any later pass must not re-import the stale legacy queue over
     * shares queued in between.
     */
    @Test
    fun migrationRerunAfterInterruptedCleanupKeepsNewShares() {
        CredStore.migrationSlowdownMs = 0L
        // setUp already staged two legacy links with the flag off: import them.
        CredStore.migrateToPlain(ctx)
        assertEquals(2, QueueStore(ctx).shareQueue().size)
        assertTrue(QueueStore(ctx).migrated)

        // A share lands after migration.
        QueueStore(ctx).enqueueShare("https://youtu.be/new222new22", "New share")

        // The legacy cleanup never happened: the stale value is still there.
        encryptedPrefs().edit()
            .putString("queue", "[\"https://youtu.be/aaa111bbb22\",\"https://www.youtube.com/watch?v=ccc333ddd44\"]")
            .commit()

        // A later pass must leave the queue - including the new share - untouched.
        CredStore.migrateToPlain(ctx)
        val queue = QueueStore(ctx).shareQueue()
        assertEquals(3, queue.size)
        assertTrue(queue.any { it.url == "https://youtu.be/new222new22" })
        assertTrue(queue.any { it.url == "https://youtu.be/aaa111bbb22" })
        assertTrue(queue.any { it.url == "https://www.youtube.com/watch?v=ccc333ddd44" })
    }

    /**
     * The import and the migrated marker are one commit (QueueStore.importQueueAndMarkMigrated),
     * so a failure can only leave "nothing written" (retry next launch) or "queue and
     * marker together" (done, cleanup best-effort) - never the loss window of imported
     * data without the marker. These two tests pin both sides of that boundary.
     */
    @Test
    fun corruptLegacyValueIsBackedUpAndMarkedDone() {
        CredStore.migrationSlowdownMs = 0L
        encryptedPrefs().edit().putString("queue", "{not json").commit()
        QueueStore(ctx).migrated = false

        CredStore.migrateToPlain(ctx)

        // Unreadable legacy data is not silently dropped: the raw value is backed
        // up, the marker is set (no retry loop), and the queue migrates empty.
        assertTrue(QueueStore(ctx).migrated)
        assertTrue(QueueStore(ctx).shareQueue().isEmpty())
        assertEquals("{not json",
            ctx.getSharedPreferences("queue_store", 0).getString("migration_backup_queue", null))
    }

    @Test
    fun absentLegacyValueMigratesEmptyAndMarksDone() {
        CredStore.migrationSlowdownMs = 0L
        encryptedPrefs().edit().remove("queue").commit()
        QueueStore(ctx).migrated = false

        CredStore.migrateToPlain(ctx)

        assertTrue(QueueStore(ctx).migrated)
        assertTrue(QueueStore(ctx).shareQueue().isEmpty())
    }

    /**
     * True pre-write failure: the legacy READ itself throws (the stored ciphertext
     * for "queue" is corrupted at the raw level, so decryption fails). Nothing may
     * be written: the marker stays unset, an already-queued share survives, and
     * once the value is repaired the next pass imports cleanly.
     */
    @Test
    fun legacyReadFailureWritesNothingAndRetries() {
        CredStore.migrationSlowdownMs = 0L
        QueueStore(ctx).clearAll()
        QueueStore(ctx).migrated = false
        QueueStore(ctx).enqueueShare("https://youtu.be/sur555vive5", "Already queued")

        // Find the raw storage key that backs the encrypted "queue" entry, then
        // corrupt its ciphertext so the read genuinely fails.
        val raw = ctx.getSharedPreferences("creds", 0)
        val before = raw.all.keys
        encryptedPrefs().edit()
            .putString("queue", "[\"https://youtu.be/leg666acy66\"]")
            .commit()
        val added = raw.all.keys - before
        assertTrue("expected raw entries for the encrypted value", added.isNotEmpty())
        added.forEach { raw.edit().putString(it, "AAAA corrupted").commit() }

        var threw = false
        try {
            CredStore.migrateToPlain(ctx)
        } catch (t: Throwable) {
            threw = true // the app catches this in its startup guard; here we verify
        }
        assertTrue("the corrupt read must fail, not migrate empty", threw)
        assertTrue("no marker may be written on a failed read", !QueueStore(ctx).migrated)
        assertEquals(
            "the already-queued share must be untouched",
            listOf("https://youtu.be/sur555vive5"),
            QueueStore(ctx).shareQueue().map { it.url }
        )

        // Repaired value: the retry imports and merges, keeping the existing share.
        encryptedPrefs().edit()
            .putString("queue", "[\"https://youtu.be/leg666acy66\"]")
            .commit()
        CredStore.migrateToPlain(ctx)
        val queue = QueueStore(ctx).shareQueue()
        assertTrue(QueueStore(ctx).migrated)
        assertEquals(2, queue.size)
        assertTrue(queue.any { it.url == "https://youtu.be/sur555vive5" })
        assertTrue(queue.any { it.url == "https://youtu.be/leg666acy66" })
    }

    /**
     * The state an unconfirmed durable write can leave: the plain queue already
     * holds a share, the marker never persisted, and the legacy data is still
     * there. The retry must merge - never overwrite - so the share survives.
     */
    @Test
    fun retryAfterUnconfirmedCommitMergesInsteadOfOverwriting() {
        CredStore.migrationSlowdownMs = 0L
        QueueStore(ctx).clearAll()
        QueueStore(ctx).migrated = false
        QueueStore(ctx).enqueueShare("https://youtu.be/kee333pee33", "Queued between attempts")

        CredStore.migrateToPlain(ctx)

        val queue = QueueStore(ctx).shareQueue()
        assertEquals(3, queue.size)
        assertTrue(queue.any { it.url == "https://youtu.be/kee333pee33" })
        assertTrue(queue.any { it.url == "https://youtu.be/aaa111bbb22" })
        assertTrue(queue.any { it.url == "https://www.youtube.com/watch?v=ccc333ddd44" })
        assertTrue(QueueStore(ctx).migrated)
    }
}
