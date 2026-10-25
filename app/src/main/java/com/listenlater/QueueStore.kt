package com.listenlater

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Share queue with per-link retry state, and the "Couldn't get it" list.
 *
 * Each queued link carries its own title, retry count, next retry time, and the
 * moment the server started answering unexpectedly - so one stuck video can be
 * moved to the back and the rest get a turn, and the list can say what a row is
 * really doing. Older installs stored a bare list of links; both forms read fine.
 *
 * Plain preferences on purpose: these are YouTube links and app state, not secrets -
 * the API key only ever lives in the encrypted store. If encrypted storage ever has to
 * start fresh, this survives and a wipe costs only a sign-in, never the queue.
 *
 * Every read-modify-write runs under one lock so a share landing at the exact moment
 * the worker removes an item cannot be lost, and whole worker runs are serialized by
 * [QueueLock] in ShareWorker. Nothing here may touch the queue before startup
 * migration has run: every caller awaits [ListenLaterApp.ready] first.
 */
class QueueStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("queue_store", Context.MODE_PRIVATE)

    /** One queued share and everything the retry loop needs to know about it. */
    data class Queued(
        val url: String,
        val title: String = "",
        val attempts: Int = 0,
        val nextRetryAt: Long = 0L,
        val unexpectedSince: Long? = null
    )

    /** A video the server refused, kept so he can Try again. Clears itself after about a day. */
    data class Skipped(val url: String, val title: String, val reason: String, val addedAt: Long = 0L)

    /** Failed items clear themselves after this long. */
    private val skippedMaxAgeMs = 24 * 60 * 60 * 1000L

    /** A version the user chose to skip; not offered again. */
    var skippedUpdate: String?
        get() = prefs.getString("skipped_update", null)
        set(v) = prefs.edit().putString("skipped_update", v).apply()

    /** Set once the one-time move out of the old encrypted store has run. */
    var migrated: Boolean
        get() = prefs.getBoolean("migrated", false)
        set(v) = prefs.edit().putBoolean("migrated", v).apply()

    fun shareQueue(): List<Queued> = synchronized(QueueStore::class.java) {
        val raw = prefs.getString("queue", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val entry = arr.get(i)
                if (entry is JSONObject) {
                    Queued(
                        url = entry.optString("u", ""),
                        title = entry.optString("t", ""),
                        attempts = entry.optInt("a", 0),
                        nextRetryAt = entry.optLong("r", 0L),
                        unexpectedSince = if (entry.has("x")) entry.optLong("x") else null
                    )
                } else {
                    // Older installs stored bare links.
                    Queued(url = entry.toString())
                }
            }.filter { it.url.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveQueue(list: List<Queued>) {
        val arr = JSONArray()
        list.forEach { q ->
            val o = JSONObject().put("u", q.url).put("t", q.title).put("a", q.attempts).put("r", q.nextRetryAt)
            if (q.unexpectedSince != null) o.put("x", q.unexpectedSince)
            arr.put(o)
        }
        prefs.edit().putString("queue", arr.toString()).apply()
    }

    fun enqueueShare(url: String, title: String = ""): Unit = synchronized(QueueStore::class.java) {
        val q = shareQueue().toMutableList()
        if (q.none { it.url == url }) {
            q.add(Queued(url, title))
            saveQueue(q)
        }
    }

    fun removeFromQueue(url: String): Unit = synchronized(QueueStore::class.java) {
        val q = shareQueue().toMutableList()
        if (q.removeAll { it.url == url }) saveQueue(q)
    }

    /** After a failed try the link goes to the back so the others get a turn. */
    fun moveToBack(url: String): Unit = synchronized(QueueStore::class.java) {
        val q = shareQueue().toMutableList()
        val item = q.firstOrNull { it.url == url } ?: return@synchronized
        q.remove(item)
        q.add(item)
        saveQueue(q)
    }

    /** Update one queued link's retry state in place. */
    fun updateQueued(url: String, change: (Queued) -> Queued): Unit = synchronized(QueueStore::class.java) {
        val q = shareQueue().map { if (it.url == url) change(it) else it }
        saveQueue(q)
    }

    /** Used by the one-time migration; takes the old encrypted store's raw JSON. */
    fun importQueue(rawJson: String): Unit = synchronized(QueueStore::class.java) {
        try {
            JSONArray(rawJson) // validate before trusting it
            prefs.edit().putString("queue", rawJson).apply()
        } catch (e: Exception) {
            // Corrupt legacy value: leave the queue empty rather than crash a first launch.
        }
    }

    fun skipped(): List<Skipped> = synchronized(QueueStore::class.java) {
        val raw = prefs.getString("skipped", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            var backfilled = false
            val list = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                var addedAt = o.optLong("w", 0L)
                if (addedAt == 0L) {
                    // Legacy entries carry no timestamp: start their day at the update, not retroactively.
                    addedAt = System.currentTimeMillis()
                    backfilled = true
                }
                Skipped(o.optString("u", ""), o.optString("t", ""), o.optString("r", ""), addedAt)
            }.filter { it.url.isNotEmpty() }
            if (backfilled) saveSkipped(list)
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveSkipped(list: List<Skipped>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title).put("r", it.reason).put("w", it.addedAt)) }
        prefs.edit().putString("skipped", arr.toString()).apply()
    }

    fun addSkipped(url: String, title: String, reason: String): Unit = synchronized(QueueStore::class.java) {
        val list = skipped().filter { it.url != url }.toMutableList()
        list.add(Skipped(url, title, reason, System.currentTimeMillis()))
        saveSkipped(list)
    }

    fun removeSkipped(url: String): Unit = synchronized(QueueStore::class.java) {
        val list = skipped().filter { it.url != url }
        if (list.size != skipped().size) saveSkipped(list)
    }

    /**
     * Failed items clear themselves after about a day. Returns the links that were
     * cleared so their notifications can be cancelled at the same moment.
     */
    fun sweepExpiredSkipped(nowMs: Long = System.currentTimeMillis()): List<String> = synchronized(QueueStore::class.java) {
        val before = skipped()
        val expired = before.filter { nowMs - it.addedAt >= skippedMaxAgeMs }
        if (expired.isEmpty()) return@synchronized emptyList()
        saveSkipped(before.filter { nowMs - it.addedAt < skippedMaxAgeMs })
        expired.map { it.url }
    }

    /**
     * Legacy "Couldn't reach your server" items parked before retries became unlimited:
     * hand them back to the queue on the next successful send or list load. Removes and
     * returns them so notifications can be cancelled.
     */
    fun takeUnreachableSkipped(): List<Skipped> = synchronized(QueueStore::class.java) {
        val all = skipped()
        val parked = all.filter { it.reason == "Couldn't reach your server" }
        if (parked.isEmpty()) return@synchronized emptyList()
        saveSkipped(all.filter { it.reason != "Couldn't reach your server" })
        parked
    }

    /** Account switch: nothing from the previous account carries over. */
    fun clearAll(): Unit = synchronized(QueueStore::class.java) {
        prefs.edit().remove("queue").remove("attempts").remove("skipped").apply()
    }

    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
}
