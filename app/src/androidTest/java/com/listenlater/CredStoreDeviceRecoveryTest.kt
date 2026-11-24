package com.listenlater

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real-Keystore version of CredStoreRecoveryTest: an emulator has a real
 * AndroidKeyStore and real Tink decryption, so the unreadable-login recovery runs
 * the exact production crypto path the unit tests can only simulate. The login
 * file is made unreadable by replacing its keyset entries with strings that are
 * not valid hex: tink-android 1.8.0's readKeysetFromPrefs then throws a
 * CharConversionException out of EncryptedSharedPreferences.create, which is the
 * same create() failure an undecryptable keyset (lost/unusable master key)
 * produces, so the production fallback runs for real.
 *
 * Every byte-for-byte snapshot in this class is taken AFTER the corruption: the
 * recovery preserves the UNREADABLE bytes it found, and corruptKeysets() ends in
 * a synchronous commit() so the snapshot cannot race the login's async write.
 */
@RunWith(AndroidJUnit4::class)
class CredStoreDeviceRecoveryTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir get() = File(ctx.applicationInfo.dataDir, "shared_prefs")
    private fun credsFile() = File(dir, "creds.xml")
    private fun raw() = ctx.getSharedPreferences("creds", Context.MODE_PRIVATE)

    private fun clearAll() {
        CredStore.resetForTests()
        CredStore.createOverrideForTests = null
        CredStore.failNextCreateForTests = false
        ctx.deleteSharedPreferences("creds")
        dir.listFiles()?.filter { it.name == "creds.xml" || it.name.startsWith("creds.unreadable") }
            ?.forEach { it.delete() }
    }

    @Before fun setUp() = clearAll()
    @After fun tearDown() = clearAll()

    /** Replace the (plaintext-named) keyset entries with invalid hex: the next
     * EncryptedSharedPreferences.create fails its keyset read with a
     * CharConversionException, exactly like an undecryptable login file. Ends in
     * commit(), so the disk state is settled when this returns. */
    private fun corruptKeysets() {
        val edit = raw().edit()
        raw().all.keys
            .filter { it.contains("keyset") }
            .forEach { edit.putString(it, "aW52YWxpZA==") }
        assertTrue("fixture must find real keyset entries to corrupt", raw().all.keys.any { it.contains("keyset") })
        edit.commit()
    }

    @Test
    fun realLoginSurvivesAFullReopenFromDisk() {
        CredStore(ctx).markLoggedIn("http://srv", "u", "T0", "p", false)
        assertTrue("a real encrypted file exists", credsFile().isFile)
        CredStore.resetForTests() // drop every process-lived object
        assertEquals("the session is re-read from the real encrypted file", "T0", CredStore(ctx).token)
        assertEquals("u", CredStore(ctx).username)
    }

    @Test
    fun corruptKeysetsFallBackToMemoryAndPreserveEveryByte() {
        CredStore(ctx).markLoggedIn("http://srv", "u", "T1", "p", false)
        corruptKeysets()
        val original = credsFile().readBytes() // the unreadable bytes recovery must preserve

        CredStore.resetForTests()
        val store = CredStore(ctx)
        assertNull("an unreadable file means signed out in memory", store.token)
        assertArrayEquals("the unreadable file is preserved, byte for byte", original, credsFile().readBytes())
    }

    @Test
    fun freshLoginMovesTheOriginalAsideAndTheReplacementReadsBack() {
        CredStore(ctx).markLoggedIn("http://srv", "u", "T1", "p", false)
        corruptKeysets()
        val original = credsFile().readBytes() // the unreadable bytes recovery must set aside
        CredStore.resetForTests()
        val store = CredStore(ctx)
        assertNull(store.token)

        store.markLoggedIn("http://srv", "u", "T2", "p", false)
        val aside = File(dir, "creds.unreadable.xml")
        assertTrue("the original bytes moved aside, not deleted", aside.isFile)
        assertArrayEquals(original, aside.readBytes())
        assertEquals("T2", store.token)

        CredStore.resetForTests()
        assertEquals("the replacement is a real encrypted file that reads back", "T2", CredStore(ctx).token)

        CredStore(ctx).signOut()
        CredStore.resetForTests()
        val after = CredStore(ctx)
        assertNull("a sign-out after recovery is a disk write", after.token)
        assertTrue(after.signedOut)
    }

    @Test
    fun aPreservedAsideIsNeverOverwrittenByALaterRecovery() {
        CredStore(ctx).markLoggedIn("http://srv", "u", "T1", "p", false)
        corruptKeysets()
        val generationOne = credsFile().readBytes() // the unreadable bytes of generation one
        CredStore.resetForTests()
        CredStore(ctx).markLoggedIn("http://srv", "u", "T2", "p", false)
        assertArrayEquals(generationOne, File(dir, "creds.unreadable.xml").readBytes())

        // The replacement becomes unreadable too: its recovery must not touch generation one.
        corruptKeysets()
        val generationTwo = credsFile().readBytes() // the unreadable bytes of generation two
        CredStore.resetForTests()
        CredStore(ctx).markLoggedIn("http://srv", "u", "T3", "p", false)

        assertArrayEquals("generation one is untouched", generationOne, File(dir, "creds.unreadable.xml").readBytes())
        assertArrayEquals("generation two is preserved under a new name", generationTwo, File(dir, "creds.unreadable.2.xml").readBytes())
        CredStore.resetForTests()
        assertEquals("T3", CredStore(ctx).token)
    }

    @Test
    fun aCrashedWriteBackupIsPreservedAsideWithItsGeneration() {
        CredStore(ctx).markLoggedIn("http://srv", "u", "T1", "p", false)
        corruptKeysets()
        val unreadable = credsFile().readBytes()
        CredStore.resetForTests()
        val store = CredStore(ctx)
        assertNull(store.token)

        // A write interrupted mid-swap leaves the framework's own backup next to the
        // unreadable file: the PREVIOUS generation's bytes, distinct from the current
        // file's, so the preservation assertions can tell the two generations apart.
        // SharedPreferencesImpl would resurrect a leftover .bak over any fresh file and
        // deleteSharedPreferences would delete it, so recovery must move it aside too.
        val backupBytes = byteArrayOf(1, 2, 3, 4) + unreadable
        java.io.File(dir, "creds.xml.bak").writeBytes(backupBytes)
        store.markLoggedIn("http://srv", "u", "T2", "p", false)

        assertArrayEquals("the unreadable file moved aside",
            unreadable, java.io.File(dir, "creds.unreadable.xml").readBytes())
        assertArrayEquals("the crashed-write backup moved aside as its OWN generation, not deleted",
            backupBytes, java.io.File(dir, "creds.unreadable.xml.bak").readBytes())
        assertEquals("the fresh login landed", "T2", store.token)
        CredStore.resetForTests()
        assertEquals("and reads back from the fresh file, no resurrection",
            "T2", CredStore(ctx).token)
    }

    @Test
    fun aFailedRecreateRollsTheOriginalBytesBack() {
        CredStore(ctx).markLoggedIn("http://srv", "u", "T1", "p", false)
        corruptKeysets()
        val original = credsFile().readBytes() // the unreadable bytes recovery must set aside
        CredStore.resetForTests()
        val store = CredStore(ctx)
        assertNull(store.token)

        // A crashed-write backup from the previous generation is present too: the
        // rollback must restore it alongside the main file.
        val backupBytes = byteArrayOf(9, 8, 7) + original
        java.io.File(dir, "creds.xml.bak").writeBytes(backupBytes)

        CredStore.failNextCreateForTests = true
        store.markLoggedIn("http://srv", "u", "T2", "p", false)

        assertArrayEquals("the original bytes are restored after a failed recreate", original, credsFile().readBytes())
        assertArrayEquals("the previous generation's backup is restored too", backupBytes, java.io.File(dir, "creds.xml.bak").readBytes())
        assertTrue("no aside is left behind after a rollback", !File(dir, "creds.unreadable.xml").exists())
        assertTrue("no backup aside is left behind after a rollback", !File(dir, "creds.unreadable.xml.bak").exists())
        CredStore.resetForTests()
        assertNull("the session stays memory-only and signed out", CredStore(ctx).token)
    }
}
