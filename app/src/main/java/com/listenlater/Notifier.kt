package com.listenlater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {

    private const val CHANNEL = "listenlater_alerts"
    private const val ID_SIGNIN = 1
    private const val ID_EXPIRY = 2

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Listen Later alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun post(context: Context, id: Int, text: String) {
        ensureChannel(context)
        val pi = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Listen Later")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // Notification permission not granted; nothing else to do.
        }
    }

    fun notifySignInOnce(context: Context, store: CredStore) {
        if (store.loginNotified) return
        store.loginNotified = true
        post(context, ID_SIGNIN, "Sign in again to keep sending videos.")
    }

    fun notifyExpiry(context: Context, store: CredStore) {
        post(context, ID_EXPIRY, "Your sign-in runs out soon. Open the app to renew it.")
    }

    fun toast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
