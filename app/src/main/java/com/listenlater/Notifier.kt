package com.listenlater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {

    private const val CHANNEL = "listenlater_alerts"
    private const val ID_SIGNIN = 1
    private const val ID_SKIPPED_BASE = 1000
    const val EXTRA_URL = "com.listenlater.extra.URL"

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

    /** The sign-in ask is answered; clear it no matter how he got in. */
    fun clearSignIn(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_SIGNIN)
    }

    private fun skippedId(url: String) = ID_SKIPPED_BASE + (url.hashCode() and 0xFFFF)

    /**
     * A video the server refused. Names the video and why, one notification per video;
     * tapping opens the video link, Try again re-queues it now.
     */
    fun skipped(context: Context, url: String, title: String, reason: String) {
        ensureChannel(context)
        val id = skippedId(url)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val retry = PendingIntent.getBroadcast(
            context, id,
            Intent(context, SkippedRetryReceiver::class.java).putExtra(EXTRA_URL, url),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val name = if (title.isNotEmpty()) title else url
        val text = "$name - $reason"
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Couldn't get a video")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$url"))
            .setContentIntent(open)
            .addAction(0, "Try again", retry)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // Notification permission not granted; the list row still shows it.
        }
    }

    fun cancelSkipped(context: Context, url: String) {
        NotificationManagerCompat.from(context).cancel(skippedId(url))
    }

    fun toast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
