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
 * file is made unreadable by replacing its keyset entries with parseable but
 * invalid base64 - the same failure a genuinely undecryptable file produces.
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

    /** Replace the (plaintext-named) keyset entries with parseable-but-invalid
     * base64: the next EncryptedSharedPreferences.create fails its keyset read,
     * exactly like an undecryptable login file. */
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
        val original = credsFile().readBytes()
        corruptKeysets()

        CredStore.resetForTests()
        val store = CredStore(ctx)
        assertNull("an unreadable file means signed out in memory", store.token)
        assertArrayEquals("the unreadable file is preserved, byte for byte", original, credsFile().readBytes())
    }

    @Test
    fun freshLoginMovesTheOriginalAsideAndTheReplacementReadsBack() {
        CredStore(ctx).markLoggedIn("http://srv", "u", "T1", "p", false)
        val original = credsFile().readBytes()
        corruptKeysets()
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
        val generationOne = credsFile().readBytes()
        corruptKeysets()
        CredStore.resetForTests()
        CredStore(ctx).markLoggedIn("http://srv", "u", "T2", "p", false)
        assertArrayEquals(generationOne, File(dir, "creds.unreadable.xml").readBytes())

        // The replacement becomes unreadable too: its recovery must not touch generation one.
        val generationTwo = credsFile().readBytes()
        corruptKeysets()
        CredStore.resetForTests()
        CredStore(ctx).markLoggedIn("http://srv", "u", "T3", "p", false)

        assertArrayEquals("generation one is untouched", generationOne, File(dir, "creds.unreadable.xml").readBytes())
        assertArrayEquals("generation two is preserved under a new name", generationTwo, File(dir, "creds.unreadable.2.xml").readBytes())
        CredStore.resetForTests()
        assertEquals("T3", CredStore(ctx).token)
    }

    @Test
    fun aFailedRecreateRollsTheOriginalBytesBack() {
        CredStore(ctx).markLoggedIn("http://srv", "u", "T1", "p", false)
        val original = credsFile().readBytes()
        corruptKeysets()
        CredStore.resetForTests()
        val store = CredStore(ctx)
        assertNull(store.token)

        CredStore.failNextCreateForTests = true
        store.markLoggedIn("http://srv", "u", "T2", "p", false)

        assertArrayEquals("the original bytes are restored after a failed recreate", original, credsFile().readBytes())
        assertTrue("no aside is left behind after a rollback", !File(dir, "creds.unreadable.xml").exists())
        CredStore.resetForTests()
        assertNull("the session stays memory-only and signed out", CredStore(ctx).token)
    }
}
