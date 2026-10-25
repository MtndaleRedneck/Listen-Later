package com.listenlater

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ListenLaterApp : Application() {

    /**
     * Completed once the one-time migration and the credential store are ready.
     * Everything that touches the queue or credentials - the opening screen, the
     * share sheet, the worker, the retry receiver - awaits this first, so a share
     * or a worker starting mid-migration can never see a half-imported state.
     * Completed even on failure (the Keystore-down path runs signed out in memory);
     * readers must never hang.
     */
    val ready = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        // No update is ever mid-install at a cold start; clear any leftover APK.
        Updater.clean(this)
        val app = this
        // Storage work never runs on the main thread: migration and the first
        // encrypted-store open both happen here, before any reader proceeds.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                CredStore.migrateToPlain(app)
                CredStore.warm(app)
            } finally {
                ready.complete(Unit)
            }
        }
    }
}
