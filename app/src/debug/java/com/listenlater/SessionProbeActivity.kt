package com.listenlater

import android.app.Activity
import android.os.Bundle
import java.io.File

/**
 * CI-only (debug builds): reports whether the session fields the queue worker's
 * consumption gate reads (token AND server - ShareWorker holds the queue when
 * either is empty) are present - never their values. Needed because
 * EncryptedSharedPreferences encrypts key names, so no file-content check can
 * separate real credentials from the benign needsLogin/loginNotified flags the
 * app writes at startup when a queued item has no session (CI run 45313566190).
 * Result goes to files/session_probe.txt.
 */
class SessionProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = File(filesDir, "session_probe.txt")
        try {
            val store = CredStore(applicationContext)
            val token = if (store.token.isNullOrEmpty()) "TOKEN_ABSENT" else "TOKEN_PRESENT"
            val server = if (store.server.isNullOrEmpty()) "SERVER_ABSENT" else "SERVER_PRESENT"
            out.writeText(token + " " + server)
        } catch (e: Exception) {
            // An unreadable store is NOT proof of absence.
            out.writeText("UNREADABLE:" + e.javaClass.simpleName)
        }
        finish()
    }
}
