package com.listenlater

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class ListenLaterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "expiry-check",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ExpiryWorker>(1, TimeUnit.DAYS).build()
        )
    }
}
