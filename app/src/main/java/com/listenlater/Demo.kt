package com.listenlater

import android.content.Context

/**
 * Debug builds only: canned data so CI screenshots and manual checks have something
 * real to show. Activated with `--ez demo true` on the launch intent; never in release.
 */
object Demo {
    var active = false
    var forceLoadFail = false

    data class Ep(val id: String, val title: String, val status: String)

    val episodes = listOf(
        Ep("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up (Official Video)", "COMPLETED"),
        Ep("x1x1x1x1x1x", "A quite long video title that keeps going and going well past what two lines can hold on any phone screen", "COMPLETED"),
        Ep("y2y2y2y2y2y", "Weekend talk show, episode twelve", "FAILED"),
        Ep("z3z3z3z3z3z", "Cooking along: soup for cold days", "DOWNLOADING"),
        Ep("w4w4w4w4w4w", "Old interview from the archive", "READY"),
    )

    /** Signed in, one share queued, one skipped video, five episodes in every state. */
    fun seedSignedIn(ctx: Context) {
        val store = CredStore(ctx)
        store.markLoggedIn("https://demo.listenlater.example", "demo", "demo-token", "", false)
        store.feedUrl = "https://demo.listenlater.example/api/feed/individual-videos-youtube"
        val queue = QueueStore(ctx)
        queue.clearAll()
        queue.enqueueShare("https://www.youtube.com/watch?v=a1b2c3d4e5f")
        queue.addSkipped(
            "https://www.youtube.com/watch?v=g6h7i8j9k0l",
            "A video YouTube would not give us",
            "This video is members-only."
        )
    }

    /** Signed out with shares waiting: the sign-in screen shows the waiting line. */
    /**
     * CI screenshots: queued rows in each real status, with the real shared titles -
     * sending, retrying at a time, unexpected server replies past an hour, and one
     * very long title for the wrap check.
     */
    fun seedQueueStates(ctx: Context) {
        val now = System.currentTimeMillis()
        val json = org.json.JSONArray()
        fun row(u: String, t: String, a: Int, r: Long, x: Long?) {
            val o = org.json.JSONObject().put("u", u).put("t", t).put("a", a).put("r", r)
            if (x != null) o.put("x", x)
            json.put(o)
        }
        row("https://www.youtube.com/watch?v=q1w2e3r4t5y", "The video he just shared from YouTube", 0, 0L, null)
        row("https://www.youtube.com/watch?v/z1x2c3v4b5n", "A second share waiting for its retry", 1, now + 25 * 60 * 1000L, null)
        row("https://www.youtube.com/watch?v/p1o2i3u4y5t", "A share the server keeps answering oddly", 2, now + 45 * 60 * 1000L, now - 2 * 60 * 60 * 1000L)
        row("https://www.youtube.com/watch?v/l1k2j3h4g5f", "An extremely long shared video title that keeps going and going well past what two lines of the row can hold on any phone screen", 0, 0L, null)
        QueueStore(ctx).importQueue(json.toString())
    }

    fun seedSignedOut(ctx: Context) {
        val store = CredStore(ctx)
        store.server = "https://demo.listenlater.example"
        store.username = "demo"
        store.signOut()
        val queue = QueueStore(ctx)
        queue.clearAll()
        queue.enqueueShare("https://www.youtube.com/watch?v=a1b2c3d4e5f")
        queue.enqueueShare("https://www.youtube.com/watch?v=m1n2o3p4q5r")
    }
}
