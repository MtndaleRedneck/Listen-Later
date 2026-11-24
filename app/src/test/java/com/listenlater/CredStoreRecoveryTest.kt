package com.listenlater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The unreadable login file is never deleted by a read failure. When fresh credentials
 * arrive, the original bytes move aside (never deleted, never overwritten), the fresh file
 * is created under its final name - encrypted keys bind that name - the framework cache is
 * evicted, and every live store - and every later one - follows
 * the file again: a later sign-out must still be there after a full reopen from disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CredStoreRecoveryTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun writeInvalidKeysetCreds(f: File, marker: String) {
        // Parseable SharedPreferences XML holding an "encrypted" entry whose keysets are
        // base64 but not valid keyset protos: EncryptedSharedPreferences init fails on the
        // keyset read - the recovery branch an unreadable login actually takes.
        f.writeText(
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
            "<map>\n" +
            "    <string name=\"__androidx_security_crypto_encrypted_prefs_key_keyset__\">aW52YWxpZA==</string>\n" +
            "    <string name=\"__androidx_security_crypto_encrypted_prefs_value_keyset__\">aW52YWxpZA==</string>\n" +
            "    <string name=\"not-a-real-entry\">$marker</string>\n" +
            "</map>\n"
        )
    }

    @Before
    fun setUp() {
        CredStore.resetForTests()
        // Robolectric has no AndroidKeyStore, so the real encrypted factory always fails
        // here. This override reproduces the exact production decision: a file carrying
        // keysets it cannot read (the fixture) fails to open; a file written by the
        // replacement store (tagged) opens fine. Everything between - the preservation,
        // the swap, the rollback - is the production code.
        CredStore.createOverrideForTests = { c ->
            val f = File(c.applicationInfo.dataDir, "shared_prefs/creds.xml")
            if (f.isFile && !f.readText().contains("__test_readable__")) {
                throw java.security.GeneralSecurityException("test: creds file is unreadable")
            }
            c.getSharedPreferences("creds", Context.MODE_PRIVATE)
                .also { it.edit().putBoolean("__test_readable__", true).commit() }
        }
    }

    @org.junit.After
    fun tearDown() {
        CredStore.createOverrideForTests = null
    }

    @Test
    fun failedOpenThenFreshLoginThenSignOutSurvivesReopen() {
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
        val creds = File(dir, "creds.xml")
        writeInvalidKeysetCreds(creds, "original-login-marker")
        CredStore.resetForTests()

        val store = CredStore(ctx)
        assertNull("an unreadable file means signed out in memory", store.token)
        assertTrue("the unreadable file is preserved, not deleted", creds.readText().contains("original-login-marker"))

        store.markLoggedIn("http://localhost", "u", "T", "p", false)
        // The sign-in wrote a fresh file under the final name and flipped every live store.
        val aside = File(dir, "creds.unreadable.xml")
        assertTrue("the original bytes survive, moved aside", aside.readText().contains("original-login-marker"))
        assertEquals("T", store.token)
        CredStore.resetForTests() // prove it is on disk, not in the process
        assertEquals("T", CredStore(ctx).token)

        store.signOut()
        CredStore.resetForTests()
        val after = CredStore(ctx)
        assertNull("a sign-out after recovery is a disk write, not a memory one", after.token)
        assertTrue(after.signedOut)
    }

    @Test
    fun aPreservedAsideIsNeverOverwritten() {
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
        File(dir, "creds.unreadable.xml").writeText("generation one, the only copy")
        writeInvalidKeysetCreds(File(dir, "creds.xml"), "generation two, also unreadable")
        CredStore.resetForTests()

        val store = CredStore(ctx) // memory fallback again
        store.markLoggedIn("http://localhost", "u", "T", "p", false)
        assertEquals("generation one is untouched", "generation one, the only copy", File(dir, "creds.unreadable.xml").readText())
        assertTrue("generation two is preserved under a new name", File(dir, "creds.unreadable.2.xml").readText().contains("generation two, also unreadable"))
    }

    @Test
    fun aFailedRecreateRollsTheOriginalBytesBack() {
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
        val creds = File(dir, "creds.xml")
        writeInvalidKeysetCreds(creds, "rollback-marker")
        CredStore.resetForTests()

        val store = CredStore(ctx)
        CredStore.failNextCreateForTests = true
        store.markLoggedIn("http://localhost", "u", "T", "p", false)
        assertTrue("the original bytes are restored after a failed recreate", creds.readText().contains("rollback-marker"))
        CredStore.resetForTests()
        assertNull("the session stays memory-only and signed out", CredStore(ctx).token)
    }
}
