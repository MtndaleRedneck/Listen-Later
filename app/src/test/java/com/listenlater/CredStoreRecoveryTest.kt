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
 * arrive, the replacement is staged and every live store - and every later one - follows
 * the file again: a later sign-out must still be there after a full reopen from disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CredStoreRecoveryTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        CredStore.resetForTests()
    }

    @Test
    fun failedOpenThenFreshLoginThenSignOutSurvivesReopen() {
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
        val creds = File(dir, "creds.xml")
        creds.writeText("this is neither xml nor encrypted")
        CredStore.resetForTests()

        val store = CredStore(ctx)
        assertNull("an unreadable file means signed out in memory", store.token)
        assertTrue("the unreadable file is preserved, not deleted", creds.readText().startsWith("this is neither"))

        store.markLoggedIn("http://localhost", "u", "T", "p", false)
        // The sign-in staged a fresh file and flipped every live store to it.
        assertEquals("T", store.token)
        CredStore.resetForTests() // prove it is on disk, not in the process
        assertEquals("T", CredStore(ctx).token)

        store.signOut()
        CredStore.resetForTests()
        val after = CredStore(ctx)
        assertNull("a sign-out after recovery is a disk write, not a memory one", after.token)
        assertTrue(after.signedOut)
    }
}
