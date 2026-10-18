package com.listenlater

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** The login token is a fixed 30 days (not sliding), so warn quietly at day 28. */
class ExpiryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val store = CredStore(applicationContext)
        if (store.token.isNullOrEmpty() || store.loginAtMs <= 0L) return Result.success()
        val ageMs = System.currentTimeMillis() - store.loginAtMs
        if (!store.expiryNotified && ageMs >= 28L * 24 * 60 * 60 * 1000) {
            store.expiryNotified = true
            Notifier.notifyExpiry(applicationContext, store)
        }
        return Result.success()
    }
}
