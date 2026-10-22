package com.listenlater

import android.app.Application

class ListenLaterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        // One-time move of the queue and friends out of the encrypted store (see QueueStore).
        CredStore.migrateToPlain(this)
        // No update is ever mid-install at a cold start; clear any leftover APK.
        Updater.clean(this)
    }
}
